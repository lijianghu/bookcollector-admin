package com.bookcollector.credential;

import com.bookcollector.credential.entity.WereadCredential;
import com.bookcollector.credential.repository.WereadCredentialRepository;
import com.bookcollector.credential.req.WereadCredentialRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Date;

/**
 * 接口凭证的<b>内存副本</b> + 持久化入口。
 *
 * <h3>为什么是「内存副本」而不是每次去查库</h3>
 * 采集循环每页都要拼一次 URL（一次任务可能几百页），每页都查库是没必要的开销。
 * 凭证分两种更新频率：**表单八项是「人手点一下」**，保存即换内存；
 * **synckey 每次书单请求发出前按本地时钟刷新**（{@link #refreshSynckeyNow()}），
 * 同一秒内不重复写库。保存时<b>先落库、再换内存</b>，所以内存里永远是一份已经持久化成功的值。
 *
 * <h3>🔴 三个 accessor 的语义差别</h3>
 * <ul>
 *   <li>{@link #listBaseUrl()} —— 分类 / 榜单用。库里没配时<b>回落到 yml 的配置</b>，
 *       保证已有的采集链路不会因为「凭证还没保存过」而失效</li>
 *   <li>{@link #booklistBaseUrl()} —— 书单用。<b>没有回落</b>：没配就是没配，
 *       由 {@link #requireBooklistComplete()} 报「书单配置信息不完整」，
 *       而不是拼出一个半截 URL 去请求</li>
 *   <li>{@link #skey()} —— 登录态。未配置时返回空串。绝不进日志</li>
 * </ul>
 *
 * <h3>⚠️ 启动时读不到库不算致命</h3>
 * Mongo 还没起来 / 集合不存在都会让 {@code findById} 抛异常。这里<b>吞掉并降级为空值</b>：
 * 应用照样起来，页面照样能打开、能把凭证填进去；只有真正要用书单接口时才会失败，
 * 而那时给的是「书单配置信息不完整（缺 X）」这种能自己解决的提示，不是启动失败。
 */
