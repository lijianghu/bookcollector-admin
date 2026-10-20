package com.bookcollector.audit.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bookcollector.audit.dto.ReplayRequest;
import com.bookcollector.audit.dto.ReplayResult;
import com.bookcollector.collector.WereadHeaders;
import com.bookcollector.credential.WereadCredentialStore;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 「回放」：按提交的请求再发一次（2026-10-15 晚新增 → 当晚 redesign 成可编辑请求 →
 * 深夜换传输实现）。
 *
 * <h3>🔴 为什么从 JDK HttpURLConnection 换成 Apache HttpClient（书单那个客户端）</h3>
 * 用户 2026-10-15 实测：书单接口<b>带了 {@code User-Agent} 只回拍平形态</b>，
 * <b>不带才回带 {@code bookInfo} 的完整形态</b>。JDK 的 {@code HttpURLConnection}
 * <b>做不到「不发 UA」</b>：没设 {@code User-Agent} 时它自己补
 * {@code User-Agent: Java/1.8.0_181}；{@code Connection} 之类受限头设了还会被静默丢弃。
 * 所以回放复用书单采集那个 {@code booklistHttpClient}
 * （{@link com.bookcollector.config.RestTemplateConfig}，协议处理器末尾摘掉 UA）——
 * <b>「回放发的头」与「书单采集发的头」就是同一套</b>，可比性才对得上。
 * 代价诚实说在这里：回放不再是「最原始的 http」，线上仍会有协议层必需的 {@code Host}
 * （任何客户端都会加，Postman 也一样）。
 *
 * <h3>🔴 入参是「这次要发的请求」，不是记录 ID</h3>
 * 用户要求「url、参数和请求头都可以修改，按本次最新值发起请求」——
 * 弹窗里的参数行是 URL query 的可编辑视图（改行 = 前端重建 query 回写 URL），
 * 所以 URL 是唯一真相，后端<b>不单独收 params</b>（收两份必然出现「听谁的」的歧义）。
 *
 * <h3>skey 规则：真值照用，掩码 / 空才替换</h3>
 * 见 {@link #resolveHeaders}。⚠️ 掩码值绝不发出去是底线。
 *
 * <h3>不写审计</h3>
 * 回放是诊断动作，不是采集。把它记进 {@code api_requests} 会污染
 * 「这个目标采了多少次」的口径，所以这里什么都不落库。
 */
@Component
public class RequestReplayer {

    private static final Logger log = LoggerFactory.getLogger(RequestReplayer.class);

    /** 读响应体的缓冲区 */
    private static final int BUFFER_SIZE = 8192;

    /** {@code skey} 头的名字 */
    private static final String SKEY_HEADER = "skey";

    /** 掩码标记。提交值里带它就说明前端给的是展示用的掩码，不是真 skey */
    private static final String MASK_MARK = "****";

    /** skey 来源标记：用了提交的真值 */
    private static final String SKEY_FROM_SUBMITTED = "SUBMITTED";

    /** skey 来源标记：提交的是掩码 / 留空，替换成了当前凭证的 skey */
    private static final String SKEY_FROM_CREDENTIAL = "CREDENTIAL";

    private final WereadCredentialStore credentials;

    /** 与书单采集同一个客户端（不发 {@code User-Agent}）—— 回放要复现的就是书单链路的请求 */
    private final CloseableHttpClient httpClient;

    public RequestReplayer(WereadCredentialStore credentials,
                           @Qualifier("booklistHttpClient") CloseableHttpClient httpClient) {
        this.credentials = credentials;
        this.httpClient = httpClient;
    }

    /**
     * 按提交的请求回放。
     *
     * <p>URL 用提交的那个（含 query）原样发；请求头以提交的为准，
     * skey 按 {@link #resolveHeaders} 的规则处理。HTTP 异常不抛出 —— 装进
     * {@link ReplayResult#getErrorMsg()}，让弹窗原样展示。
     *
     * @param req 这次要发的请求（url 非空；requestHeaders 可空）
     * @return 结果对象
     * @throws IllegalStateException 书单域名但凭证八项缺一（调用方转成
     *         {@code BizException.warn} 弹给人看）
     */
    public ReplayResult replay(ReplayRequest req) {
        String url = req.getUrl().trim();

        // 域名 → 要不要过书单凭证校验、skey 怎么处理。判据与采集同一处
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (RuntimeException e) {
            host = null;
        }
        boolean booklist = WereadHeaders.isBooklistHost(host, credentials.booklistBaseUrl());
        if (booklist) {
            credentials.requireBooklistComplete();
        }

        Resolved resolved = resolveHeaders(req.getRequestHeaders(), booklist);
        Map<String, String> headers = resolved.headers;

        // 「实际发送」清单里不列 User-Agent：回放用的客户端在协议处理器末尾**无条件**摘掉它
        // （见 RestTemplateConfig#StripUserAgent），列出来会让用户以为 UA 发出去了。
        // ⚠️ 忽略大小写地筛 —— 客户端摘除也是忽略大小写的（HeaderGroup.removeHeaders）。
        Map<String, String> reported = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!e.getKey().equalsIgnoreCase("User-Agent")) {
                reported.put(e.getKey(), e.getValue());
            }
        }

        ReplayResult result = ReplayResult.builder()
                .url(url)
                .queryParams(WereadHeaders.parseQuery(url))
                .requestHeaders(WereadHeaders.snapshot(reported))
                .skeyFrom(resolved.skeyFrom)
                .build();

        long start = System.currentTimeMillis();
        try {
            fillResponse(result, url, headers);
        } catch (Exception e) {
            // ⚠️ 不无条件把状态码清成 0：拿到状态码之后才失败的（比如解压 body 出错）
            //    仍要如实显示那个状态码，否则排查时会被误导成「请求没发出去」。
            if (result.getStatusCode() <= 0) {
                result.setStatusCode(0);
            }
            if (result.getBody() == null) {
                result.setBody("");
            }
            result.setErrorMsg(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.setCostMs(System.currentTimeMillis() - start);
        probe(result, result.getBody());

        log.info("回放 {} → 状态 {}，{}ms；{}{}", url, result.getStatusCode(), result.getCostMs(),
                result.getShapeHint() == null ? "" : result.getShapeHint(),
                result.getErrorMsg() == null ? "" : "；错误 " + result.getErrorMsg());
        return result;
    }

    /**
     * 最终实际发送的头清单。
     *
     * <h3>🔴 skey 规则：真值照用，掩码 / 空才替换</h3>
     * 用户实测「同样的 URL / 参数 / 请求头，回放没有 bookInfo、Postman 有」，
     * 追问「你额外做了什么」—— 当时 skey 是唯一一处<b>不照抄用户输入</b>的地方。
     * 现在：
     * <ol>
     *   <li>提交值<b>可用</b>（非空、且不含 {@code ****}）→ <b>照用</b>（用户能粘贴 Postman 那把）</li>
     *   <li>否则（空 / 掩码）→ 用当前内存凭证的 skey；书单域名缺 skey 行时补上</li>
     * </ol>
     * 来源回在 {@link ReplayResult#getSkeyFrom()} 里，界面直接显示「用了谁的」。
     *
     * <p>保持提交顺序（{@code LinkedHashMap}）—— 展示时要和用户编辑时看到的顺序一致。
     */
    private Resolved resolveHeaders(Map<String, String> submitted, boolean booklist) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (submitted != null) {
            for (Map.Entry<String, String> e : submitted.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    headers.put(e.getKey(), e.getValue());
                }
            }
        }
        String skey = credentials.current().getSkey();
        String submittedSkey = findIgnoreCase(headers, SKEY_HEADER);
        boolean usable = submittedSkey != null && !submittedSkey.trim().isEmpty()
                && !submittedSkey.contains(MASK_MARK);
        if (usable) {
            return new Resolved(headers, SKEY_FROM_SUBMITTED);
        }
        if (submittedSkey == null && !booklist) {
            // 非书单域名、也没提交 skey 行 —— 本次不发 skey
            return new Resolved(headers, null);
        }
        putIgnoreCase(headers, SKEY_HEADER, skey == null ? "" : skey.trim());
        return new Resolved(headers, SKEY_FROM_CREDENTIAL);
    }

    /**
     * {@link #resolveHeaders} 的结果。
     *
     * <p>把「最终头清单」与「skey 到底用了谁的」一起带出来 —— 后者是用户追问
     * 「你额外做了什么」的答案，要回给界面，不能只留在日志里。
     */
    private static final class Resolved {
        private final Map<String, String> headers;
        private final String skeyFrom;

        private Resolved(Map<String, String> headers, String skeyFrom) {
            this.headers = headers;
            this.skeyFrom = skeyFrom;
        }
    }

    private static String findIgnoreCase(Map<String, String> headers, String key) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 按忽略大小写的键覆盖 —— 用户可能把它写成 {@code sKey} / {@code SKEY} */
    private static void putIgnoreCase(Map<String, String> headers, String key, String value) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                e.setValue(value);
                return;
            }
        }
        headers.put(key, value);
    }

    /**
     * 发请求并把状态码与 body 填进 {@code result}。
     *
     * <p>body 的读取与解码：{@code byte[]} → {@code new String(bytes, UTF_8)}，
     * <b>不经过任何 JSON 库</b>（用户要求「不要使用 fastjson 转换，将 json 展示出来」）。
     */
    private void fillResponse(ReplayResult result, String url, Map<String, String> headers)
            throws IOException {
        HttpGet get = new HttpGet(url);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            get.setHeader(h.getKey(), h.getValue());
        }
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            int status = resp.getStatusLine().getStatusCode();
            HttpEntity entity = resp.getEntity();
            byte[] raw = entity == null ? null : EntityUtils.toByteArray(entity);
            result.setStatusCode(status);
            result.setBody(decode(raw, resp.getFirstHeader("Content-Encoding")));
        } finally {
            get.releaseConnection();
        }
    }

    /**
     * 字节 → UTF-8 字符串，必要时<b>手工解 gzip / deflate</b>。
     *
     * <p>为什么要兜这一手：我们没有显式设 {@code Accept-Encoding}，
     * HttpClient 会自己补一个 {@code gzip,deflate}（它默认开启内容压缩），所以响应<b>可能是压缩的</b>。
     * 正常情况下 HttpClient 的响应拦截器会自动解压并摘掉 {@code Content-Encoding}，
     * 这里只是兜「它没解 / 解不了」的情况 —— 不解压拿到的是二进制乱码。
     */
    private String decode(byte[] raw, Header contentEncoding) throws IOException {
        if (raw == null || raw.length == 0) {
            return "";
        }
        String enc = contentEncoding == null ? null : contentEncoding.getValue().toLowerCase();
        boolean compressed = enc != null && (enc.contains("gzip") || enc.contains("deflate"));
        if (!compressed) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        InputStream in = new ByteArrayInputStream(raw);
        InputStream decoded = enc.contains("gzip")
                ? new GZIPInputStream(in)
                : new InflaterInputStream(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = decoded.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        decoded.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 从 body 里取诊断信息：{@code synckey} 与「形状指纹」。
     *
     * <p>⚠️ 只<b>读</b> body、不改它 —— body 本身仍原样返回、原样展示
     * （用户要求「不要使用 fastjson 转换，将 json 展示出来」）。
     * 解析失败一律降级成一句提示，<b>绝不让它把一次成功的回放变成失败</b>。
     *
     * <p>为什么要指纹：书单响应可达约 90 万字符，让人在 body 里翻 {@code books[0]}
     * 找 bookInfo 太费眼，而「为什么没有 bookInfo」恰恰只需要一句结论。
     * ⚠️ 这是<b>诊断提示</b>，不是解析 —— 解析口径仍在 {@code WereadPage} / {@code BookParser}。
     */
    private void probe(ReplayResult result, String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        JSONObject root;
        try {
            root = JSON.parseObject(body);
        } catch (RuntimeException e) {
            String head = body.length() > 120 ? body.substring(0, 120) + "..." : body;
            result.setShapeHint("响应不是合法 JSON，开头：" + head);
            return;
        }
        if (root == null) {
            result.setShapeHint("响应不是 JSON 对象");
            return;
        }

        // 错误信封优先：登录态失效时回的是 {errcode, errlog, errmsg}，没有 books ——
        // 那种情况「没有 bookInfo」根本不是形状问题，别让用户往形状上查。
        Integer errcode = root.getInteger("errcode");
        if (errcode != null && errcode.intValue() != 0) {
            String msg = root.getString("errmsg");
            result.setShapeHint("错误信封：errcode=" + errcode
                    + (msg == null || msg.trim().isEmpty() ? "" : "（" + msg.trim() + "）"));
            return;
        }

        Long synckey = root.getLong("synckey");
        if (synckey != null && synckey.longValue() > 0L) {
            result.setResponseSynckey(synckey);
        }

        JSONObject booklist = root.getJSONObject("booklist");
        if (booklist != null) {
            JSONObject node = booklist.getJSONObject("booklist");
            if (node == null) {
                node = booklist;
            }
            result.setShapeHint(describeShape("书单响应", node.getJSONArray("books")));
            return;
        }
        JSONArray books = root.getJSONArray("books");
        if (books != null) {
            result.setShapeHint(describeShape("列表响应", books));
            return;
        }
        result.setShapeHint("认不出形状，顶层字段=" + root.keySet());
    }

    /** 「N 条；books[0] 带/不带 bookInfo（元素键=…）」—— 一行说清形状（键名不含数据，可入日志） */
    private static String describeShape(String label, JSONArray books) {
        if (books == null) {
            return label + "：没有 books 数组";
        }
        if (books.isEmpty()) {
            return label + "：books 为空数组（0 条）";
        }
        JSONObject first = books.getJSONObject(0);
        boolean wrapped = first != null && first.getJSONObject("bookInfo") != null;
        return label + " " + books.size() + " 条；books[0] "
                + (wrapped ? "带 bookInfo（包装形态）" : "无 bookInfo（拍平形态，元素即书籍对象）")
                + "，元素键=" + (first == null ? "-" : first.keySet());
    }
}
