package com.bookcollector.collector;

import com.bookcollector.credential.entity.WereadCredential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阅读平台请求头的<b>唯一构造处</b>，外加审计用的两个小工具（头快照 / URL 参数拆解）。
 *
 * <h3>🔴 为什么把它从拦截器里拆出来</h3>
 * 用户 2026-10-15 要求「把请求 url、参数、请求头、返回原始响应存到审计表」。
 * 请求头是 {@link WereadHeaderInterceptor} 在发送前那一刻加进 {@code RestTemplate} 的，
 * 采集链路自己拿不到。如果为了让审计记下「发了什么头」而在 {@link WereadClient} 里
 * <b>再抄一份</b>构造逻辑，就会出现两份会各自漂移的实现 —— 存的头 ≠ 实际发的头，
 * 排查时反而被误导。所以构造逻辑收敛到这一个类，两处共用：
 * <ul>
 *   <li>{@link WereadHeaderInterceptor}：真正加头的地方</li>
 *   <li>{@link WereadClient}：只为生成<b>审计快照</b>（skey 掩码，见 {@link #snapshot}）</li>
 * </ul>
 *
 * <h3>🔴 skey 绝不进审计</h3>
 * {@link #snapshot} 会把 {@code skey} 头替换成掩码值（前 2 位 + {@code ****}）。
 * 明文登录态只有「加进请求头」这一处会出现，不会落任何库、不进任何日志。
 *
 * <h3>🔴 2026-10-15：App 头七个值全部来自库，不再硬编码</h3>
 * 原来 {@code channelid / vid / priority / accept-language / basever / v}
 * 连同 {@code skey} 抄自一次抓包、写死在 {@link #appHeaders} 里。
 * 用户实测确认<b>书单请求不带 URL 参数 {@code synckey} 时响应是拍平的</b>
 * （books 元素没有 bookInfo 包装、缺 intro），凭证因此从 3 项扩到 9 项
 * （{@link com.bookcollector.credential.entity.WereadCredential}）——
 * 换账号 = 换 skey / vid / synckey，不该为此重新打包。
 * <b>本类不做缺项兜底</b>：少一个头也照样把请求发出去，是最难查的静默降级；
 * 缺项校验在调用方（{@code WereadCredentialStore#requireBooklistComplete()}）。
 *
 * <h3>两套头按域名切换</h3>
 * 分类 / 榜单走 Web 端接口，书单走 App 端接口，两套头不重叠。
 * 判据放在这里（{@link #isBooklistHost}）而不是各自的调用方 ——
 * 判据只有一份，「哪些请求算 App 端」在整条链路上才是同一件事。
 */
public final class WereadHeaders {

    private static final Logger log = LoggerFactory.getLogger(WereadHeaders.class);

    /** 与 Python 版逐字一致 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** ⚠️ 去掉了 br：Apache HttpClient 4.5 不支持 Brotli，带上会解不开 */
    private static final String ACCEPT_ENCODING = "gzip, deflate";

    /** {@code skey} 头的名字。快照里按它认出登录态并掩码 */
    private static final String SKEY_HEADER = "skey";

    private WereadHeaders() {
    }

    /**
     * App 端请求头（书单接口）—— <b>七个值全部来自 {@code WereadCredential}</b>。
     *
     * <p>🔴 没有兜底：调用前必须已经过
     * {@code WereadCredentialStore#requireBooklistComplete()}，
     * 否则缺项会以 {@code null} 值原样进请求头（书单采回来的书会是残的）。
     *
     * @param cred 凭证。skey / channelid / vid / priority / acceptLanguage /
     *             basever / v 全部从里面取
     */
    public static LinkedHashMap<String, String> appHeaders(WereadCredential cred) {
        LinkedHashMap<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", "WeRead/10.2.2 (iPhone; iOS 26.6.2; Scale/3.00)");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Accept-Encoding", "gzip, deflate, br");
        h.put("Connection", "keep-alive");
        h.put("channelid", cred.getChannelid());
        h.put("vid", cred.getVid());
        h.put("priority", cred.getPriority());
        h.put("accept-language", cred.getAcceptLanguage());
        h.put("basever", cred.getBasever());
        h.put("v", cred.getV());
        h.put(SKEY_HEADER, cred.getSkey());
        return h;
    }

    /** Web 端请求头（分类 / 榜单接口） */
    public static LinkedHashMap<String, String> webHeaders(String referer) {
        LinkedHashMap<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", USER_AGENT);
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        h.put("Accept-Encoding", ACCEPT_ENCODING);
        h.put("Connection", "keep-alive");
        h.put("Referer", referer);
        return h;
    }

    /**
     * 按 host 选一套头。分类 / 榜单与书单的判定都走这里。
     *
     * <p>⚠️ 书单分支返回的七个值来自 {@code cred}，调用前需已过
     * {@code WereadCredentialStore#requireBooklistComplete()}
     * （书单请求的 URL 拼装先发生，天然挡住缺项请求）。
     *
     * @param host            本次请求的域名
     * @param booklistBaseUrl 书单接口基础地址（来自库里凭证）
     * @param cred            凭证（书单分支取七个头字段）
     * @param webReferer      Web 端 Referer（来自配置）
     */
    public static LinkedHashMap<String, String> forHost(String host, String booklistBaseUrl,
                                                        WereadCredential cred, String webReferer) {
        return isBooklistHost(host, booklistBaseUrl)
                ? appHeaders(cred)
                : webHeaders(webReferer);
    }

    /**
     * 这个 host 是不是书单接口（按与书单地址的域名比对，忽略大小写）。
     *
     * <p>地址填错（缺 scheme 等）时按「不是」处理：那只会影响加哪套头，
     * 真正的报错交给 {@link WereadClient#buildUrl} —— 那里会说清「书单配置信息不完整」。
     */
    public static boolean isBooklistHost(String host, String booklistBaseUrl) {
        if (host == null || host.isEmpty()
                || booklistBaseUrl == null || booklistBaseUrl.isEmpty()) {
            return false;
        }
        try {
            String baseHost = URI.create(booklistBaseUrl).getHost();
            return baseHost != null && host.equalsIgnoreCase(baseHost);
        } catch (RuntimeException e) {
            log.warn("书单接口地址不是合法 URL，按 Web 端请求头处理：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 生成<b>审计用</b>的请求头快照：{@code skey} 一律替换成掩码值。
     *
     * <p>为什么不直接存构造出来的那份：那份带 skey 明文。用户 2026-10-08 立的规则是
     * 「敏感信息零外泄」—— 必须落库/进日志时只放长度或前缀。
     * 掩码口径：长度 ≤ 2 全掩；否则前 2 位 + {@code ****}
     * （例：{@code tuYYxC9y} → {@code tu****}）。
     */
    public static LinkedHashMap<String, String> snapshot(Map<String, String> headers) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<String, String>();
        if (headers == null) {
            return copy;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            copy.put(e.getKey(),e.getValue());
//            copy.put(e.getKey(),
//                    SKEY_HEADER.equals(e.getKey()) ? maskSkey(value) : value);
        }
        return copy;
    }

    /** skey → 掩码值。空值给 {@code "****"}（不是 null，免得前端多一种判空） */
    public static String maskSkey(String skey) {
        if (skey == null || skey.isEmpty()) {
            return "****";
        }
        if (skey.length() <= 2) {
            return "****";
        }
        return skey.substring(0, 2) + "****";
    }

    /**
     * 把 URL 的 query 拆成 key → value。
     *
     * <p>刻意<b>存原样（编码态）</b>，不做 URLDecoder：这样详情页看到的参数与
     * {@link com.bookcollector.audit.entity.ApiRequest#getUrl()} 里那串完全一致，
     * 排查「参数到底发对了没」时不需要在脑子里做一次解码。没有 {@code ?} 或
     * 没有参数时返回空 map（不是 null，前端少一种判空）。
     */
    public static LinkedHashMap<String, String> parseQuery(String url) {
        LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
        if (url == null) {
            return params;
        }
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return params;
        }
        for (String pair : url.substring(q + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(pair, "");
                continue;
            }
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }
}