@Component
public class WereadCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(WereadCredentialStore.class);

    /** 单文档的固定主键 */
    public static final String DOC_ID = "weread";

    private final WereadCredentialRepository repository;
    /** 只用来做单字段 $set（synckey 滚动）—— 全量 save 走 repository，见 {@link #save} */
    private final MongoTemplate mongoTemplate;

    /**
     * 内存副本。
     *
     * <p>{@code volatile}：保存线程写、采集线程读，两个线程之间要可见性。
     * 这里不用锁 —— 换的是一个<b>完整对象引用</b>（不可变替换），
     * 读到的要么是旧值要么是新值，不存在「读到一半」的中间态。
     */
    private volatile WereadCredential cached;

    public WereadCredentialStore(WereadCredentialRepository repository,
                                 MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    /** 启动时加载一次。见类注释「启动时读不到库不算致命」 */
    @PostConstruct
    public void load() {
        WereadCredential doc = null;
        try {
            doc = repository.findById(DOC_ID).orElse(null);
        } catch (Exception e) {
            log.error("读取接口凭证失败，本次以空值启动（页面上保存一次即可恢复）：{}", e.toString());
        }
        this.cached = doc == null ? newDocument() : doc;
        // 🔴 只打「配了几项 / 缺哪几项」，不打任何值 —— skey / synckey 敏感，
        //    channelid 等虽不敏感，混在一行里迟早有人顺手把值也带上。
        log.info("已加载接口凭证：{}", completeness());
    }

    /** 当前内存副本（只读用；不要直接改它，改请走 {@link #save(WereadCredentialRequest)}） */
    public WereadCredential current() {
        return cached;
    }

    /**
     * 🔴 书单链路的前置校验：凭证八项缺一不可。
     *
     * <p>用户 2026-10-15 原话：「缺少任意一个，就提示书单配置信息不完整」。
     * 「没有兜底」是说缺项时<b>不能带着残配置继续发请求</b> ——
     * 少 skey 的表现是服务端回「登录超时」，少 vid 的表现不可预期，
     * 都是最难查的静默降级。
     *
     * <p>⚠️ <b>synckey 不在校验范围内</b>（2026-10-15 晚起）：它由
     * {@link #refreshSynckeyNow()} 在每次书单请求发出前按本地时间生成，
     * 永远有值，没得缺。表单校验只管人要填的八项。
     *
     * <p>在 {@link com.bookcollector.collector.WereadClient#buildUrl} 与
     * {@link com.bookcollector.collector.WereadHeaderInterceptor} 两处调用 ——
     * URL 拼装与加头是书单请求逃不掉的两个前置点。
     *
     * @throws IllegalStateException 缺项时抛出，消息 = {@link #describeMissing}
     */
    public void requireBooklistComplete() {
        String missing = describeMissing(cached);
        if (missing != null) {
            throw new IllegalStateException(missing);
        }
    }

    /**
     * 缺项描述句；全配完返回 {@code null}。
     *
     * <p>保存接口（弹给人看的 {@code BizException.warn}）与采集链路（抛给人看的
     * {@link IllegalStateException}）<b>共用这一句</b> ——
     * 同 {@code WereadRawResponse#describeFailure()} 的口径：两处措辞一致，
     * 用户在界面上看到的和日志里才是同一句话。
     */
    public static String describeMissing(WereadCredential c) {
        String tail = describeMissingTail(c);
        return tail.isEmpty() ? null : INCOMPLETE_PREFIX + "，缺：" + tail;
    }

    /**
     * 🔴 刷新 synckey = <b>后端当前时刻</b>的 Unix 秒。
     *
     * <p>用户 2026-10-15 晚原话：「synckey 不是从 root.synckey 取，而是后端
     * new Date() 的秒值」。为什么从「响应回读」改成本地时间：带 synckey
     * 书单响应才给完整形状（{@code bookInfo} 包装，用户本地实测确认）。
     * 响应值要等「请求成功且解析完」才拿得到，第一次请求永远带不上；
     * 而服务端要的只是「一个足够新的同步时间点」，本地时钟完全满足，
     * 且每个请求发出前都能现取，不存在「先缺一次」的窗口。
     *
     * <p>调用时机：{@code WereadClient#buildUrl} 拼书单 URL 时，即<b>请求发出前</b>；
     * 返回值就是本次拼进 URL 的值。
     * 🔴 调用方必须用返回值，不能回头读 {@link #current()} ——
     * 并发任务随时可能又刷新过一次，读共享内存会串。
     *
     * <h3>两个刻意的决定</h3>
     * <ol>
     *   <li><b>同一秒内不重复写库</b>：书单任务只有一页，但重试 / 连着跑两个
     *       书单任务可能落在同一秒，值没变就不写。</li>
     *   <li><b>用单字段 {@code $set} 而不是 {@code repository.save}</b>：
     *       表单保存是全量替换，这里只改一个字段 —— 两条写路径并发时
     *       不能互相覆盖别的字段。</li>
     * </ol>
     *
     * <p>日志只打「已刷新」不打值：synckey 属于用户划线「注释日志不要打印」的凭证项。
     *
     * @return 本次使用的 synckey（字符串形态，直接拼进 URL）
     */
    public String refreshSynckeyNow() {
        String v = String.valueOf(System.currentTimeMillis() / 1000L);
        if (v.equals(cached.getSynckey())) {
            // 同一秒内的重复请求：库里与内存里的值就是刚刚写进去的那个，直接用
            return v;
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(DOC_ID)),
                Update.update("synckey", v),
                WereadCredential.class);
        // 内存换引用（不可变替换），不是原地改 —— 采集线程随时在并发读
        this.cached = cached.toBuilder().synckey(v).build();
        log.info("synckey 已刷新为请求时刻的本地时间");
        return v;
    }

    /** 分类 / 榜单接口的基础地址（末尾斜杠已去掉）。只从库里读，未配置直接报错 */
    public String listBaseUrl() {
        String url = cached.getListUrl();
        if (isBlank(url)) {
            throw new IllegalStateException("列表接口地址未配置（weread_credential.listUrl）");
        }
        return stripTrailingSlash(url);
    }

    /**
     * Referer：从列表接口地址推导 origin（{@code scheme://host/}）。
     *
     * <p>不硬编码域名；{@link #listBaseUrl()} 未配置时会抛错。
     */
    public String referer() {
        String url = listBaseUrl();
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return "";
            }
            return scheme + "://" + host + "/";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 书单接口的基础地址（末尾斜杠已去掉）。未配置时返回空串 */
    public String booklistBaseUrl() {
        return stripTrailingSlash(cached.getBooklistUrl());
    }

    /** 登录态。未配置时返回空串 */
    public String skey() {
        String v = cached.getSkey();
        return v == null ? "" : v.trim();
    }

    /** 书单链路是否可用：八项凭证全配了才算（synckey 自动维护，不看它） */
    public boolean booklistReady() {
        return describeMissing(cached) == null;
    }

    /**
     * 保存。<b>八项书单凭证全量覆盖</b>，缺项由 {@code WereadCredentialServiceImpl}
     * 在调这里之前拦掉（弹给人看的提示）；{@code listUrl} 不在书单表单里，
     * 用基线值原样保留。
     *
     * <p>🔴 {@code synckey} <b>原样保留 cached 的值</b>：表单已没有这一项，
     * 保存动作绝不能把 {@link #refreshSynckeyNow()} 刚刷新进去的值冲掉 ——
     * 那会让「表单下方显示的最近一次请求值」无谓回退。反正下次书单请求
     * 发出前还会再刷新，冲掉也不致命，但没必要。
     */
    public WereadCredential save(WereadCredentialRequest req) {
        WereadCredential next = newDocument();
        next.setListUrl(cached.getListUrl());
        // ★ synckey 不取 req，保留当前值（下次书单请求前还会再刷新）
        next.setSynckey(cached.getSynckey());
        next.setBooklistUrl(req.getBooklistUrl().trim());
        next.setSkey(req.getSkey().trim());
        next.setChannelid(req.getChannelid().trim());
        next.setVid(req.getVid().trim());
        next.setPriority(req.getPriority().trim());
        next.setAcceptLanguage(req.getAcceptLanguage().trim());
        next.setBasever(req.getBasever().trim());
        next.setV(req.getV().trim());
        next.setUpdatedAt(new Date());

        // 先落库 → 成功了才换内存。反过来会出现「内存里已经是新值、
        // 库里还是旧值」，重启后悄悄回退。
        WereadCredential saved = repository.save(next);
        this.cached = saved;
        log.info("接口凭证已保存：{}", completeness());
        return saved;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static final String INCOMPLETE_PREFIX = "书单配置信息不完整";

    /** 「8/8 项已配置」或「6/8 项，缺 sUrl、vid」——只出现字段名，不出现值 */
    private String completeness() {
        String missingTail = describeMissingTail(cached);
        if (missingTail.isEmpty()) {
            return "书单凭证 8/8 项已配置";
        }
        int missingCount = missingTail.split("、").length;
        return "书单凭证 " + (8 - missingCount) + "/8 项，缺 " + missingTail;
    }

    /**
     * 缺项列表（顿号分隔）；没有缺项时为空串。
     *
     * <p>⚠️ <b>synckey 刻意不在此列</b>：2026-10-15 晚起由
     * {@link #refreshSynckeyNow()} 每次请求前生成，表单也没有这一项。
     */
    private static String describeMissingTail(WereadCredential c) {
        if (c == null) {
            return "sUrl、skey、channelid、vid、priority、acceptLanguage、basever、v";
        }
        StringBuilder sb = new StringBuilder();
        appendMissing(sb, "sUrl", c.getBooklistUrl());
        appendMissing(sb, "skey", c.getSkey());
        appendMissing(sb, "channelid", c.getChannelid());
        appendMissing(sb, "vid", c.getVid());
        appendMissing(sb, "priority", c.getPriority());
        appendMissing(sb, "acceptLanguage", c.getAcceptLanguage());
        appendMissing(sb, "basever", c.getBasever());
        appendMissing(sb, "v", c.getV());
        return sb.toString();
    }

    private static void appendMissing(StringBuilder sb, String field, String value) {
        if (!isBlank(value)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('、');
        }
        sb.append(field);
    }

    private WereadCredential newDocument() {
        WereadCredential doc = new WereadCredential();
        doc.setId(DOC_ID);
        return doc;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String v = url.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
