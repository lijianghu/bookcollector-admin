package com.bookcollector.book.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.bookcollector.book.dto.EnrichResult;
import com.bookcollector.book.entity.Book;
import com.bookcollector.book.repository.BookRepository;
import com.bookcollector.collector.BookParser;
import com.bookcollector.collector.WereadClient;
import com.bookcollector.collector.dto.WereadRawResponse;
import com.bookcollector.common.BizException;
import com.bookcollector.common.ResultBean;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 「完善书籍信息」—— 把书单链路漏掉的字段用 {@code /book/info} 逐本补齐（2026-10-16）。
 *
 * <h3>为什么需要这个功能</h3>
 * 书单接口（{@code /booklist/single}）的<b>拍平形态</b>响应里没有 {@code intro}
 * 和 {@code category} 两个字段（{@code categories} 多数有）。所以今天从书单采进来的书
 * 简介、分类是空的。补法：拿 {@code bookId} 调 {@code /book/info} 取回完整字段。
 *
 * <h3>🔴 用户 2026-10-16 拍板的五条口径</h3>
 * <ol>
 *   <li><b>补齐范围由调用方决定</b>：日期范围由列表查询的 {@code minCollectedAt} 控制，
 *       后端不再用 {@code LocalDate.now()} 当闸门（否则隔天就补不到之前采的书）。</li>
 *   <li><b>{@code category} 优先本地派生</b>：{@code categories[0].title} 就是它，
 *       能派生就不发请求（用户明确要求「省掉大半请求」）。</li>
 *   <li><b>顺带补未建模字段</b>（{@code isbn} / {@code publisher} / {@code totalWords} …）。</li>
 *   <li><b>5 并发</b>。</li>
 *   <li><b>请求间隔 1~5 秒随机</b>，不要固定 1 秒（避免被限流）。</li>
 * </ol>
 *
 * <h3>为什么不拆「接口 + impl」</h3>
 * 它只有一个实现、也不会被替换，拆两层是纯粹的样板（同 {@code CollectLoop} 的处理）。
 *
 * <h3>🔴 补齐的写入口径：「只补空，不覆盖」</h3>
 * 库里该字段已经有值就跳过 —— 这个动作叫「完善」不叫「刷新」。
 * 采集链路的「以接口为真相、全量覆盖」是另一套语义，两者不要混。
 *
 * @see com.bookcollector.collector.WereadClient#fetchBookInfo(String)
 */
@Service
public class BookEnrichService {

    private static final Logger log = LoggerFactory.getLogger(BookEnrichService.class);

    /** 并发数。用户 2026-10-16 拍板 5 */
    private static final int CONCURRENCY = 5;

    /** 请求间隔下限（毫秒） */
    private static final long MIN_GAP_MS = 1000L;

    /** 请求间隔上限（毫秒） */
    private static final long MAX_GAP_MS = 5000L;

    /** 单次调用的总超时。一页最多 200 本，5 并发 + 每本最多 5 秒 ≈ 200 秒，给足余量 */
    private static final long TOTAL_TIMEOUT_MINUTES = 30L;

