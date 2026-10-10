package com.bookcollector.publish.service.impl;

import com.bookcollector.common.BizException;
import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;
import com.bookcollector.publish.PublishProperties;
import com.bookcollector.publish.client.AiClient;
import com.bookcollector.publish.client.YuqueClient;
import com.bookcollector.publish.dto.AiCompletion;
import com.bookcollector.publish.dto.YuqueDoc;
import com.bookcollector.publish.entity.AiModel;
import com.bookcollector.publish.entity.AiTaxonomyItem;
import com.bookcollector.publish.entity.AiYuqueToken;
import com.bookcollector.publish.entity.Interpretation;
import com.bookcollector.publish.entity.PublishJob;
import com.bookcollector.publish.entity.PublishRecord;
import com.bookcollector.publish.entity.PublishStatus;
import com.bookcollector.publish.entity.SensitiveWord;
import com.bookcollector.publish.prompt.InterpretationPrompt;
import com.bookcollector.publish.req.InterpretationQuery;
import com.bookcollector.publish.repository.AiConfigRepository;
import com.bookcollector.publish.repository.InterpretationRepository;
import com.bookcollector.publish.repository.PublishJobRepository;
import com.bookcollector.publish.repository.PublishRecordRepository;
import com.bookcollector.publish.repository.SensitiveWordRepository;
import com.bookcollector.publish.service.PublishService;
import com.bookcollector.publish.vo.InterpretationVO;
import com.bookcollector.publish.vo.PublishJobVO;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.util.CloseableIterator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.support.CronSequenceGenerator;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link PublishService} 的实现。
 *
 * <h3>结构</h3>
 * 两条互不相干的长任务，各有自己的线程池与互斥位：
 * <ul>
 *   <li><b>初始化候选</b>（{@link #initRunning} / {@link #initExecutor}）：
 *       从 {@code books} 流式扫描 → 分批 upsert 进清单；</li>
 *   <li><b>生成并发布</b>（{@link #genRunning} / {@link #genExecutor}）：
 *       挑一本 → 生成 → 合规替换 → 落盘 → 发语雀。</li>
 * </ul>
 * 分成两个池是有意的：初始化扫几千本书要几十秒到几分钟，
 * 若与生成共用一个单线程池，用户点「生成」会被<b>排在初始化后面</b>，
 * 页面显示「已提交」却迟迟不动 —— 典型的「看起来卡死」。
 *
 * <h3>🔴 互斥的唯一权威是内存里的 AtomicBoolean</h3>
 * 同 {@code RefreshServiceImpl}：DB 里的 {@code lastRunStatus} 只是快照，
 * 进程被杀之后它就是假的。所以「能不能开始跑」一律以 {@code compareAndSet} 为准，不看 DB。
 * 用 {@code compareAndSet} 而不是「先 get 再 set」，是为了让「定时触发」与「手动点击」
 * 同时发生时只有一个能通过。
 *
 * <h3>🔴 实时进度为什么不落库</h3>
 * 页面 2 秒轮询一次，而生成一本要 30~120 秒。进度只有「当前阶段」这么点信息，
 * 却要为此在 Mongo 里反复写 —— 不值得。所以进度只活在内存里
 * （{@link #liveInit} / {@link #liveGen}），{@link #getJob()} 读它。
 * 用户 2026-10-09 明确要求「异步，并可以看到进度，不要卡住一直等待」。
 *
 * <h3>⚠️ 不做启动清理</h3>
 * 进程被杀会留下 {@code status = GENERATING} 的残留记录。
 * 用户 2026-10-09 明确要求<b>不做任何处理</b> —— 页面上能按状态筛出来，
 * 想重来就点行内的「重置为待解读」。
 * （这与 {@code RefreshServiceImpl} 启动时把 RUNNING 标成 INTERRUPTED 的做法相反，
 * 是刻意的：那条路无人值守、必须自愈；这条路是人工介入的。）
 */
@Service
public class PublishServiceImpl implements PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishServiceImpl.class);

    /** 触发方式：手动点「生成并发布」 */
    public static final String TRIGGER_MANUAL = "MANUAL";

    /** 触发方式：cron 定时 */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    // ------------------------------------------------------------------
    // 「一次执行」的总结状态。
    // ⚠️ 与 PublishStatus（单条记录的解读状态）不是一套东西：
    //    这里描述的是「这次任务跑得怎么样」，所以多了 SKIPPED 这种「没干活但也不是错」。
    // ------------------------------------------------------------------

    /** 执行中 */
    static final String RUN_RUNNING = PublishStatus.GENERATING.name();

    /** 成功发布了一本 */
    static final String RUN_OK = "SUCCESS";

    /** 这一本失败了（原因写进 lastRunMessage） */
    static final String RUN_FAILED = "FAILED";

    /** 没干活：候选清单已空，或者定时触发时发现未启用 */
    static final String RUN_SKIPPED = "SKIPPED";

    /** 候选清单空了时的提示语。用户 2026-10-09 指定（方案 A） */
    static final String EMPTY_QUEUE_MESSAGE = "候选清单已空，请点「初始化候选」补充";

    /** 写本地文件时的文件名日期前缀 */
    private static final String FILE_DATE_PATTERN = "yyyy-MM-dd";

    /** 文件名里不能出现的字符（Windows 最严格：{@code \ / : * ? " < > |}） */
    private static final String ILLEGAL_FILE_CHARS = "[\\\\/:*?\"<>|\\r\\n\\t]";

    private final PublishJobRepository jobRepository;
    private final InterpretationRepository interpretationRepository;
    private final PublishRecordRepository publishRecordRepository;
    private final AiConfigRepository aiConfigRepository;
    private final SensitiveWordRepository sensitiveWordRepository;
    private final AiClient aiClient;
    private final YuqueClient yuqueClient;
    private final PublishProperties props;

    /** 初始化候选的互斥位 */
    private final AtomicBoolean initRunning = new AtomicBoolean(false);

    /** 生成并发布的互斥位 */
    private final AtomicBoolean genRunning = new AtomicBoolean(false);

    /** 初始化进度快照（内存态，不落库） */
    private final AtomicReference<InitProgress> liveInit = new AtomicReference<InitProgress>();

    /** 生成进度快照（内存态，不落库） */
    private final AtomicReference<GenProgress> liveGen = new AtomicReference<GenProgress>();

    /** 初始化专用线程（见类注释「分成两个池是有意的」） */
    private final ThreadPoolTaskExecutor initExecutor = new ThreadPoolTaskExecutor();

    /** 生成专用线程 */
    private final ThreadPoolTaskExecutor genExecutor = new ThreadPoolTaskExecutor();

    public PublishServiceImpl(PublishJobRepository jobRepository,
                              InterpretationRepository interpretationRepository,
                              PublishRecordRepository publishRecordRepository,
                              AiConfigRepository aiConfigRepository,
                              SensitiveWordRepository sensitiveWordRepository,
                              AiClient aiClient,
                              YuqueClient yuqueClient,
                              PublishProperties props) {
        this.jobRepository = jobRepository;
        this.interpretationRepository = interpretationRepository;
        this.publishRecordRepository = publishRecordRepository;
        this.aiConfigRepository = aiConfigRepository;
        this.sensitiveWordRepository = sensitiveWordRepository;
        this.aiClient = aiClient;
        this.yuqueClient = yuqueClient;
        this.props = props;
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @PostConstruct
    public void init() {
        setUpExecutor(initExecutor, "ai-init-");
        setUpExecutor(genExecutor, "ai-publish-");

        // 只做一次「配置是否就绪」的体检，方便启动日志里一眼看出问题。
        // ⚠️ 刻意不打印任何配置内容 —— 只打有没有、以及非敏感的模型名。
        try {
            AiModel model = aiConfigRepository.loadEnabledModel();
            AiYuqueToken token = aiConfigRepository.loadYuqueToken();
            int categories = aiConfigRepository.listEnabledTaxonomy().size();
            log.info("AI 书籍解读已就绪：模型 {}，语雀凭证 {}，分类白名单 {} 条",
                    model == null ? "未配置" : ("已配置(" + model.getModel() + ")"),
                    token == null ? "未配置" : "已配置",
                    categories);
        } catch (Exception e) {
            log.warn("AI 书籍解读：启动体检失败（不影响启动）：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        initExecutor.shutdown();
        genExecutor.shutdown();
    }

    private static void setUpExecutor(ThreadPoolTaskExecutor executor, String prefix) {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // 队列只留 1 个位置：正常情况用不到（互斥位已经挡住重复提交），
        // 留一个是为了极端并发下「拒绝」而不是「静默排队」
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Override
    public PublishJobVO getJob() {
        PublishJob job = jobRepository.loadOrCreate();

        // 三张只读配置表。这里每次轮询都会查一遍 —— 都是几十条以内的小集合，
        // 代价可忽略；换来的是「改完配置刷新页面就生效」，不需要重启。
        AiModel model = aiConfigRepository.loadEnabledModel();
        AiYuqueToken token = aiConfigRepository.loadYuqueToken();
        List<AiTaxonomyItem> taxonomy = aiConfigRepository.listEnabledTaxonomy();

        List<String> names = new ArrayList<String>();
        for (AiTaxonomyItem item : taxonomy) {
            if (item != null && item.getName() != null && !item.getName().trim().isEmpty()) {
                names.add(item.getName().trim());
            }
        }

        boolean initBusy = initRunning.get();
        boolean genBusy = genRunning.get();

        InitProgress ip = liveInit.get();
        GenProgress gp = liveGen.get();

        return PublishJobVO.builder()
                .cron(job.getCron())
                .enabled(Boolean.TRUE.equals(job.getEnabled()))
                .nextFireTime(nextFireTime(job))
                .categoryNames(names)
                .categoryCount(names.size())
                // ---- 脱敏：只有「配没配」和三个非敏感字段，绝无 apiKey / token ----
                .modelConfigured(model != null)
                .modelName(model == null ? null : model.getName())
                .modelId(model == null ? null : model.getModel())
                .modelBaseUrl(model == null ? null : model.getBaseUrl())
                .yuqueConfigured(isTokenUsable(token))
                // ---- 内存态 ----
                .initRunning(initBusy)
                .genRunning(genBusy)
                // ---- 初始化进度 ----
                .initScanned(ip == null ? null : ip.scanned)
                .initInserted(ip == null ? null : ip.inserted)
                .initSkipped(ip == null ? null : (ip.scanned - ip.inserted))
                // ---- 生成进度 ----
                .currentBookTitle(genBusy && gp != null ? gp.bookTitle : null)
                .currentPhase(genBusy && gp != null ? gp.phase : null)
                .currentElapsedMs(genBusy && gp != null ? Long.valueOf(System.currentTimeMillis() - gp.startedAtMs) : null)
                .lastMessage(gp != null ? gp.message : job.getLastRunMessage())
                // ---- 清单统计 ----
                .pendingCount(interpretationRepository.countByStatus(PublishStatus.PENDING.name()))
                .generatingCount(interpretationRepository.countByStatus(PublishStatus.GENERATING.name()))
                .publishedCount(interpretationRepository.countByStatus(PublishStatus.PUBLISHED.name()))
                .failedCount(interpretationRepository.countByStatus(PublishStatus.FAILED.name()))
                .totalCount(interpretationRepository.countAll())
                .build();
    }

    @Override
    public String currentCron() {
        String cron = jobRepository.loadOrCreate().getCron();
        return (cron == null || cron.trim().isEmpty()) ? PublishJob.DEFAULT_CRON : cron.trim();
    }

    @Override
    public PageResult<InterpretationVO> pageInterpretations(InterpretationQuery query) {
        PageResult<Interpretation> raw = interpretationRepository.page(query);
        List<InterpretationVO> list = new ArrayList<InterpretationVO>();
        for (Interpretation it : raw.getList()) {
            list.add(InterpretationVO.from(it));
        }
        return PageResult.of(list, raw.getTotal(), raw.getPage(), raw.getSize());
    }

    @Override
    public long deleteInterpretations(List<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            throw new BizException(ResultBean.code_warn, "缺少 bookId 列表");
        }
        long deleted = interpretationRepository.deleteByBookIds(bookIds);
        log.info("AI 书籍解读：批量删除候选清单 {} 条（请求 {} 条）", deleted, bookIds.size());
        return deleted;
    }

    // ==================================================================
    // 配置
    // ==================================================================

    @Override
    public PublishJobVO updateConfig(String cron, Boolean enabled) {
        boolean cronGiven = cron != null && !cron.trim().isEmpty();
        if (!cronGiven && enabled == null) {
            throw new BizException(ResultBean.code_warn, "没有需要修改的字段（cron / enabled 至少给一个）");
        }
        if (cronGiven && !CronSequenceGenerator.isValidExpression(cron.trim())) {
            // 早失败：非法 cron 存进去之后，定时触发会在算下一次时间时抛异常，
            // 那才是真正难查的（页面看起来一切正常，就是永远不触发）
            throw new BizException(ResultBean.code_warn,
                    "cron 表达式不合法（需要 6 段：秒 分 时 日 月 周）：" + cron.trim());
        }
        PublishJob saved = jobRepository.saveConfig(cron, enabled);
        log.info("AI 书籍解读配置已更新：cron={}，enabled={}", saved.getCron(), saved.getEnabled());
        return getJob();
    }

    // ==================================================================
    // 初始化候选
    // ==================================================================

    @Override
    public PublishJobVO initializeCandidates() {
        if (!initRunning.compareAndSet(false, true)) {
            throw new BizException(ResultBean.code_warn, "候选清单正在初始化中，请等它跑完再试");
        }
        // 立刻发布一份「刚起步」的空快照：页面此刻轮询过来应该看到 0/0/0，
        // 而不是上一次跑完的数字
        publishInit(new InitProgress(0, 0));
        try {
            initExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        doInit();
                    } finally {
                        initRunning.set(false);
                    }
                }
            });
        } catch (RuntimeException e) {
            initRunning.set(false);
            log.error("AI 书籍解读：提交初始化线程失败", e);
            throw new BizException(ResultBean.code_warn, "初始化线程池繁忙，本次未能启动，请稍后重试");
        }
        log.info("AI 书籍解读：已提交候选清单初始化");
        return getJob();
    }

    /**
     * 扫 {@code books}，分批快照进清单。<b>不抛异常</b> ——
     * 异常穿透出去只会变成线程池里一条没人看的堆栈，页面上的进度会永远停在半路。
     */
    private void doInit() {
        long startedAt = System.currentTimeMillis();
        try {
            // 🔴 code 是 String，而 books.categories[].categoryId 在库里是 int ——
            //    必须在这里转成整数再传下去。直接传字符串会「恒不匹配、静默返回 0 条」：
            //    2026-10-09 实测，同一批条件 String 命中 0 条、Integer 命中 4533 条。
            List<Integer> codes = new ArrayList<Integer>();
            List<String> unparsable = new ArrayList<String>();
            List<String> subLevel = new ArrayList<String>();
            for (AiTaxonomyItem item : aiConfigRepository.listEnabledTaxonomy()) {
                if (item == null) {
                    continue;
                }
                String code = item.getCode() == null ? "" : item.getCode().trim();
                if (code.isEmpty()) {
                    continue;
                }
                // 二级分类的 code 是 subCategoryId，而查询用的是 categories.categoryId（一级）。
                // 传下去只会静默贡献 0 条，所以显式跳过并说出来 —— 不猜、不静默吞掉。
                if (item.getParentCode() != null && !item.getParentCode().trim().isEmpty()) {
                    subLevel.add(item.getName() + "(" + code + ")");
                    continue;
                }
                Integer parsed = toInt(code);
                if (parsed == null) {
                    unparsable.add(item.getName() + "(" + code + ")");
                    continue;
                }
                codes.add(parsed);
            }
            if (!unparsable.isEmpty()) {
                log.warn("AI 书籍解读：分类白名单里有 {} 条的 code 不是数字，已跳过：{}",
                        unparsable.size(), unparsable);
            }
            if (!subLevel.isEmpty()) {
                log.warn("AI 书籍解读：分类白名单里有 {} 条是二级分类，已跳过（候选范围只按一级分类筛）：{}",
                        subLevel.size(), subLevel);
            }
            if (codes.isEmpty()) {
                String msg = "分类白名单里没有可用的一级分类（ai_taxonomy_config），未做任何事";
                publishInit(new InitProgress(0, 0));
                log.warn("AI 书籍解读：{}", msg);
                jobRepository.markFinished(RUN_SKIPPED, msg, new Date());
                return;
            }
            List<String> ratingTitles = props.getRatingTitles();
            if (ratingTitles == null || ratingTitles.isEmpty()) {
                String msg = "评级档位白名单为空（bookcollector.publish.rating-titles），未做任何事";
                publishInit(new InitProgress(0, 0));
                log.warn("AI 书籍解读：{}", msg);
                jobRepository.markFinished(RUN_SKIPPED, msg, new Date());
                return;
            }

            log.info("AI 书籍解读：开始初始化候选，分类 {} 个 {}，评级 {}，评价人数 > {}",
                    codes.size(), codes, ratingTitles, props.getMinRatingCount());

            int scanned = 0;
            int inserted = 0;
            int batchSize = Math.max(1, interpretationRepository.batchSize());
            List<Document> batch = new ArrayList<Document>(batchSize);
            Date now = new Date();

            CloseableIterator<Document> it = interpretationRepository.streamCandidates(
                    codes, ratingTitles, props.getMinRatingCount());
            try {
                while (it.hasNext()) {
                    Document doc = it.next();
                    if (doc == null) {
                        continue;
                    }
                    scanned++;
                    // ★ 用户要求「快照所有字段，除了 _class」——
                    //   _class 是 Spring Data 的类型标记，带过来会污染集合
                    doc.remove("_class");
                    doc.put("status", PublishStatus.PENDING.name());
                    doc.put("initializedAt", now);
                    doc.put("createdAt", now);
                    doc.put("updatedAt", now);
                    batch.add(doc);

                    if (batch.size() >= batchSize) {
                        inserted += interpretationRepository.upsertSnapshot(batch);
                        batch.clear();
                        publishInit(new InitProgress(scanned, inserted));
                    }
                }
                if (!batch.isEmpty()) {
                    inserted += interpretationRepository.upsertSnapshot(batch);
                    batch.clear();
                    publishInit(new InitProgress(scanned, inserted));
                }
            } finally {
                // ⚠️ 流式游标必须关，否则连接一直占着
                it.close();
            }

            publishInit(new InitProgress(scanned, inserted));
            String msg = "初始化完成：扫描 " + scanned + " 本，新增 " + inserted
                    + " 条，跳过 " + (scanned - inserted) + " 条（已在清单中）";
            jobRepository.markFinished(RUN_OK, msg, new Date());
            log.info("AI 书籍解读：{}（耗时 {} ms）", msg, System.currentTimeMillis() - startedAt);

        } catch (Throwable t) {
            String msg = "初始化失败：" + brief(t);
            log.error("AI 书籍解读：候选清单初始化失败", t);
            try {
                jobRepository.markFinished(RUN_FAILED, msg, new Date());
            } catch (Exception e) {
                log.error("AI 书籍解读：写初始化失败状态时又出错", e);
            }
            InitProgress cur = liveInit.get();
            publishInit(cur == null ? new InitProgress(0, 0) : cur);
        }
    }

    @Override
    public PublishJobVO clearCandidates() {
        if (initRunning.get() || genRunning.get()) {
            throw new BizException(ResultBean.code_warn, "有任务正在执行中，请等它跑完再清空");
        }
        long n = interpretationRepository.clearPending();
        log.info("AI 书籍解读：已清空未解读候选 {} 条", n);
        return getJob();
    }

    // ==================================================================
    // 生成并发布
    // ==================================================================

    @Override
    public PublishJobVO trigger() {
        if (!genRunning.compareAndSet(false, true)) {
            throw new BizException(ResultBean.code_warn, "已有一本书正在生成中，请等它跑完再试");
        }
        publishGen(new GenProgress("正在挑选图书", null, System.currentTimeMillis(), "正在挑选待解读的图书…"));
        try {
            // 同步把「执行中」写进 DB：前端拿到响应后立刻开始轮询，
            // 这一步若交给异步线程做，轮询的头一两秒会看到旧状态，可能提前停止轮询
            jobRepository.markRunning(TRIGGER_MANUAL, new Date());
        } catch (RuntimeException e) {
            genRunning.set(false);
            throw e;
        }
        try {
            genExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        doGenerate(TRIGGER_MANUAL);
                    } finally {
                        genRunning.set(false);
                    }
                }
            });
        } catch (RuntimeException e) {
            genRunning.set(false);
            log.error("AI 书籍解读：提交生成线程失败", e);
            throw new BizException(ResultBean.code_warn, "生成线程池繁忙，本次未能启动，请稍后重试");
        }
        log.info("AI 书籍解读：已提交手动生成");
        return getJob();
    }

    /**
     * 定时触发。
     *
     * <p>⚠️ 这里<b>丢线程池后立刻返回</b>，而不是像 {@code RefreshServiceImpl.runScheduled()}
     * 那样就地跑。原因：本应用的两个定时任务共用 Spring 为 {@code @EnableScheduling}
     * 建的<b>单线程</b>调度器（{@code RefreshScheduler} 也是 {@code SchedulingConfigurer}）。
     * 就地跑一本要 1~3 分钟，那段时间里定时刷新的
     * {@code Trigger.nextExecutionTime()} 也排在同一条线程上，会被推迟同样久 ——
     * 表现为「定时刷新莫名其妙晚了几分钟触发」。
     */
    @Override
    public void runScheduled() {
        if (!Boolean.TRUE.equals(jobRepository.loadOrCreate().getEnabled())) {
            log.debug("AI 书籍解读未启用，跳过本次定时触发");
            return;
        }
        if (!genRunning.compareAndSet(false, true)) {
            log.warn("AI 书籍解读：上一次还在执行，跳过本次定时触发");
            return;
        }
        publishGen(new GenProgress("正在挑选图书", null, System.currentTimeMillis(), "正在挑选待解读的图书…"));
        try {
            jobRepository.markRunning(TRIGGER_SCHEDULED, new Date());
        } catch (RuntimeException e) {
            genRunning.set(false);
            log.error("AI 书籍解读：定时触发写状态失败", e);
            return;
        }
        try {
            genExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        doGenerate(TRIGGER_SCHEDULED);
                    } finally {
                        genRunning.set(false);
                    }
                }
            });
        } catch (RuntimeException e) {
            genRunning.set(false);
            log.error("AI 书籍解读：定时触发提交线程失败", e);
        }
    }

    /**
     * 完整跑一本：挑书 → 生成 → 合规替换 → 落盘 → 发语雀 → 写终态。
     *
     * <p>⚠️ <b>刻意不校验模型产出</b>（用户 2026-10-09 拍板）：
     * 「AI 输出什么就发什么」—— 产出不再做模板检查，直接走后续流程。
     *
     * <p><b>不抛异常</b>：这是无人值守路径（定时那条尤其），所有异常都在这里收口，
     * 并保证清单里的那条记录和 {@code ai_publish_job} 一定被写成终态 ——
     * 否则页面会永远显示「生成中」。
     *
     * <p>失败<b>不重试</b>（用户 2026-10-08 定），只把原因记进 {@code errorMsg}。
     */
    private void doGenerate(String trigger) {
        Date startedAt = new Date();
        String bookId = null;
        String bookTitle = null;
        // 提升到 try 之外：失败时也要把它落库（见 catch 里的 rawContent）。
        // 失败发生在生成之后的话（校验没过 / 落盘失败 / 语雀失败），
        // 这次 AI 调用已经花掉了，不留原文就等于白烧一次。
        AiCompletion completion = null;
        try {
            // ---- 1. 挑书 ----
            publishGen(new GenProgress("正在挑选图书", null, startedAt.getTime(), "正在挑选待解读的图书…"));
            Interpretation target = interpretationRepository.pickOnePending();
            if (target == null) {
                String msg = EMPTY_QUEUE_MESSAGE;
                publishGen(new GenProgress(null, null, startedAt.getTime(), msg));
                jobRepository.markFinished(RUN_SKIPPED, msg, new Date());
                log.info("AI 书籍解读：{}", msg);
                return;
            }
            bookId = target.getBookId();
            bookTitle = target.getTitle();
            if (bookId == null || bookId.trim().isEmpty()) {
                throw new BizException("候选记录缺少 bookId，无法继续");
            }

            // ---- 2. 占位：标成「生成中」，并清掉上一次的失败原因 ----
            //     先落状态再干活：这样进程中途被杀，页面上能看见它卡在哪一本上
            Map<String, Object> mark = new LinkedHashMap<String, Object>();
            mark.put("status", PublishStatus.GENERATING.name());
            mark.put("trigger", trigger);
            mark.put("startedAt", startedAt);
            mark.put("finishedAt", null);
            mark.put("errorMsg", null);
            // 与 errorMsg 一起清掉上一次的失败痕迹：否则新一轮「生成中」的窗口里，
            // 库里挂着的还是上一轮失败的原文，看的人会误以为这次已经出了东西
            mark.put("rawContent", null);
            mark.put("updatedAt", startedAt);
            interpretationRepository.updateFields(bookId, mark);

            // ---- 3. 生成 ----
            publishGen(new GenProgress("正在生成解读内容", bookTitle, startedAt.getTime(),
                    "正在生成解读内容，通常需要 30~120 秒…"));
            AiModel model = aiConfigRepository.loadEnabledModel();
            if (model == null) {
                throw new BizException("没有可用的 AI 模型，请先在 ai_model 表里配置一行 enable=true 的记录");
            }
            String systemPrompt = InterpretationPrompt.buildSystem();
            String userPrompt = InterpretationPrompt.buildUser(
                    target.getTitle(), target.getAuthor(), target.getCategory(), target.getIntro());
            completion = aiClient.chat(model, systemPrompt, userPrompt);

            // ---- 4. 合规替换 ----
            publishGen(new GenProgress("正在做合规替换", bookTitle, startedAt.getTime(), "正在做合规替换…"));
            List<SensitiveWord> words = sensitiveWordRepository.listEnabledByLengthDesc();
            ReplaceResult rep = replaceSensitiveWords(completion.getContent(), words);
            if (!rep.hits.isEmpty()) {
                log.info("AI 书籍解读：《{}》命中敏感词 {} 个：{}",
                        bookTitle, rep.hits.size(), rep.hits);
            }

            // ---- 5. 落盘 ----
            publishGen(new GenProgress("正在保存到本地", bookTitle, startedAt.getTime(), "正在保存到本地…"));
            String localPath = writeLocalFile(target.getTitle(), rep.text);

            // ---- 6. 发语雀 ----
            publishGen(new GenProgress("正在发布到语雀", bookTitle, startedAt.getTime(), "正在发布到语雀…"));
            AiYuqueToken token = aiConfigRepository.loadYuqueToken();
            if (!isTokenUsable(token)) {
                throw new BizException("没有可用的语雀 token，请先在 ai_yuque_token 表里配置");
            }
            YuqueDoc doc = yuqueClient.createDoc(token.getToken(), docTitle(target), rep.text);
            // prepend=true：最新的解读排在最前面
            yuqueClient.addToToc(token.getToken(), doc.getId(), true);

            // ---- 7. 成功 ----
            Date finishedAt = new Date();
            Map<String, Object> ok = new LinkedHashMap<String, Object>();
            ok.put("status", PublishStatus.PUBLISHED.name());
            ok.put("content", rep.text);
            ok.put("rawContent", completion.getContent());
            ok.put("sensitiveHits", rep.hits);
            ok.put("localPath", localPath);
            ok.put("yuqueDocId", doc.getId());
            ok.put("yuqueUrl", doc.getUrl());
            ok.put("model", completion.getModel());
            ok.put("promptTokens", completion.getPromptTokens());
            ok.put("completionTokens", completion.getCompletionTokens());
            ok.put("errorMsg", null);
            ok.put("finishedAt", finishedAt);
            ok.put("updatedAt", finishedAt);
            interpretationRepository.updateFields(bookId, ok);

            // ---- 7.5 写发布记录（2026-10-10 新增）----
            // 与候选清单分表：候选清单一本只有一行、状态会被覆盖，
            // 而「发布记录」是流水账（同一本可以发多次）。
            writePublishRecord(target, localPath, doc, completion, rep.hits.size(), trigger, finishedAt);

            String msg = "《" + nvl(bookTitle) + "》解读已发布到语雀"
                    + (rep.hits.isEmpty() ? "" : "（合规替换 " + rep.hits.size() + " 个词）");
            publishGen(new GenProgress(null, bookTitle, startedAt.getTime(), msg));
            jobRepository.markFinished(RUN_OK, msg, finishedAt);
            log.info("AI 书籍解读：{}", msg);

        } catch (Throwable t) {
            Date finishedAt = new Date();
            String msg = (bookTitle == null ? "" : "《" + bookTitle + "》") + "处理失败：" + brief(t);
            log.error("AI 书籍解读：{}", msg, t);
            // 单条记录标失败（失败原因给用户看，所以取 message 而不是整条堆栈）
            if (bookId != null) {
                try {
                    Map<String, Object> bad = new LinkedHashMap<String, Object>();
                    bad.put("status", PublishStatus.FAILED.name());
                    bad.put("errorMsg", brief(t));
                    // 失败也留下模型原文（用户 2026-10-09 要求，方案 D）：
                    // 否则「校验没过」时根本看不到它写了什么，判断不了是校验太严还是模型真漏了。
                    // 失败在生成之前（如没配模型）时为 null —— 那本来就没有原文。
                    bad.put("rawContent", completion == null ? null : completion.getContent());
                    bad.put("finishedAt", finishedAt);
                    bad.put("updatedAt", finishedAt);
                    interpretationRepository.updateFields(bookId, bad);
                } catch (Exception e) {
                    log.error("AI 书籍解读：写单条失败状态时又出错", e);
                }
            }
            publishGen(new GenProgress(null, bookTitle, startedAt.getTime(), msg));
            try {
                jobRepository.markFinished(RUN_FAILED, msg, finishedAt);
            } catch (Exception e) {
                log.error("AI 书籍解读：写执行终态失败", e);
            }
        }
    }

    // ==================================================================
    // 发布记录
    // ==================================================================

    /**
     * 把一次成功的发布写成一条流水。
     *
     * <h3>🔴 为什么整段 try-catch 吞掉异常</h3>
     * 走到这里时<b>语雀已经收到文章了</b> —— 主流程是成功的。
     * 如果因为流水表写不进去（网络抖动、磁盘、序列化问题）而抛出去，
     * 会被外层的 {@code catch (Throwable)} 接住，把这次发布判成 <b>FAILED</b>，
     * 于是候选清单里显示「失败」、{@code ai_publish_job} 也记失败 ——
     * 而实际上文章已经在语雀上了。用户会去重发，语雀里就多出一篇重复的。
     *
     * <p>所以这里的选择是：<b>宁可少一条流水，也不能把一次成功的发布判成失败</b>。
     * 代价是流水可能缺行（日志里有 ERROR，可以事后补）。
     */
    private void writePublishRecord(Interpretation target, String localPath, YuqueDoc doc,
                                    AiCompletion completion, int sensitiveHitCount,
                                    String trigger, Date publishedAt) {
        try {
            PublishRecord record = new PublishRecord();
            // 图书快照：发布那一刻的值，之后图书库被改被删都不影响这里
            record.setBookId(target.getBookId());
            record.setTitle(target.getTitle());
            record.setAuthor(target.getAuthor());
            record.setCategory(target.getCategory());
            record.setNewRating(target.getNewRating());
            // 发布产物
            record.setYuqueDocId(doc.getId());
            record.setYuqueUrl(doc.getUrl());
            record.setLocalPath(localPath);
            record.setModel(completion.getModel());
            record.setPromptTokens(completion.getPromptTokens());
            record.setCompletionTokens(completion.getCompletionTokens());
            record.setSensitiveHitCount(sensitiveHitCount);
            record.setTrigger(trigger);
            record.setPublishedAt(publishedAt);
            record.setCreatedAt(publishedAt);
            publishRecordRepository.insert(record);
        } catch (Exception e) {
            log.error("AI 书籍解读：写发布记录失败（本次发布本身已成功，仅流水缺失）", e);
        }
    }

    // ==================================================================
    // 单条操作
    // ==================================================================

    @Override
    public boolean resetToPending(String bookId) {
        if (bookId == null || bookId.trim().isEmpty()) {
            throw new BizException(ResultBean.code_warn, "缺少 bookId");
        }
        boolean ok = interpretationRepository.resetToPending(bookId.trim());
        if (!ok) {
            throw new BizException(ResultBean.code_notfound, "清单里没有这本书：" + bookId);
        }
        log.info("AI 书籍解读：已把 {} 重置为待解读", bookId);
        return true;
    }

    // ==================================================================
    // 敏感词替换
    // ==================================================================

    /**
     * 按词表顺序（<b>已按词长降序</b>）逐个替换。
     *
     * <h3>🔴 为什么必须长词先替</h3>
     * 先替短词会造出半截词：把「极权」替成「绝对权力」之后，
     * 原文里的「极权主义」就变成了「绝对权力主义」—— 原本想整体替换的「极权主义」
     * 再也匹配不到了。排序由 {@code SensitiveWordRepository} 保证。
     *
     * <h3>替换词是数组，逐次轮换</h3>
     * 同一个词在一篇文章里可能出现多次。轮换取替换词（{@code i % size}）
     * 比「每次都取第一个」读起来自然得多。
     *
     * <p>替换后从<b>替换文本之后</b>继续扫描，所以替换词里即使含有原词也不会死循环。
     */
    static ReplaceResult replaceSensitiveWords(String text, List<SensitiveWord> words) {
        ReplaceResult result = new ReplaceResult();
        if (text == null || text.isEmpty()) {
            result.text = text == null ? "" : text;
            return result;
        }
        String current = text;
        List<String> hits = new ArrayList<String>();

        if (words != null) {
            for (SensitiveWord w : words) {
                String word = w.getWord();
                List<String> reps = w.getReplacement();
                if (word == null || word.isEmpty() || reps == null || reps.isEmpty()) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                int from = 0;
                int idx = 0;
                int count = 0;
                while (true) {
                    int at = current.indexOf(word, from);
                    if (at < 0) {
                        break;
                    }
                    String rep = reps.get(idx % reps.size());
                    sb.append(current, from, at).append(rep);
                    from = at + word.length();
                    idx++;
                    count++;
                }
                if (count > 0) {
                    sb.append(current, from, current.length());
                    current = sb.toString();
                    hits.add(word);
                }
            }
        }
        result.text = current;
        result.hits = hits;
        return result;
    }

    // ==================================================================
    // 本地文件
    // ==================================================================

    /**
     * 把正文写到本地 Markdown。
     *
     * <p>文件名 = 日期 + 书名（用户 2026-10-08 定），形如 {@code 2026-10-08_《活着》.md}。
     * 书名里的 Windows 非法字符会被换成下划线 —— 微信读书的书名里有冒号、斜杠并不罕见，
     * 不处理的话 {@code Files.write} 会直接抛 {@code InvalidPathException}。
     *
     * <p>⚠️ 同名文件会被<b>覆盖</b>：同一天对同一本书重新生成，本来就该是「重来一遍」。
     */
    private String writeLocalFile(String title, String content) {
        Path dir = Paths.get(props.getOutputDir()).toAbsolutePath().normalize();
        String fileName = buildFileName(title);
        Path file = dir.resolve(fileName);
        try {
            Files.createDirectories(dir);
            Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception e) {
            throw new BizException("写入本地文件失败（" + file + "）：" + brief(e));
        }
        log.info("AI 书籍解读：正文已写入 {}", file);
        return file.toString();
    }

    /** 文件名：{@code yyyy-MM-dd_《书名》.md} */
    static String buildFileName(String title) {
        String date = new SimpleDateFormat(FILE_DATE_PATTERN).format(new Date());
        String safe = title == null ? "" : title.replaceAll(ILLEGAL_FILE_CHARS, "_").trim();
        // Windows 不允许文件名以点或空格结尾
        while (safe.endsWith(".") || safe.endsWith(" ")) {
            safe = safe.substring(0, safe.length() - 1);
        }
        if (safe.isEmpty()) {
            safe = "未命名";
        }
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        return date + "_《" + safe + "》.md";
    }

    /** 语雀文档标题。用户要求正文一级标题是 {@code # 《书名》 作者名}，这里与它保持一致 */
    private static String docTitle(Interpretation target) {
        String title = nvl(target.getTitle());
        String author = nvl(target.getAuthor());
        return author.isEmpty() ? ("《" + title + "》") : ("《" + title + "》 " + author);
    }

    // ==================================================================
    // 进度快照
    // ==================================================================

    private void publishInit(InitProgress p) {
        liveInit.set(p);
    }

    private void publishGen(GenProgress p) {
        liveGen.set(p);
    }

    /** 初始化进度（不可变快照，发布后不再改动） */
    private static final class InitProgress {
        private final int scanned;
        private final int inserted;

        InitProgress(int scanned, int inserted) {
            this.scanned = scanned;
            this.inserted = inserted;
        }
    }

    /** 生成进度（不可变快照，发布后不再改动） */
    private static final class GenProgress {
        private final String phase;
        private final String bookTitle;
        private final long startedAtMs;
        private final String message;

        GenProgress(String phase, String bookTitle, long startedAtMs, String message) {
            this.phase = phase;
            this.bookTitle = bookTitle;
            this.startedAtMs = startedAtMs;
            this.message = message;
        }
    }

    /** 替换结果：合规后的正文 + 命中的原词 */
    static final class ReplaceResult {
        String text = "";
        List<String> hits = new ArrayList<String>();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static boolean isTokenUsable(AiYuqueToken token) {
        return token != null && token.getToken() != null && !token.getToken().trim().isEmpty();
    }

    /** 异常压成一句话。只取类型 + message，不带堆栈（失败原因是要展示给用户的） */
    private static String brief(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.trim().isEmpty() ? "" : "：" + msg);
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 把 {@code ai_taxonomy_config.code} 解析成整数分类 ID。
     *
     * <p>必须转的原因见 {@code doInit} 的注释：{@code books.categories[].categoryId}
     * 在库里是 int，用字符串去匹配会静默返回 0 条。
     *
     * @return 不是数字时返回 {@code null}（由调用方记 WARN 并跳过，不猜、不静默）
     */
    private static Integer toInt(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 下一次触发时间。未启用、或算不出来时返回 {@code null}。
     *
     * <p>⚠️ 用的是 {@link CronSequenceGenerator} 而不是 {@code CronExpression} ——
     * 后者是 <b>Spring 5.3</b> 才引入的，本项目锁在 Spring Boot 2.3.12
     * （Spring Framework 5.2.15.RELEASE），<b>没有那个类</b>。
     * 升级 Spring Boot 到 2.4+ 之后应换成 {@code CronExpression.parse(cron).next(...)}。
     */
    private Date nextFireTime(PublishJob job) {
        if (!Boolean.TRUE.equals(job.getEnabled())) {
            return null;
        }
        String cron = job.getCron();
        if (!CronSequenceGenerator.isValidExpression(cron)) {
            return null;
        }
        try {
            return new CronSequenceGenerator(cron).next(new Date());
        } catch (RuntimeException e) {
            // 例如「2 月 30 日」这种格式合法但永远匹配不到的表达式
            log.warn("AI 书籍解读：cron 算不出下一次触发时间（{}）：{}", cron, e.getMessage());
            return null;
        }
    }
}
