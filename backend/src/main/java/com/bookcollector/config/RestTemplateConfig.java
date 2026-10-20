package com.bookcollector.config;

import com.bookcollector.collector.WereadHeaderInterceptor;
import com.bookcollector.credential.WereadCredentialStore;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

/**
 * RestTemplate 配置。
 *
 * <p><b>⚠️ 关键约束（R25）</b>：Spring Framework 5.2（Spring Boot 2.3）的
 * {@link HttpComponentsClientHttpRequestFactory} 只支持 <b>Apache HttpClient 4.5.x</b>，
 * 不支持 HttpClient 5.x。HttpClient 5 的支持是 Spring Framework 6.0 / Spring Boot 3.0 才加入的。
 *
 * <p>几处刻意设置：
 * <ul>
 *   <li>带 CookieStore —— 原 Python 用的是 {@code requests.Session()}，会保留会话 Cookie。
 *       这里用 {@link BasicCookieStore} 对齐这个行为。</li>
 *   <li>关掉 HttpClient 自带的自动重试 —— 重试策略由我们自己的 CollectLoop 控制，
 *       两套重试叠在一起会导致实际请求次数不可预期。</li>
 *   <li><b>换掉默认的错误处理器</b> —— 否则 4xx/5xx 会先被抛成异常，
 *       状态码丢失 + 「4xx 不重试」失效，见 {@link RawResponseErrorHandler}。</li>
 *   <li><b>书单（App 端）单独一个 client</b>（{@code booklistHttpClient} + {@code booklistRestTemplate}）——
 *       与 Web 端的唯一区别是<b>不发 {@code User-Agent}</b>，见 {@link StripUserAgent}。
 *       ⚠️ 光把 {@code appHeaders} 里那行 UA 删掉是不够的：HttpClient 会自己补一个。</li>
 *   <li>连接池 —— 单机单任务其实用不满，但留着便于后续并发抓取。</li>
 * </ul>
 */
@Configuration
public class RestTemplateConfig {

    private static final Logger log = LoggerFactory.getLogger(RestTemplateConfig.class);

    private final WereadProperties props;
    private final WereadCredentialStore credentials;

    public RestTemplateConfig(WereadProperties props, WereadCredentialStore credentials) {
        this.props = props;
        this.credentials = credentials;
    }

    /** 分类 / 榜单（Web 端）用的客户端 —— <b>行为保持原样，一行不改</b> */
    @Bean
    public CloseableHttpClient wereadHttpClient() {
        return baseClientBuilder().build();
    }

    /**
     * 书单（App 端）专用客户端 —— 与 {@link #wereadHttpClient()} 的<b>唯一区别：不发 {@code User-Agent}</b>。
     *
     * <p>🔴 用户 2026-10-15 实测：书单接口<b>带 {@code User-Agent} 只回拍平形态</b>
     * （{@code books[0]} 没有 {@code bookInfo}、缺 {@code intro}），
     * <b>不带才回带 {@code bookInfo} 的完整形态</b>。
     *
     * <p>而「不在 {@code appHeaders} 里写 UA」不等于「线上不发 UA」：Apache HttpClient 4.5.13
     * 在请求没设 UA 时会自己补一个（{@code RequestUserAgent} →
     * {@code User-Agent: Apache-HttpClient/4.5.13 (Java/1.8.0_181)}，
     * 官方源码 {@code HttpClientBuilder#build()} 的默认处理器分支可证），
     * 所以必须在协议处理器<b>末尾</b>摘掉它，见 {@link StripUserAgent}。
     *
     * <p>⚠️ 单独一个 client 是刻意的：Web 端继续用 {@link #wereadHttpClient()}，
     * 两个 client 之间<b>不做任何「按域名判断」的分支</b>。
     */
    @Bean("booklistHttpClient")
    public CloseableHttpClient booklistHttpClient() {
        return baseClientBuilder()
                .addInterceptorLast(new StripUserAgent())
                .build();
    }

    /** 两个客户端共用的基础配置：超时 / 连接池 / cookie / 不自动重试 */
    private HttpClientBuilder baseClientBuilder() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(props.getConnectTimeout())
                .setConnectionRequestTimeout(props.getConnectTimeout())
                .setSocketTimeout(props.getReadTimeout())
                .build();

        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(20);
        connManager.setDefaultMaxPerRoute(10);

