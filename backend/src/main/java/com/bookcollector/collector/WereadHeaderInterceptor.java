package com.bookcollector.collector;

import com.bookcollector.config.WereadProperties;
import com.bookcollector.credential.WereadCredentialStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给每个请求注入自定义 Header。
 *
 * <h3>为什么用拦截器而不是在每次调用时手动 set header</h3>
 * RestTemplate 的 message converter 会根据 body 类型自行决定 {@code Accept}，
 * 手动 set 的值可能被覆盖。拦截器在最后一步执行，能确保 Header 是我们想要的值。
 *
 * <h3>🔴 两套请求头，按「目标域名」自动切换</h3>
 * 分类 / 榜单走 Web 端接口，书单走 App 端接口，两者要求的头<b>不重叠</b>：
 * <ul>
 *   <li><b>Web 端</b>：浏览器那套（User-Agent / Accept / Accept-Language / Referer）</li>
 *   <li><b>App 端</b>：客户端那套（channelid / vid / priority / basever / v / <b>skey</b>）</li>
 * </ul>
 * 判据与构造逻辑都在 {@link WereadHeaders}（域名比对 + 两套头的定义）——
 * 本类只保留「域名从哪来」（{@link WereadCredentialStore}）这一件拦截器自己的事。
 *
 * <h3>🔴 为什么不在这里为审计再存一份头</h3>
 * 请求头快照由 {@link com.bookcollector.collector.WereadClient#fetchRaw} 直接调
 * {@link WereadHeaders} 生成（skey 掩码）。两处共用同一套构造逻辑，
 * 才能保证「审计里记的头」与「实际发出的头」永远一致。
 *
 * <h3>🔴 skey 绝不进日志</h3>
 * 它是登录态，从内存凭证取，只在 {@code headers.set} 这一处出现。
 * 下面的 debug 日志只打「哪一套头 + 方法 + URI」，不打任何 header 的值。
 *
 * <h3>🔴 2026-10-15：App 头缺项直接抛，不再静默降级</h3>
 * {@link #applyAppHeaders} 先过 {@code WereadCredentialStore#requireBooklistComplete()}：
 * 八项凭证缺任何一项都不发这个请求 —— 少 skey 的表现是服务端回「登录超时」，
 * 少 vid 的表现不可预期，都属于最难查的静默失败。校验放在这里而不是更上游，
 * 是因为**拦截器是最后一个能在请求离开进程前拦下它的点**。
 *
 * <p>⚠️ synckey <b>不在此校验内</b>（2026-10-15 晚起）：它由
 * {@code WereadClient#buildUrl} 在请求发出前按本地时间生成，永远有值。
 *
 * <h3>⚠️ 关于 Accept-Encoding</h3>
 * 原 Python 写的是 {@code gzip, deflate, br}，但 Apache HttpClient 4.5
 * <b>不支持 Brotli</b>。如果照抄带上 {@code br}，服务端可能返回 Brotli 编码，
 * 而客户端解不开 → 乱码。所以 {@link WereadHeaders} 里刻意只写
 * {@code gzip, deflate}。
 */
public class WereadHeaderInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WereadHeaderInterceptor.class);

    private final WereadProperties props;
    private final WereadCredentialStore credentials;

    public WereadHeaderInterceptor(WereadProperties props, WereadCredentialStore credentials) {
        this.props = props;
        this.credentials = credentials;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        HttpHeaders headers = request.getHeaders();

        boolean appClient = WereadHeaders.isBooklistHost(
                request.getURI().getHost(), credentials.booklistBaseUrl());
        if (appClient) {
            applyAppHeaders(headers);
        } else {
            applyWebHeaders(headers);
        }

        if (log.isDebugEnabled()) {
            log.debug("→ {} {} | 请求头={}", request.getMethod(), request.getURI(),
                    appClient ? "App 端" : "Web 端");
        }

        return execution.execute(request, body);
    }

    private void applyWebHeaders(HttpHeaders headers) {
        setAll(headers, WereadHeaders.webHeaders(credentials.referer()));
    }

    private void applyAppHeaders(HttpHeaders headers) {
        // 🔴 缺项就抛（书单配置信息不完整）—— 不带着残配置发请求。
        //    书单 URL 早在 buildUrl 阶段就校验过，这里兜底是给「URL 合法但
        //    凭证被改成不完整」的窗口期（比如保存到一半，另一个线程正在采）。
        credentials.requireBooklistComplete();
        setAll(headers, WereadHeaders.appHeaders(credentials.current()));
    }

    /** 把共享逻辑产出的 key→value 逐项 set 进真实请求头（顺序与构造一致） */
    private static void setAll(HttpHeaders headers,
                               LinkedHashMap<String, String> built) {
        for (Map.Entry<String, String> e : built.entrySet()) {
            headers.set(e.getKey(), e.getValue());
        }
    }
}