    /**
     * 允许被补齐写入的字段白名单。
     *
     * <p>🔴 刻意用<b>白名单</b>而不是「接口给什么写什么」：接口返回的字段里有些是
     * <b>采集元数据</b>（{@code firstCollectedAt} / {@code lastCollectedAt} /
     * {@code collectSource} / {@code bookLists}），补齐动作绝不能碰它们 ——
     * 否则「最近采集时间」会变成「最近补齐时间」。
     *
     * <p>另外 {@code searchIdx} / {@code typeInfo} / {@code readingCount} 也<b>不在</b>白名单：
     * 它们取自采集响应的<b>外层记录</b>，{@code /book/info} 根本给不了（传进去只会是 null）。
     */
    private static final Set<String> ENRICH_FIELDS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    // 本需求的核心三件
                    "intro", "category", "categories",
                    // 出版与体量
                    "isbn", "publisher", "totalWords", "publishPrice", "bookSize",
                    "chapterSize", "encodeId", "deepLink", "inStoreBookId",
                    // 时间戳
                    "updateTime", "onTime",
                    // 价格 / 市场
                    "unitPrice", "marketType",
                    // 评分补充
                    "star", "ratingCount", "deepVRating", "showDeepVRatingLabel",
                    "ratingDetail",
                    // 展示 / 控制开关
                    "hasKeyPoint", "recommended", "lectureRecommended", "showLectureButton",
                    "isHideTtsButton", "shouldHideTts", "wxtts", "askAiBook",
                    "aiBookButtonType", "autoBookRequest", "latestReviewSecretMode",
                    // 账号个人状态
                    "follow", "secret", "offline", "lectureOffline", "finishReading",
                    "hideReview", "hideFriendMark", "blacked", "hasSubject", "isAutoPay",
                    "availables", "paid", "isChapterPaid",
                    // 文本
                    "aiSummary",
                    // 嵌套对象 / 数组
                    "copyrightInfo", "authorSeg", "translatorSeg", "coverBoxInfo",
                    "skuInfo", "shortTimeRead", "ranklist")));

    private final BookRepository repository;
    private final WereadClient wereadClient;
    private final BookParser parser;
    private final MongoTemplate mongoTemplate;

    /** 互斥位：同一时刻只允许一次补齐（同 {@code BooklistSyncServiceImpl}） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最新进度快照。前端轮询读它 */
    private final AtomicReference<EnrichResult> live = new AtomicReference<EnrichResult>();

    /**
     * 跑补齐的线程池。
     *
     * <p>🔴 <b>不复用采集任务的线程池</b>：那个池的 {@code maxConcurrentRuns=1} 是
     * 「同时最多 1 个采集任务」的全局闸门（对齐接口限速），补齐是另一码事 ——
     * 挤进去会把采集任务挡在门外。这里自己起一个单线程池；
     * 「5 并发」是池内并发，不占采集池。
     */
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public BookEnrichService(BookRepository repository,
                             WereadClient wereadClient,
                             BookParser parser,
                             MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.wereadClient = wereadClient;
        this.parser = parser;
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // 生命周期（照 BooklistSyncServiceImpl 那套）
    // ==================================================================

    @PostConstruct
    public void init() {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // 队列只留 1 个位置：正常用不到（互斥位已挡住重复提交），
        // 留一个是为了极端并发下「明确拒绝」而不是「静默排队」
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("book-enrich-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();

        live.set(idle(null));
    }

    @PreDestroy
    public void destroy() {
        executor.shutdown();
    }

    /**
     * 提交一次补齐 —— <b>提交即返回，不等它跑完</b>。
     *
     * <p>一页最多 200 本、5 并发 + 每请求随机 1~5 秒，可能要几分钟。同步等会让 HTTP
     * 一直挂着被网关/浏览器掐断（用户 2026-10-16：「前端会超时的」），
     * 所以改成「提交 + 轮询」，同 {@code BooklistSyncService#sync} 那套。
     *
     * @param rawBookIds 前端选中的 bookId（当前页）。空/全空白 → 报错
     * @return 刚起步的进度快照（前端立刻就能显示 0/N）
     */
    public EnrichResult enrich(List<String> rawBookIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        if (rawBookIds != null) {
            for (String id : rawBookIds) {
                if (id != null && !id.trim().isEmpty()) {
                    ids.add(id.trim());
                }
            }
        }
        if (ids.isEmpty()) {
            throw new BizException(ResultBean.code_warn, "bookIds 不能为空");
        }

        if (!running.compareAndSet(false, true)) {
            throw new BizException(ResultBean.code_warn, "补齐正在进行中，请等它跑完再试");
        }

        final List<String> targets = new ArrayList<String>(ids);
        final Progress p = new Progress(targets.size());
        p.running = true;
        p.startedAtMs = System.currentTimeMillis();
        p.message = "准备开始";
        live.set(p.toVO());

        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    doEnrich(p, targets);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            live.set(idle("启动失败：" + brief(e)));
            log.error("完善书籍信息：提交线程失败", e);
            throw new BizException(ResultBean.code_warn, "补齐线程池繁忙，本次未能启动，请稍后重试");
        }
        log.info("完善书籍信息：已提交（{} 本）", targets.size());
        return progress();
    }

    /** 当前进度快照。没跑过 / 进程刚起来时给一份 idle 快照（不是 null，前端少一种判空） */
    public EnrichResult progress() {
        EnrichResult vo = live.get();
        return vo == null ? idle(null) : vo;
    }

    /**
     * 真正干活的地方（跑在 {@link #executor} 的单线程里）。
     *
     * <p>🔴 <b>整个方法不抛异常</b>：它是后台线程的入口，抛出去没人接 ——
     * 只会让 {@code running} 永远停在 true，之后所有点击都被「正在进行中」挡回去。
     * 所以 catch 住、把原因写进 {@code message}，在 finally 里复位。
     */
    private void doEnrich(Progress p, List<String> ids) {
        try {
            // ---- 批量取库里的当前值（一次 $in 分片查询，避免 N 次单查）----
            Map<String, Book> byId = new HashMap<String, Book>();
            for (Book b : repository.findByBookIds(ids)) {
                if (b.getBookId() != null) {
                    byId.put(b.getBookId(), b);
                }
            }

        List<Book> needFetch = new ArrayList<Book>();

        for (String id : ids) {
            Book cur = byId.get(id);
            if (cur == null) {
                p.skipped++;
                p.processed++;
                continue;
            }

                // 口径 ②：category 能本地派生就先派生（零请求）
                String derivedCategory = deriveCategory(cur);
                if (derivedCategory != null) {
                    Map<String, Object> localSets = new LinkedHashMap<String, Object>();
                    localSets.put("category", derivedCategory);
                    // 被「完善书籍信息」碰过就记一笔 —— 记「试过」不记「补到了」，
                    // 见 Book#enrichedAt
                    localSets.put("enrichedAt", new Date());
                    repository.updateFields(id, localSets);
                    // 同步内存对象，免得后面 buildEnrichSets 又把接口的 category 重复写一遍
                    cur.setCategory(derivedCategory);
                    p.derived++;
                }

                // 还需要调接口吗：intro 缺 或 categories 缺 → 是（category 已由上面处理）
                if (isBlank(cur.getIntro()) || isEmpty(cur.getCategories())) {
                    needFetch.add(cur);
                } else {
                    if (derivedCategory == null) {
                        p.skipped++;   // 什么都不缺
                    }
                    p.processed++;
                }
            }

            p.fetched = needFetch.size();
            p.message = needFetch.isEmpty() ? "无需调用接口" : "正在逐本补齐";
            publish(p);

            // ---- 口径 ④⑤：5 并发 + 每请求前随机等 1~5 秒 ----
            if (!needFetch.isEmpty()) {
                runConcurrently(needFetch, p);
            }

            p.message = "已完成";
        } catch (Exception e) {
            log.error("完善书籍信息：执行出错", e);
            p.message = "出错：" + brief(e);
        } finally {
            p.running = false;
            p.finishedAtMs = System.currentTimeMillis();
            publish(p);
            running.set(false);
            log.info("完善书籍信息：结束（请求 {} 本，补齐 {} 本，失败 {}，跳过 {}，耗时 {} ms）",
                    p.requested, p.updated + p.derived, p.failed, p.skipped,
                    p.finishedAtMs - p.startedAtMs);
        }
    }

    /**
     * 并发执行补齐，并把进度刷进快照。
     *
     * <p>进度在<b>每本书处理完</b>时刷新（而不是每个请求发出时）——
     * 「已处理」才是有意义的进度；「已发出」在 1~5 秒的随机等待里会长时间不动。
     */
    private void runConcurrently(List<Book> targets, final Progress p) {
        List<Callable<Boolean>> tasks = new ArrayList<Callable<Boolean>>(targets.size());
        for (final Book cur : targets) {
            tasks.add(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    // 🔴 口径 ⑤：每个请求发出前随机等 1~5 秒，不要固定 1 秒。
                    //    放在请求「之前」而不是之后：这样 5 个 worker 的首次请求
                    //    也是错开的，不会一上来就同时打 5 个。
                    sleepRandomGap();
                    boolean ok = enrichOne(cur);
                    // 多个 worker 会同时改 Progress，加锁后再发布，避免快照乱序
                    synchronized (p) {
                        if (ok) {
                            p.updated++;
                        } else {
                            p.failed++;
                        }
                        p.processed++;
                        publish(p);
                    }
                    return ok;
                }
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Future<Boolean>> futures =
                    pool.invokeAll(tasks, TOTAL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            for (Future<Boolean> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    // ⚠️ 计数已经在 call() 里加过了，这里只记日志 —— 别再 ++（会重复计）
                    log.warn("补齐任务异常：{}", e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("补齐被中断，已完成 {} 本", p.processed);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 补一本。
     *
     * @return 是否算成功（接口没给新东西也算成功 —— 不是失败）
     */
    private boolean enrichOne(Book cur) {
        String bookId = cur.getBookId();
        WereadRawResponse resp = wereadClient.fetchBookInfo(bookId);
        if (!resp.isSuccess()) {
            log.warn("补齐失败 {}：{}", bookId, resp.describeFailure());
            return false;
        }

        JSONObject json;
        try {
            json = JSON.parseObject(resp.getBody());
        } catch (Exception e) {
            log.warn("补齐响应不是合法 JSON（{}）：{}", bookId, e.getMessage());
            return false;
        }
        if (json == null) {
            log.warn("补齐响应为空：{}", bookId);
            return false;
        }

        // 🔴 错误信封优先（同采集链路口径）：登录态失效时回的是 {errcode, errlog, errmsg}
        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode.intValue() != 0) {
            log.warn("补齐被接口拒绝 {}：errcode={}", bookId, errcode);
            return false;
        }

        Book parsed = parser.parse(json);
        if (parsed == null) {
            log.warn("补齐解析不出 bookId：{}", bookId);
            return false;
        }

        Map<String, Object> sets = buildEnrichSets(cur, parsed);
        // 🔴 无论补到没补到都记一笔「试过」—— 平台本来就没数据的书，
        //    不该在下次筛选时又被当成「还没补」（见 Book#enrichedAt）。
        //    ⚠️ 接口失败的分支**不写**：那是可重试的临时故障，写了会让它再也补不了。
        sets.put("enrichedAt", new Date());
        repository.updateFields(bookId, sets);
        return true;
    }

    /**
     * 算出「该写哪些字段」：<b>只补库里为空的</b>，且值来自接口。
     *
     * <p>实现上用 Mongo 转换器把两个 {@link Book} 各摊成一个 {@code Document} 再比对 ——
     * 比手工列 50 个 getter 更不容易漏（将来加字段也不用改这里）。
     */
    private Map<String, Object> buildEnrichSets(Book current, Book parsed) {
        Document currentDoc = toDocument(current);
        Document parsedDoc = toDocument(parsed);

        Map<String, Object> sets = new LinkedHashMap<String, Object>();
        for (String key : ENRICH_FIELDS) {
            if (!isEmptyValue(currentDoc.get(key))) {
                continue;                       // 库里已有值 → 不覆盖
            }
            Object value = parsedDoc.get(key);
            if (isEmptyValue(value)) {
                continue;                       // 接口也没给 → 不写
            }
            sets.put(key, value);
        }
        return sets;
    }

    /** 实体 → Document。去掉 {@code _id}（不能进 {@code $set}） */
    private Document toDocument(Book book) {
        Document doc = new Document();
        try {
            mongoTemplate.getConverter().write(book, doc);
        } catch (RuntimeException e) {
            log.warn("实体转 Document 失败（bookId={}）：{}", book.getBookId(), e.getMessage());
        }
        doc.remove("_id");
        return doc;
    }

    /**
     * 本地派生 {@code category} = {@code categories[0].title}（用户 2026-10-16 明确要求）。
     *
     * @return 派生出的分类；已有分类 / 没有 categories / 首条没标题 → {@code null}
     */
    private String deriveCategory(Book book) {
        if (!isBlank(book.getCategory())) {
            return null;                        // 已经有分类了，不派生
        }
        List<Book.BookCategory> categories = book.getCategories();
        if (categories == null || categories.isEmpty()) {
            return null;
        }
        Book.BookCategory first = categories.get(0);
        if (first == null || isBlank(first.getTitle())) {
            return null;
        }
        return first.getTitle().trim();
    }

    /** 请求间隔随机 1~5 秒 */
    private void sleepRandomGap() {
        long gap = ThreadLocalRandom.current().nextLong(MIN_GAP_MS, MAX_GAP_MS + 1L);
        try {
            Thread.sleep(gap);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** null / 空串 / 空集合 / 空 Map 都算「空」 */
    private boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String) {
            return ((String) value).trim().isEmpty();
        }
        if (value instanceof Collection) {
            return ((Collection<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).isEmpty();
        }
        return false;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private boolean isEmpty(Collection<?> c) {
        return c == null || c.isEmpty();
    }

    // ==================================================================
    // 进度快照
    // ==================================================================

    /**
     * 可变进度。只在补齐线程（含它派生的 worker）里改，发布时转成不可变 VO。
     *
     * <p>为什么不直接改 {@link EnrichResult}：那个对象一旦 {@code live.set()} 出去，
     * HTTP 线程随时可能读到 —— 边读边改会读到半截状态
     * （比如 {@code updated} 加了、{@code processed} 还没加）。
     */
    private static final class Progress {

        /** 去重后的入参书数（分母） */
        final int requested;

        /** 已处理完的书数（分子）。前端进度条用它 */
        int processed;
        /** 需要调接口的书数 */
        int fetched;
        /** 调接口成功且写库 */
        int updated;
        /** 调接口失败 */
        int failed;
        /** 只本地派生 category（没调接口） */
        int derived;
        /** 跳过（库里查不到该书 / 什么都不缺） */
        int skipped;

        boolean running;
        long startedAtMs;
        long finishedAtMs;
        String message;

        Progress(int requested) {
            this.requested = requested;
        }

        EnrichResult toVO() {
            EnrichResult vo = new EnrichResult();
            vo.setRunning(running);
            vo.setMessage(message);
            vo.setStartedAtMs(startedAtMs);
            vo.setFinishedAtMs(finishedAtMs);
            vo.setRequested(requested);
            vo.setProcessed(processed);
            vo.setFetched(fetched);
            vo.setUpdated(updated);
            vo.setFailed(failed);
            vo.setDerived(derived);
            vo.setSkipped(skipped);
            vo.setCostMs(startedAtMs > 0 && finishedAtMs > 0 ? finishedAtMs - startedAtMs : 0);
            return vo;
        }
    }

    /** 发布一份新快照 */
    private void publish(Progress p) {
        live.set(p.toVO());
    }

    /** 「没在跑」的快照。{@code message} 可为 null */
    private static EnrichResult idle(String message) {
        EnrichResult vo = new EnrichResult();
        vo.setRunning(false);
        vo.setMessage(message);
        return vo;
    }

    /** 异常 → 一句话（给 {@code message} 用） */
    private static String brief(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