        log.info("初始化阅读平台 HttpClient：connectTimeout={}ms, readTimeout={}ms",
                props.getConnectTimeout(), props.getReadTimeout());

        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManager(connManager)
                .setDefaultCookieStore(new BasicCookieStore())
                // 自动重试交给我们自己的逻辑，避免两套重试叠加导致请求次数不可预期
                .disableAutomaticRetries();
    }

    @Bean("wereadRestTemplate")
    public RestTemplate wereadRestTemplate(
            @Qualifier("wereadHttpClient") CloseableHttpClient wereadHttpClient) {
        return restTemplate(wereadHttpClient);
    }

    /**
     * 书单采集（{@code /booklist/single}）用的 RestTemplate —— 底层是 {@link #booklistHttpClient()}。
     *
     * <p>⚠️ 书单发现（{@code /booklist/category}）<b>不</b>走它：那条链路原来就能用，
     * 本次不动（{@code WereadClient#fetchBooklistCategory} 仍用 {@code wereadRestTemplate}）。
     */
    @Bean("booklistRestTemplate")
    public RestTemplate booklistRestTemplate(
            @Qualifier("booklistHttpClient") CloseableHttpClient booklistHttpClient) {
        return restTemplate(booklistHttpClient);
    }

    /** 两个 RestTemplate 的公共装配：同一个头拦截器 + 同一个错误处理器 */
    private RestTemplate restTemplate(CloseableHttpClient httpClient) {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        // 不要用 SimpleClientHttpRequestFactory —— 那个是 JDK HttpURLConnection，Header 行为不可控

        RestTemplate restTemplate = new RestTemplate(factory);
        // 用拦截器统一注入自定义 Header，避免被 RestTemplate 的默认 Accept 覆盖
        restTemplate.setInterceptors(Collections.singletonList(
                new WereadHeaderInterceptor(props, credentials)));
        // ★ 关键：不让 RestTemplate 把 4xx/5xx 变成异常，见 RawResponseErrorHandler
        restTemplate.setErrorHandler(new RawResponseErrorHandler());
        return restTemplate;
    }

    /**
     * 只摘 {@code User-Agent} —— 用户 2026-10-15 实测：书单接口<b>带了 UA 就只回拍平形态</b>
     * （{@code books[0]} 没有 {@code bookInfo}、缺 {@code intro}），<b>不带才回完整形态</b>。
     *
     * <p>为什么必须在这里摘：Apache HttpClient 4.5.13 在请求没设 UA 时会自己补
     * {@code User-Agent: Apache-HttpClient/...}（官方源码 {@code HttpClientBuilder#build()}
     * 的默认处理器里有 {@code RequestUserAgent}）—— 所以「不在 {@code appHeaders} 里写 UA」
     * 并不能让线上没有 UA。挂在协议处理器<b>末尾</b>（{@code addInterceptorLast}）摘，
     * 此时头已全部构造完，摘掉的就是最终要发的那份。
     *
     * <p>⚠️ 只给 {@link #booklistHttpClient()} 挂 —— Web 端的 {@link #wereadHttpClient()}
     * 不挂，行为一行不改。这里只做「摘一个头」这一件事，不做任何域名判断。
     */
    static class StripUserAgent implements HttpRequestInterceptor {

        @Override
        public void process(HttpRequest request, HttpContext context) {
            request.removeHeaders("User-Agent");
            request.removeHeaders("Accept");
            request.removeHeaders("Accept-Encoding");
            request.removeHeaders("Connection");
        }
    }

    /**
     * 「不抛异常」的错误处理器 —— 让 HTTP 状态码原样交回给调用方。
     *
     * <h3>为什么必须换掉默认的 {@code DefaultResponseErrorHandler}</h3>
     * 默认实现在 4xx/5xx 上<b>抛异常</b>（{@code HttpClientErrorException} /
     * {@code HttpServerErrorException}）。这些异常会在
     * {@code WereadClient.fetchRaw} 的 {@code catch} 里被包成
     * {@code WereadRawResponse.error}，同时 {@code statusCode} 被写成 {@code -1}。
     * 后果有两个，而且都是静默的：
     *
     * <ol>
     *   <li><b>状态码丢了</b> —— 请求审计页把一次 500 显示成 {@code -1}，
     *       失败原因被描述成「网络异常 InternalServerError」，看起来像网线掉了，
     *       实际是服务端明确回了 500。</li>
     *   <li><b>「4xx 不重试」变成死代码</b> —— {@code WereadRawResponse.isRetryable()}
     *       的第一条判据是 {@code error != null}，而抛异常必然让 {@code error != null}，
     *       于是 404 也会被当成可重试，白白退避 1s + 3s 才失败。</li>
     * </ol>
     *
     * <p>由 {@code CollectRetryFailureIT} 钉住：5xx 恰好重试 3 次、4xx 恰好 1 次，
     * 且 {@code errorMsg} 分别是「HTTP 500」「HTTP 404」。
     *
     * <p>{@code hasError()} 恒返回 {@code false}，所以 {@code handleError()} 永远不会被调用；
     * 留着它只是为了让接口实现完整。
     */
    static class RawResponseErrorHandler implements ResponseErrorHandler {

        @Override
        public boolean hasError(ClientHttpResponse response) {
            // 「这一页算不算失败」由 CollectLoop 统一决策（它还要看 body 能不能解析出 books），
            // 这里不该替它下结论。
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) {
            // 不会走到这里（hasError 恒为 false）
        }
    }
}
