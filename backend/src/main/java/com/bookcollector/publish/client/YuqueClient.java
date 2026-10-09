package com.bookcollector.publish.client;

import com.bookcollector.common.BizException;
import com.bookcollector.publish.PublishProperties;
import com.bookcollector.publish.dto.YuqueDoc;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语雀客户端 —— 只做发布流程需要的两件事：<b>建文档</b>、<b>挂到目录</b>。
 *
 * <h3>为什么用 Java 重写而不是调 {@code skills/yuque-publish} 的 py 脚本</h3>
 * 走 {@code ProcessBuilder} 会引入五类新问题：stdout/stderr 双流读取、Windows 下 GBK 编码、
 * Python 运行时依赖、token 透传（要么进命令行、要么进环境变量）、超时与僵尸进程。
 * 而这两件事的 HTTP 报文加起来不到 20 行，直接写更短也更可控。
 *
 * <h3>接口依据</h3>
 * 语雀官方 OpenAPI（{@code yuque_openapi_20260722.yaml}）与项目里 py 脚本
 * （{@code yuque_client.py} / {@code docs.py} / {@code toc.py}）两处对过，报文一致：
 * <ul>
 *   <li>建文档 {@code POST /api/v2/repos/{namespace}/docs}，body 必填 {@code title} / {@code body}，
 *       {@code format} 默认 {@code markdown}；返回 {@code {"data": {"id": 数字, "slug": ..., "title": ...}}}</li>
 *   <li>挂目录 {@code PUT /api/v2/repos/{namespace}/toc}，body 是 action 对象；返回 {@code {"data": [...]}}</li>
 *   <li>鉴权 Header 固定 {@code X-Auth-Token}（不是 {@code Authorization}）</li>
 * </ul>
 *
 * <h3>🔴 官方约束：创建场景不支持「同级头插」</h3>
 * {@code prependNode} 只能配 {@code action_mode=child}，而 {@code child} 必须带 {@code target_uuid}。
 * 也就是说「插到最前面」这个动作<b>必须有一个父节点</b>。见 {@link #addToToc(String, Long, boolean)}。
 *
 * <h3>🔴 敏感信息</h3>
 * {@code token} 只出现在 {@code X-Auth-Token} 请求头。绝不进日志、绝不回传前端；
 * 对外错误信息统一过 {@link #scrub(String, String)}。
 */
@Component
public class YuqueClient {

    private static final Logger log = LoggerFactory.getLogger(YuqueClient.class);

    private static final int MAX_DETAIL = 300;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_TYPE =
            new ParameterizedTypeReference<Map<String, Object>>() {
            };

    private final PublishProperties props;
    private final RestTemplate restTemplate;

    public YuqueClient(PublishProperties props) {
        this.props = props;
        this.restTemplate = build(props.getAiConnectTimeout(), props.getAiReadTimeout());
    }

    // ==================================================================
    // 对外
    // ==================================================================

    /**
     * 新建一篇文档。
     *
     * @param token 语雀 token（来自 {@code ai_yuque_token} 表）
     * @param title 文档标题（语雀侧显示的标题，通常传「《书名》作者」）
     * @param body  Markdown 正文
     * @return 含 id / slug / url 的结果；{@code id} 是挂目录时必须的
     */
    public YuqueDoc createDoc(String token, String title, String body) {
        requireToken(token);
        String namespace = namespace();

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("title", title == null ? "" : title);
        payload.put("format", "markdown");
        payload.put("body", body == null ? "" : body);

        Map<String, Object> json = exchange(HttpMethod.POST, "/repos/" + namespace + "/docs", token, payload, "创建语雀文档");

        Map<String, Object> data = asMap(json.get("data"));
        if (data == null) {
            throw new BizException("创建语雀文档失败：响应里没有 data 字段");
        }
        Long docId = asLong(data.get("id"));
        if (docId == null) {
            throw new BizException("创建语雀文档失败：响应里没有文档 id");
        }

        String slug = asString(data.get("slug"));
        YuqueDoc doc = new YuqueDoc();
        doc.setId(docId);
        doc.setSlug(slug);
        doc.setTitle(asString(data.get("title")));
        doc.setUrl(webUrl(namespace, slug));

        log.info("语雀文档已创建：docId={}, title={}", docId, doc.getTitle());
        return doc;
    }

    /**
     * 把刚建的文档挂到「书籍解读」目录节点下。
     *
     * <p>{@code prepend=true} 表示插到该父节点下的最前面（最新的在最上面）。
     *
     * <p>🔴 <b>为什么 {@code prepend} 必须配 {@code parentUuid}</b>：
     * 语雀官方明确「创建场景下不支持同级头插 {@code prependNode}」。
     * 同级插入需要 {@code action_mode=sibling} + {@code target_uuid}（已有节点），
     * 但我们是「新建 + 挂载」，此时还没有同级节点可作锚点。
     * 所以这里固化成断言：{@code prepend} 与父节点<b>同时给或同时不给</b>，
     * 避免改配置后出现「发布成功但目录里找不到」这种静默失败。
     */
    public void addToToc(String token, Long docId, boolean prepend) {
        requireToken(token);
        if (docId == null) {
            throw new BizException("语雀文档 ID 为空，无法挂载到目录");
        }
        String namespace = namespace();
        String parentUuid = nvl(props.getYuqueParentUuid());

        if (prepend && parentUuid.isEmpty()) {
            throw new BizException("prependNode 必须同时指定父节点："
                    + "语雀官方限制「创建场景不支持同级头插」，请配置 bookcollector.publish.yuque-parent-uuid");
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("action", prepend ? "prependNode" : "appendNode");
        payload.put("action_mode", parentUuid.isEmpty() ? "sibling" : "child");
        payload.put("type", "DOC");
        payload.put("doc_ids", Collections.singletonList(docId));
        if (!parentUuid.isEmpty()) {
            payload.put("target_uuid", parentUuid);
        }

        exchange(HttpMethod.PUT, "/repos/" + namespace + "/toc", token, payload, "挂载语雀文档到目录");
        log.info("语雀文档已挂入目录：docId={}, parentUuid={}, prepend={}", docId, parentUuid, prepend);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private Map<String, Object> exchange(HttpMethod method, String path, String token,
                                         Map<String, Object> payload, String what) {
        String url = baseUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 语雀的鉴权头是 X-Auth-Token，不是 Authorization
        headers.set("X-Auth-Token", token);

        ResponseEntity<Map<String, Object>> resp;
        try {
            resp = restTemplate.exchange(url, method,
                    new HttpEntity<Map<String, Object>>(payload, headers), JSON_TYPE);
        } catch (RestClientException e) {
            throw new BizException(what + "失败：" + scrub(brief(e), token));
        }

        Map<String, Object> json = resp.getBody();
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new BizException(what + "失败：HTTP " + resp.getStatusCodeValue()
                    + " " + scrub(describe(json), token));
        }
        if (json == null) {
            throw new BizException(what + "失败：语雀返回了空响应体");
        }
        return json;
    }

    private String namespace() {
        String ns = nvl(props.getYuqueNamespace());
        if (ns.isEmpty()) {
            throw new BizException("没有配置语雀知识库 namespace（bookcollector.publish.yuque-namespace）");
        }
        return ns;
    }

    private String baseUrl() {
        String base = nvl(props.getYuqueBaseUrl());
        if (base.isEmpty()) {
            base = "https://www.yuque.com/api/v2";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** 文档的可访问链接：{@code https://www.yuque.com/{namespace}/{slug}}（与 py 脚本的拼法一致） */
    private String webUrl(String namespace, String slug) {
        if (slug == null || slug.trim().isEmpty()) {
            return null;
        }
        String web = baseUrl().replace("/api/v2", "");
        while (web.endsWith("/")) {
            web = web.substring(0, web.length() - 1);
        }
        return web + "/" + namespace + "/" + slug;
    }

    private static void requireToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new BizException("没有可用的语雀 token，请先在 ai_yuque_token 表里配置");
        }
    }

    /** 语雀错误体形如 {@code {"status":400,"message":"..."}}，优先取 message */
    private static String describe(Map<String, Object> json) {
        if (json == null) {
            return "（无响应体）";
        }
        String msg = asString(json.get("message"));
        if (msg != null && !msg.trim().isEmpty()) {
            return truncate(msg.trim());
        }
        return truncate(String.valueOf(json));
    }

    private static String brief(Exception e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static String scrub(String text, String secret) {
        if (text == null) {
            return "";
        }
        if (secret != null && !secret.isEmpty()) {
            return text.replace(secret, "***");
        }
        return text;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL) + "…";
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o instanceof Number) {
            return Long.valueOf(((Number) o).longValue());
        }
        if (o instanceof String) {
            try {
                return Long.valueOf(Long.parseLong(((String) o).trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static RestTemplate build(int connectTimeout, int readTimeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeout)
                .setConnectionRequestTimeout(connectTimeout)
                .setSocketTimeout(readTimeout)
                .build();

        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(4);
        connManager.setDefaultMaxPerRoute(2);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManager(connManager)
                .disableAutomaticRetries()
                .build();

        RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
        // 复用 AiClient 里那个「不抛异常」的处理器：语雀的错误信息在响应体里
        restTemplate.setErrorHandler(new AiClient.NoThrowErrorHandler());
        return restTemplate;
    }
}
