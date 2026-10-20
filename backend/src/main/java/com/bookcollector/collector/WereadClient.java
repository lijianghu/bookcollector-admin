package com.bookcollector.collector;

import com.bookcollector.collector.dto.WereadRawResponse;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.config.WereadProperties;
import com.bookcollector.credential.WereadCredentialStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 阅读平台列表接口客户端。
 *
 * <p>只负责「发请求 + 拿到原始 JSON 字符串」，不做解析（解析是 {@code BookParser} 的事）。
 *
 * <h3>🔴 URL 从哪来</h3>
 * 基础地址<b>不在代码里</b>，也不在这里拼接常量：两个地址都由
 * {@code WereadCredentialStore} 从库里读进内存，这里只负责
 * 「按 {@link TargetType} 选哪个地址、拼哪套路径与参数」。
 * 选哪一套由 {@link TargetType#getEndpoint()} 决定 —— 不在这里写
 * {@code type == RANKING} 那种二元判断，否则新增类型会静默走错分支
 * （拼出一个格式合法、但永远采不到数据的 URL）。
 *
 * <h3>⚠️ 一个容易踩的坑</h3>
 * 这里刻意用 {@code byte[].class} 而不是 {@code String.class}。
 * RestTemplate 用 {@code StringHttpMessageConverter} 处理 String 响应时，如果服务端
 * 返回的 {@code Content-Type} 里没带 charset，会退回 <b>ISO-8859-1</b> 解码 → 中文全乱码。
 * 拿 byte[] 自己按 UTF-8 构造 String，可以彻底绕开这个问题。
 */
@Component
public class WereadClient {

    private static final Logger log = LoggerFactory.getLogger(WereadClient.class);

    /** 书单接口的路径。基础地址来自库里，这里只拼这一段 */
    private static final String BOOKLIST_PATH = "/booklist/single";

    /**
     * 书单<b>发现</b>接口的路径。
     *
     * <p>与 {@link #BOOKLIST_PATH} 同一个 host（都由页面上的 sUrl 提供），
     * 所以 {@code WereadHeaderInterceptor} 会自动套 App 端请求头（含 skey）——
     * 这里不需要为它单独准备头。
     */
    private static final String BOOKLIST_CATEGORY_PATH = "/booklist/category";

    /** 书单发现接口每页取几条。抓包请求里就是 10，接口没提供「一次全取」的方式 */
    private static final int BOOKLIST_CATEGORY_PAGE_SIZE = 10;

    /**
     * 单本书详情接口的路径（{@code /book/info?bookId=x}）。
     *
     * <p>与 {@link #BOOKLIST_PATH} 同一个 host（都由页面上的 sUrl 提供），
     * 所以 {@code WereadHeaderInterceptor} 会自动套 App 端请求头（含 skey）——
     * 用户口径「请求头同 single 接口」由此天然成立，这里不需要单独准备头。
     *
     * <p>用途：书单链路（拍平形态）不带 {@code intro} / {@code category}，
     * 需要事后逐本补齐（2026-10-16「完善书籍信息」）。
     */
    private static final String BOOK_INFO_PATH = "/book/info";

    /**
     * {@code tag} 参数 = 「最多收藏」。
     *
     * <p>🔴 这个参数不是可有可无的筛选：<b>带上它接口才按收藏人数降序返回</b>。
     * 「收藏数不达标即停止翻页」这条优化完全建立在「有序」之上 ——
     * 一旦漏了它，返回顺序随机，提前终止会<b>漏掉中间那些达标的大书单</b>，
     * 而表现只是「同步到的书单变少了」，没有任何报错。
     *
     * <h3>🔴 2026-10-14 修正：原来硬编码的百分号串少了一个字</h3>
     * 这里原先写的是常量 {@code "%E6%9C%80%E6%94%B6%E8%97%8F"}，它解码出来是
     * <b>「最收藏」</b>（3 个字）—— 少了「多」（{@code %E5%A4%9A}），根本不是个词。
     * 服务端收到<b>认不出的 tag</b> 时返回的是 <b>空 {@code datas}</b>（不是报错），
     * 于是界面显示「同步完成、扫到 0 张书单」，看起来像「这个分类本来就没有书单」——
     * 而实际上「历史」有 4264 张（首页接口的 {@code categories[].totalCount} 实测）。
     *
     * <p>所以现在改成<b>写中文原字 + 运行时编码</b>：写错了是一个一眼能看出的错别字，
     * 而不是一个少一个字节、谁都不会注意的百分号串。
     * 编码后必须是 {@code %E6%9C%80%E5%A4%9A%E6%94%B6%E8%97%8F}（4 段）。
     */
    private static final String BOOKLIST_CATEGORY_TAG = encodeQueryValue("最多收藏");

    private final RestTemplate restTemplate;
    /** 书单（App 端）专用 —— 底层 client 不发 {@code User-Agent}，见 {@code RestTemplateConfig} */
    private final RestTemplate booklistRestTemplate;
    private final WereadCredentialStore credentials;
    private final WereadProperties props;

    public WereadClient(@Qualifier("wereadRestTemplate") RestTemplate restTemplate,
                        @Qualifier("booklistRestTemplate") RestTemplate booklistRestTemplate,
                        WereadCredentialStore credentials,
                        WereadProperties props) {
        this.restTemplate = restTemplate;
        this.booklistRestTemplate = booklistRestTemplate;
        this.credentials = credentials;
        this.props = props;
    }

    /**
     * 按目标类型选 RestTemplate：书单（App 端）走不发 {@code User-Agent} 的那个，
     * 其余（分类 / 榜单，Web 端）走原来的 —— 只有这一处分路，不做别的判断。
     */
    private RestTemplate restTemplateFor(TargetType type) {
        return type.getEndpoint() == TargetType.Endpoint.BOOKLIST
                ? booklistRestTemplate
                : restTemplate;
    }

    /**
     * 拼请求 URL。
     *
     * <p>两种形态：
     * <ul>
     *   <li>分类 / 榜单：{@code {列表地址}/{targetId}?maxIndex={n}}，榜单多一个
     *       {@code &rank=1}（由 {@link TargetType#getExtraQuery()} 给出）</li>
     *   <li>书单：{@code {书单地址}/booklist/single?booklistId={targetId}&synckey={n}}
     *       —— <b>没有分页参数</b>，一次返回整张书单（接口上限 500 本）</li>
     * </ul>
     *
     * <h3>🔴 synckey = 请求时刻的本地时间，每次现取（2026-10-15 晚用户拍板）</h3>
     * 用户原话：「synckey 不是从 root.synckey 取，而是后端 new Date() 的秒值」。
     * 用户本地实测：书单 URL <b>不带 synckey</b> 时响应里的 {@code books} 元素是
     * <b>拍平</b>的（没有 {@code bookInfo} 包装、连 {@code intro} 都缺）；
     * 带上才是完整形状。所以每个书单请求发出前都现取一个本地时钟秒值带上
     * （{@code WereadCredentialStore#refreshSynckeyNow}）—— 不再等响应回读，
     * 第一个请求就带，没有「先缺一次」的窗口。
     *
     * @throws IllegalStateException 书单凭证八项缺任一项（sUrl / skey /
     *         channelid / vid / priority / acceptLanguage / basever / v）
     */
    public String buildUrl(TargetType type, String targetId, int maxIndex) {
        if (type.getEndpoint() == TargetType.Endpoint.BOOKLIST) {
            // 🔴 先校验再拼 URL：缺项就抛「书单配置信息不完整」，不拼半截 URL。
            //    「没有兜底」= 缺任一字段都不允许把请求发出去。
            credentials.requireBooklistComplete();
            String base = credentials.booklistBaseUrl();
            // ★ synckey 现取现用：直接用返回值拼 URL，不回头读 current() ——
            //   并发任务随时可能又刷新一次，读共享内存会串到别人那一刻的值。
            String synckey = credentials.refreshSynckeyNow();
            StringBuilder sb = new StringBuilder(base);
            sb.append(BOOKLIST_PATH).append("?booklistId=").append(targetId);
            sb.append("&synckey=").append(synckey);
            return sb.toString();
        }

        StringBuilder sb = new StringBuilder(credentials.listBaseUrl());
        sb.append('/').append(targetId).append("?maxIndex=").append(maxIndex);
        String extra = type.getExtraQuery();
        if (extra != null && !extra.isEmpty()) {
            sb.append('&').append(extra);
        }
        return sb.toString();
    }

    /**
     * 拉一页原始 JSON。
     *
     * <p>🔴 2026-10-15：在原有字段之外多带两份「发现场」——{@code queryParams}
     * （URL 参数拆解）与 {@code requestHeaders}（请求头快照，skey 掩码）。
     * 真实请求仍由 {@link WereadHeaderInterceptor} 加头；这里只是用<b>同一套</b>
     * {@link WereadHeaders} 逻辑构造一份快照，供审计详情展示
     * （用户需求：点重试后能自己判断是「数据访问问题」还是「解析问题」）。
     *
     * @param type     目标类型
     * @param targetId 分类 ID / 榜单类型 / 书单 ID
     * @param maxIndex 分页游标（0 表示第一页）。书单不分页，这个参数会被忽略
     * @return 原始响应（含 URL、状态码、耗时、body），不抛业务异常，失败由调用方决定怎么处理
     */
    public WereadRawResponse fetchRaw(TargetType type, String targetId, int maxIndex) {
        String url;
        long start = System.currentTimeMillis();
        try {
            url = buildUrl(type, targetId, maxIndex);
        } catch (RuntimeException e) {
            // 拼 URL 阶段就失败（凭证没配）—— 也要包成响应对象，
            // 让上层走同一条「记审计 + 记失败原因」的路径，而不是抛穿整个采集循环。
            // ⚠️ 这时连 URL 都没有，queryParams / requestHeaders 留空（null）。
            log.warn("无法拼接请求地址（{} / {}）：{}", type, targetId, e.getMessage());
            return WereadRawResponse.builder()
                    .url(null)
                    .statusCode(-1)
                    .costMs(System.currentTimeMillis() - start)
                    .body(null)
                    .error(e)
                    .build();
        }

        // ---- 审计快照：参数 + 请求头（skey 掩码）----
        // 域名解析失败时 host=null → WereadHeaders 按 Web 端兜底；
        // 紧接着 restTemplate 也会因同一个 URL 失败，快照只是尽力而为。
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (RuntimeException e) {
            host = null;
        }
        final Map<String, String> auditParams = WereadHeaders.parseQuery(url);
        final Map<String, String> auditHeaders = WereadHeaders.snapshot(
                WereadHeaders.forHost(host, credentials.booklistBaseUrl(),
                        credentials.current(), credentials.referer()));

        try {
            ResponseEntity<byte[]> resp = restTemplateFor(type).getForEntity(url, byte[].class);
            long cost = System.currentTimeMillis() - start;
            byte[] bodyBytes = resp.getBody();
            // 手工按 UTF-8 解码，见类注释
            String body = bodyBytes == null ? "" : new String(bodyBytes, StandardCharsets.UTF_8);

            if (log.isDebugEnabled()) {
                log.debug("← {} {} | {}ms | {} bytes", resp.getStatusCodeValue(), url, cost,
                        bodyBytes == null ? 0 : bodyBytes.length);
            }

            return WereadRawResponse.builder()
                    .url(url)
                    .statusCode(resp.getStatusCodeValue())
                    .costMs(cost)
                    .body(body)
                    .queryParams(auditParams)
                    .requestHeaders(auditHeaders)
                    .build();

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            // ⚠️ 这里只打「方法 + URL + 异常消息」，不打 body / headers ——
            //    完整现场由 api_requests 审计承载（含掩码后的请求头与原始响应）。
            log.warn("请求失败 {} {} | {}ms | {}", type, targetId, cost, e.getMessage());
            return WereadRawResponse.builder()
                    .url(url)
                    .statusCode(-1)
                    .costMs(cost)
                    .body(null)
                    .error(e)
                    .queryParams(auditParams)
                    .requestHeaders(auditHeaders)
                    .build();
        }
    }

    // ==================================================================
    // 书单发现（链路 A）
    // ==================================================================

    /**
     * 拉一页「某分类下的书单」。
     *
     * <p>这是<b>第三条</b> URL 形态，所以没有塞进 {@link #buildUrl} ——
     * 那个方法的语义是「按采集目标拼 URL」，而书单发现<b>不是采集目标</b>
     * （它没有 {@code TargetType}，也不进任务体系）。
     *
     * @param categoryId 平台分类 ID，形如 {@code "200000"} 或 {@code "topic"}
     * @param maxIdx     偏移量。第一页传 0，之后每次 + {@value #BOOKLIST_CATEGORY_PAGE_SIZE}
     * @return 原始响应，不抛业务异常
     * @throws IllegalStateException 书单接口地址（sUrl）还没在页面上保存过
     */
    public WereadRawResponse fetchBooklistCategory(String categoryId, int maxIdx) {
        String url;
        long start = System.currentTimeMillis();
        try {
            url = buildBooklistCategoryUrl(categoryId, maxIdx);
        } catch (RuntimeException e) {
            log.warn("无法拼接书单发现请求地址（categoryId={}）：{}", categoryId, e.getMessage());
            return WereadRawResponse.builder()
                    .url(null)
                    .statusCode(-1)
                    .costMs(System.currentTimeMillis() - start)
                    .body(null)
                    .error(e)
                    .build();
        }

        try {
            // 🔴 刻意用 URI 而不是 String：RestTemplate 的 String 重载会把入参当
            // 「URI 模板」再编码一次，已编码的 tag（%E6%9C%80…）会被二次编码成
            // %25E6%9C%80…，服务端解出来就是字面量「%E6%9C%80…」而不是「最多收藏」。
            // 传 URI 对象则原样发出，不做任何编码。
            URI uri = URI.create(url);
            ResponseEntity<byte[]> resp = restTemplate.getForEntity(uri, byte[].class);
            long cost = System.currentTimeMillis() - start;
            byte[] bodyBytes = resp.getBody();
            String body = bodyBytes == null ? "" : new String(bodyBytes, StandardCharsets.UTF_8);

            if (log.isDebugEnabled()) {
                log.debug("← {} {} | {}ms | {} bytes", resp.getStatusCodeValue(), url, cost,
                        bodyBytes == null ? 0 : bodyBytes.length);
            }

            return WereadRawResponse.builder()
                    .url(url)
                    .statusCode(resp.getStatusCodeValue())
                    .costMs(cost)
                    .body(body)
                    .build();

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("书单发现请求失败 categoryId={} maxIdx={} | {}ms | {}",
                    categoryId, maxIdx, cost, e.getMessage());
            return WereadRawResponse.builder()
                    .url(url)
                    .statusCode(-1)
                    .costMs(cost)
                    .body(null)
                    .error(e)
                    .build();
        }
    }

    /**
     * 拼书单发现接口的 URL。
     *
     * <pre>
     * {sUrl}/booklist/category?categoryId={cid}&amp;count=10&amp;maxIdx={n}&amp;rnVersion=1&amp;tag=%E6%9C%80%E5%A4%9A%E6%94%B6%E8%97%8F
     * </pre>
     * 末尾那段 tag 就是「最多收藏」的 UTF-8 百分号编码（<b>4 段</b>）。
     * ⚠️ 它由 {@link #encodeQueryValue(String)} 在运行期生成，<b>不要</b>改回手写常量 ——
     * 手写的版本曾经少了一个「多」（{@code %E5%A4%9A}），变成「最收藏」，
     * 服务端认不出这个 tag 就返回空 {@code datas}，而界面上只显示「扫到 0 张」。
     *
     * @throws IllegalStateException 书单凭证八项缺任一项
     */
    public String buildBooklistCategoryUrl(String categoryId, int maxIdx) {
        // 与 buildUrl 同一条校验：发现链路也走 App 端头（同 host、同拦截器），
        // 缺项时不发请求，报同一句「书单配置信息不完整」。
        credentials.requireBooklistComplete();
        String base = credentials.booklistBaseUrl();
        // ⚠️ 这里不再单独判 sUrl 空 —— requireBooklistComplete 已经覆盖，
        //    两条报错路径并存只会让同一种缺项出现两种措辞。
        StringBuilder sb = new StringBuilder(base);
        sb.append(BOOKLIST_CATEGORY_PATH)
                .append("?categoryId=").append(categoryId)
                .append("&count=").append(BOOKLIST_CATEGORY_PAGE_SIZE)
                .append("&maxIdx=").append(maxIdx)
                .append("&rnVersion=1")
                .append("&tag=").append(BOOKLIST_CATEGORY_TAG);
        return sb.toString();
    }

    /** 书单发现接口每页条数。提前终止/翻页判断要用它 */
    public static int booklistCategoryPageSize() {
        return BOOKLIST_CATEGORY_PAGE_SIZE;
    }

    // ==================================================================
    // 付费精选（2026-10-20）
    // ==================================================================

    /** 付费精选的分类 ID（固定） */
    private static final String PAID_CATEGORY_ID = "9000100";

    /** 付费精选每页条数 */
    private static final int PAID_PAGE_SIZE = 10;

    /** 付费精选接口路径 */
    private static final String PAID_CATEGORY_DATA_PATH = "/category/data";

    /**
     * 拉一页「付费精选」（{@code /category/data}）。
     *
     * <p>与书单<b>同 host</b>、同 App 头，底层用 {@code booklistRestTemplate}
     * （不发默认 UA），与书单链路完全一致。
     *
     * @param filterCategory 平台分类 code（取自 {@code taxonomy_config}）
     * @param maxIdx         偏移量。第一页传 0，之后每次 + {@value #PAID_PAGE_SIZE}
     * @return 原始响应，不抛业务异常
     * @throws IllegalStateException 书单凭证不完整
     */
    public WereadRawResponse fetchCategoryData(String filterCategory, int maxIdx) {
        String url;
        long start = System.currentTimeMillis();
        try {
            url = buildCategoryDataUrl(filterCategory, maxIdx);
        } catch (RuntimeException e) {
            log.warn("无法拼接付费精选请求地址（filterCategory={}）：{}", filterCategory, e.getMessage());
            return WereadRawResponse.builder()
                    .url(null)
                    .statusCode(-1)
                    .costMs(System.currentTimeMillis() - start)
                    .body(null)
                    .error(e)
                    .build();
        }

        try {
            URI uri = URI.create(url);
            ResponseEntity<byte[]> resp = booklistRestTemplate.getForEntity(uri, byte[].class);
            long cost = System.currentTimeMillis() - start;
            byte[] bodyBytes = resp.getBody();
            String body = bodyBytes == null ? "" : new String(bodyBytes, StandardCharsets.UTF_8);

            if (log.isDebugEnabled()) {
                log.debug("← {} {} | {}ms | {} bytes", resp.getStatusCodeValue(), url, cost,
                        bodyBytes == null ? 0 : bodyBytes.length);
            }

            return WereadRawResponse.builder()
                    .url(url)
                    .statusCode(resp.getStatusCodeValue())
                    .costMs(cost)
                    .body(body)
                    .build();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("付费精选请求失败 filterCategory={} maxIdx={} | {}ms | {}",
                    filterCategory, maxIdx, cost, e.getMessage());
            return WereadRawResponse.builder()
                    .url(url)
                    .statusCode(-1)
                    .costMs(cost)
                    .body(null)
                    .error(e)
                    .build();
        }
    }

    /**
     * 拼付费精选 URL：
     * <pre>
     * {sUrl}/category/data?categoryId=9000100&amp;count=10&amp;filterCategory={code}
     *   &amp;finish=0&amp;maxIdx={n}&amp;sort=2&amp;synckey={当前秒值}&amp;wordcount_begin=0&amp;wordcount_end=0
     * </pre>
     * {@code synckey} 与书单链路同口径：每次请求前由
     * {@link WereadCredentialStore#refreshSynckeyNow()} 按本地时间生成。
     */
    public String buildCategoryDataUrl(String filterCategory, int maxIdx) {
        credentials.requireBooklistComplete();
        String base = credentials.booklistBaseUrl();
        StringBuilder sb = new StringBuilder(base);
        sb.append(PAID_CATEGORY_DATA_PATH)
                .append("?categoryId=").append(PAID_CATEGORY_ID)
                .append("&count=").append(PAID_PAGE_SIZE)
                .append("&filterCategory=").append(filterCategory)
                .append("&finish=0")
                .append("&maxIdx=").append(maxIdx)
                .append("&sort=2")
                .append("&synckey=").append(credentials.refreshSynckeyNow())
                .append("&wordcount_begin=0")
                .append("&wordcount_end=0");
        return sb.toString();
    }

    /** 付费精选每页条数（翻页判断用） */
    public static int paidPageSize() {
        return PAID_PAGE_SIZE;
    }

    // ==================================================================
    // 单本书详情（补齐 intro / category 用）
    // ==================================================================

    /**
     * 拉一本「书详情」。
     *
     * <p>这是<b>第四条</b> URL 形态，所以同样不塞进 {@link #buildUrl} ——
     * 那个方法的语义是「按采集目标拼 URL」，而书详情<b>不是采集目标</b>。
     *
     * <p>🔴 与书单<b>同 host</b> → 请求头自动是 App 端那套（含 skey / vid 等七项）。
     * 底层用 {@code booklistRestTemplate}（不发默认 UA），与书单链路完全一致。
     *
     * @param bookId 图书 ID，如 {@code "32489042"}
     * @return 原始响应，不抛业务异常
     * @throws IllegalStateException 书单凭证八项缺任一项
     */
    public WereadRawResponse fetchBookInfo(String bookId) {
        String url;
        long start = System.currentTimeMillis();
        try {
            url = buildBookInfoUrl(bookId);
        } catch (RuntimeException e) {
            log.warn("无法拼接书详情请求地址（bookId={}）：{}", bookId, e.getMessage());
            return WereadRawResponse.builder()
                    .url(null)
                    .statusCode(-1)
                    .costMs(System.currentTimeMillis() - start)
                    .body(null)
                    .error(e)
                    .build();
        }

        try {
            // 用 URI 而不是 String：避免 RestTemplate 把入参当模板再编码一次（同书单发现接口）
            URI uri = URI.create(url);
            ResponseEntity<byte[]> resp = booklistRestTemplate.getForEntity(uri, byte[].class);
            long cost = System.currentTimeMillis() - start;
            byte[] bodyBytes = resp.getBody();
            String body = bodyBytes == null ? "" : new String(bodyBytes, StandardCharsets.UTF_8);

            if (log.isDebugEnabled()) {
                log.debug("← {} {} | {}ms | {} bytes", resp.getStatusCodeValue(), url, cost,
                        bodyBytes == null ? 0 : bodyBytes.length);
            }

            return WereadRawResponse.builder()
                    .url(url)
                    .statusCode(resp.getStatusCodeValue())
                    .costMs(cost)
                    .body(body)
                    .build();

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("书详情请求失败 bookId={} | {}ms | {}", bookId, cost, e.getMessage());
            return WereadRawResponse.builder()
                    .url(url)
                    .statusCode(-1)
                    .costMs(cost)
                    .body(null)
                    .error(e)
                    .build();
        }
    }

    /**
     * 拼书详情接口的 URL。
     *
     * <pre>{sUrl}/book/info?bookId={bookId}</pre>
     *
     * <p>与书单链路同一条凭证校验：缺项时不发请求，报同一句「书单配置信息不完整」。
     *
     * @throws IllegalStateException 书单凭证八项缺任一项
     */
    public String buildBookInfoUrl(String bookId) {
        credentials.requireBooklistComplete();
        StringBuilder sb = new StringBuilder(credentials.booklistBaseUrl());
        sb.append(BOOK_INFO_PATH).append("?bookId=").append(bookId);
        return sb.toString();
    }

    /**
     * 查询参数值 → 百分号编码（UTF-8）。
     *
     * <p>用在<b>静态常量</b>上，所以编码失败会直接抛异常把应用拦在启动之外 ——
     * 而不是留一个 null 到运行期才炸（UTF-8 一定存在，这只是防御）。
     *
     * <p>为什么要这个包装：见 {@link #BOOKLIST_CATEGORY_TAG} 的说明 ——
     * 手写的百分号串少一个字节不会有任何提示，而中文原字写错了是错别字。
     */
    private static String encodeQueryValue(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不受支持，无法编码查询参数：" + value, e);
        }
    }
}
