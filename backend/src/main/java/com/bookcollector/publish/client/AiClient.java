package com.bookcollector.publish.client;

import com.bookcollector.common.BizException;
import com.bookcollector.publish.PublishProperties;
import com.bookcollector.publish.dto.AiCompletion;
import com.bookcollector.publish.entity.AiModel;
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
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话客户端 —— 走 <b>OpenAI 兼容协议</b>。
 *
 * <h3>为什么叫 {@code AiClient}，而不是挂某家厂商的名字</h3>
 * 请求/响应格式用的是 OpenAI 的通用格式（{@code POST {baseUrl}/chat/completions}
 * + {@code Authorization: Bearer <key>}），DeepSeek 官方就是这个协议。
 * 所以 {@code baseUrl} / {@code model} 都从 {@code ai_model} 表读，
 * 将来换一家兼容服务只需在表里插一行，这个类不用改。
 *
 * <h3>🔴 为什么自己建一个 {@code RestTemplate}，不复用 {@code wereadRestTemplate}</h3>
 * <ol>
 *   <li>那个 bean 上挂着 {@code WereadHeaderInterceptor}，会给<b>每个</b>请求注入微信读书的
 *       自定义 Header，还会带上 {@code Cookie}。发给 AI 接口属于无谓的信息外泄。</li>
 *   <li>那个 bean 的超时是「微信读书」的读数（{@code WereadProperties}），
 *       生成一篇解读要 30~120 秒，用它必然被截断。</li>
 *   <li>它的错误处理器是 {@code RawResponseErrorHandler}（包级私有，跨包也拿不到），
 *       而这里需要读错误响应体里的 {@code message} 才能给出可读的失败原因。</li>
 * </ol>
 *
 * <h3>🔴 敏感信息</h3>
 * {@code apiKey} 只出现在 {@code Authorization} 请求头里。<b>绝不</b>写进日志、
 * 绝不回传前端。所有对外的错误信息都过一遍 {@link #scrub(String, String)}，
 * 万一某个异常把请求头带进了 message，也会被替换成 {@code ***}。
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    /** 响应体最多保留多少字符再塞进错误信息（防止把一整页 HTML 弹给用户） */
    private static final int MAX_DETAIL = 300;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_TYPE =
            new ParameterizedTypeReference<Map<String, Object>>() {
            };

    private final PublishProperties props;
    private final RestTemplate restTemplate;

    public AiClient(PublishProperties props) {
        this.props = props;
        this.restTemplate = build(props.getAiConnectTimeout(), props.getAiReadTimeout());
    }

    // ==================================================================
    // 对外
    // ==================================================================

    /**
     * 发起一次生成。
     *
     * @param model        {@code ai_model} 表里选出来的那一行（已含 apiKey）
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 正文 + token 用量
     * @throws BizException 配置缺失、网络失败、非 2xx、返回体里没有正文 —— 都从这里抛出
     */
    public AiCompletion chat(AiModel model, String systemPrompt, String userPrompt) {
        if (model == null) {
            throw new BizException("没有可用的 AI 模型，请先在 ai_model 表里配置一行 enable=true 的记录");
        }
        final String apiKey = model.getApiKey() == null ? "" : model.getApiKey().trim();
        if (apiKey.isEmpty()) {
            throw new BizException("AI 模型「" + nvl(model.getName()) + "」的 api_key 是空的，请先补上");
        }
        final String modelName = model.getModel();
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new BizException("AI 模型「" + nvl(model.getName()) + "」没有填 model 字段");
        }

        String url = chatCompletionsUrl(model.getBaseUrl());
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", modelName.trim());
        // 显式关掉流式：我们要的是一次拿到完整正文，流式反而要自己拼 SSE
        body.put("stream", Boolean.FALSE);
        body.put("max_tokens", props.getAiMaxTokens());
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        // 只打 model 和 url —— 这两样都不是敏感信息；apiKey 一个字符都不出现
        log.info("调用 AI 生成解读：model={}, url={}", modelName, url);
        long startedAt = System.currentTimeMillis();

        ResponseEntity<Map<String, Object>> resp;
        try {
            resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<Map<String, Object>>(body, headers), JSON_TYPE);
        } catch (RestClientException e) {
            throw new BizException("调用 AI 接口失败：" + scrub(brief(e), apiKey));
        }

        Map<String, Object> json = resp.getBody();
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new BizException("AI 接口返回 HTTP " + resp.getStatusCodeValue()
                    + "：" + scrub(describe(json), apiKey));
        }
        if (json == null) {
            throw new BizException("AI 接口返回了空响应体");
        }

        String content = extractContent(json);
        if (content == null || content.trim().isEmpty()) {
            throw new BizException("AI 接口没有返回正文内容，原始响应：" + scrub(describe(json), apiKey));
        }

        AiCompletion result = new AiCompletion();
        result.setContent(content.trim());
        String respModel = asString(json.get("model"));
        result.setModel(respModel == null || respModel.trim().isEmpty() ? modelName.trim() : respModel.trim());

        Map<String, Object> usage = asMap(json.get("usage"));
        if (usage != null) {
            result.setPromptTokens(asInt(usage.get("prompt_tokens")));
            result.setCompletionTokens(asInt(usage.get("completion_tokens")));
        }

        long cost = System.currentTimeMillis() - startedAt;
        String finishReason = extractFinishReason(json);
        log.info("AI 生成完成：model={}, 耗时={}ms, promptTokens={}, completionTokens={}, finishReason={}",
                result.getModel(), cost, result.getPromptTokens(), result.getCompletionTokens(), finishReason);
        if ("length".equals(finishReason)) {
            // 不抛错：截断的正文校验会拦住（少一节二级标题），失败原因比「截断」更好定位
            log.warn("AI 输出被 max_tokens 截断（finish_reason=length），正文可能不完整：model={}", result.getModel());
        }
        return result;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 拼出 {@code /chat/completions} 的完整地址。
     *
     * <p>兼容表里可能出现的三种写法：{@code https://api.deepseek.com}、
     * {@code https://api.deepseek.com/}、{@code https://api.deepseek.com/v1}。
     * 已经写成完整路径的原样返回（手滑多写一层时不会变成 {@code .../chat/completions/chat/completions}）。
     */
    static String chatCompletionsUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            base = "https://api.deepseek.com";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private static Map<String, String> message(String role, String content) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        return m;
    }

    /** 取 {@code choices[0].message.content}。任何一层缺失都返回 null，由调用方统一报错 */
    private static String extractContent(Map<String, Object> json) {
        Map<String, Object> first = firstChoice(json);
        if (first == null) {
            return null;
        }
        Map<String, Object> message = asMap(first.get("message"));
        if (message == null) {
            return null;
        }
        return asString(message.get("content"));
    }

    private static String extractFinishReason(Map<String, Object> json) {
        Map<String, Object> first = firstChoice(json);
        return first == null ? null : asString(first.get("finish_reason"));
    }

    private static Map<String, Object> firstChoice(Map<String, Object> json) {
        Object choicesObj = json.get("choices");
        if (!(choicesObj instanceof List)) {
            return null;
        }
        List<?> choices = (List<?>) choicesObj;
        if (choices.isEmpty()) {
            return null;
        }
        return asMap(choices.get(0));
    }

    /**
     * 把响应体压成一句能看的话。
     *
     * <p>OpenAI 兼容协议的错误体是 {@code {"error": {"message": "...", "type": "..."}}}，
     * 所以优先取 {@code error.message}；取不到就把整个 map 的 {@code toString} 截短。
     */
    private static String describe(Map<String, Object> json) {
        if (json == null) {
            return "（无响应体）";
        }
        Map<String, Object> error = asMap(json.get("error"));
        if (error != null) {
            String msg = asString(error.get("message"));
            if (msg != null && !msg.trim().isEmpty()) {
                return truncate(msg.trim());
            }
        }
        String msg = asString(json.get("message"));
        if (msg != null && !msg.trim().isEmpty()) {
            return truncate(msg.trim());
        }
        return truncate(String.valueOf(json));
    }

    /** 只保留异常的类型 + message；不打印堆栈（生成失败是可预期的业务结果，不是崩溃） */
    private static String brief(Exception e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    /** 把不该外泄的字符串从文本里抹掉 —— 纵深防御，正常路径下不会命中 */
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

    private static Integer asInt(Object o) {
        if (o instanceof Number) {
            return Integer.valueOf(((Number) o).intValue());
        }
        if (o instanceof String) {
            try {
                return Integer.valueOf(Integer.parseInt(((String) o).trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // HTTP 装配
    // ------------------------------------------------------------------

    private static RestTemplate build(int connectTimeout, int readTimeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeout)
                .setConnectionRequestTimeout(connectTimeout)
                // ★ 必须显式设 socketTimeout：RestTemplate 默认无限等，
                //   对端不回时生成线程会永远挂着，页面永远显示「生成中」
                .setSocketTimeout(readTimeout)
                .build();

        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        // 一次只跑一本书，2 条连接绰绰有余
        connManager.setMaxTotal(4);
        connManager.setDefaultMaxPerRoute(2);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManager(connManager)
                // 不做 HTTP 层重试：用户明确「失败不重试，只记原因」
                .disableAutomaticRetries()
                .build();

        RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
        // 4xx/5xx 不要抛异常 —— 要把响应体里的 message 读出来当失败原因
        restTemplate.setErrorHandler(new NoThrowErrorHandler());
        return restTemplate;
    }

    /**
     * 与 {@code RestTemplateConfig.RawResponseErrorHandler} 同样的思路：
     * 让 HTTP 状态码原样交回调用方，由调用方决定怎么报错。
     */
    static class NoThrowErrorHandler implements ResponseErrorHandler {

        @Override
        public boolean hasError(ClientHttpResponse response) {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) {
            // 不会走到这里
        }
    }
}
