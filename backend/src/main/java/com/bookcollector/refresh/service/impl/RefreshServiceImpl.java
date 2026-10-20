package com.bookcollector.refresh.service.impl;

import com.bookcollector.collector.CollectLoop;
import com.bookcollector.collector.CollectProgressListener;
import com.bookcollector.collector.dto.CollectCommand;
import com.bookcollector.collector.dto.CollectResult;
import com.bookcollector.common.BizException;
import com.bookcollector.common.ResultBean;
import com.bookcollector.config.TaskProperties;
import com.bookcollector.refresh.RefreshTarget;
import com.bookcollector.refresh.entity.RefreshDetail;
import com.bookcollector.refresh.entity.RefreshJob;
import com.bookcollector.refresh.entity.RefreshStatus;
import com.bookcollector.refresh.repository.RefreshJobRepository;
import com.bookcollector.refresh.service.RefreshService;
import com.bookcollector.refresh.vo.RefreshJobVO;
import com.bookcollector.task.TaskHandle;
import com.bookcollector.task.TaskRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.support.CronSequenceGenerator;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link RefreshService} 的实现。
 *
 * <h3>🔴 批次互斥的唯一权威是内存里的 {@link #batchRunning}</h3>
 * DB 里的 {@code lastRunStatus=RUNNING} 只是<b>快照</b>：进程被杀之后它就是假的
 * （要靠启动时的 {@code resetStaleRunning()} 修正）。所以「能不能开始跑」
 * 一律以 {@code compareAndSet} 为准，不看 DB。
 *
 * <p>用 {@code compareAndSet} 而不是「先 get 再 set」：后者在「定时触发」和
 * 「手动点击」同时发生时会让两个批次都通过检查 —— 于是同一批目标被采两遍。
 *
 * <h3>🔴 为什么手动触发需要自己的线程池</h3>
 * 直觉上应该复用 {@code AsyncConfig.collectExecutor}，但它的
 * {@code corePoolSize=1} + 有界队列意味着：<b>只要有一个采集在跑，
 * 提交进去的刷新批次就会排进队列等着</b>。而刷新批次的第一件事是抢目标锁，
 * 抢不到就该<b>立刻</b>报「被占用，已中止」。
 *
 * <p>排队等 30 分钟之后再报「已中止」，用户早就以为它跑完了。
 * 所以这里用一个独立线程池：提交即刻开跑，冲突即刻中止。
 *
 * <h3>⚠️ 关停顺序</h3>
 * Spring 关闭时先发 {@code ContextClosedEvent} → {@code TaskRegistry} 取消所有
 * 采集权 → 正在跑的 {@code CollectLoop} 立刻抛取消异常退出 → 批次收尾 →
 * 然后才轮到 {@code @PreDestroy} 关本类的线程池。所以 20 秒的等待几乎不会用满。
 * （不要改成依赖 {@code @PreDestroy} 去唤醒采集线程 —— 那时候已经太晚了，
 * 这是 {@code TaskRegistry} 类注释里踩过的坑。）
 *
 * <h3>实时进度为什么不落库</h3>
 * 页面 2 秒轮询一次，采集是<b>逐页</b>推进的 —— 每页写一次 Mongo
 * 意味着一个批次最多几百次写，只为展示进度。所以进度只活在内存里
 * （{@link #liveDetails}）：采集线程逐页换一份不可变快照，
 * {@link #getJob()} 在执行中读它、执行完读 DB 终态。
 * 用户 2026-10-08 明确要求「不能黑盒等待，要看得到执行到哪了」。
 */
@Service
public class RefreshServiceImpl implements RefreshService {

    private static final Logger log = LoggerFactory.getLogger(RefreshServiceImpl.class);

    /** 触发方式：手动点「立即执行」 */
    public static final String TRIGGER_MANUAL = "MANUAL";

    /** 触发方式：cron 定时 */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    /**
     * 中止时的文案。用户 2026-10-08 指定，<b>逐字</b>照抄。
     *
     * <p>两种中止原因（某个目标被占用 / 全局并发闸门已满）共用它 ——
     * 对用户来说都是「有手动采集在跑，所以刷新让路了」。
     */
    static final String ABORT_MESSAGE = "定时任务被手动采集占用，已中止";

    /**
     * 传给 {@code TaskRegistry} 的合成任务 ID。
     *
     * <p>刷新任务没有 {@code CollectTask}，但目标锁需要一个「谁占着」的标识 ——
     * 用户手动启一个同目标的任务时，报错信息会显示
     * 「目标「飙升榜」正在采集中（任务 定时刷新），请先停止它再启动新任务」。
     */
    private static final String REFRESH_TASK_ID = "定时刷新";

    private final RefreshJobRepository repository;
    private final CollectLoop collectLoop;
    private final TaskRegistry registry;
    private final TaskProperties taskProperties;

    /** 批次互斥锁。见类注释 */
    private final AtomicBoolean batchRunning = new AtomicBoolean(false);

    /**
     * 批次执行中的<b>实时明细</b>（内存态，<b>不落库</b>）。
     *
     * <h3>为什么放内存</h3>
     * 页面 2 秒轮询一次，而采集是<b>逐页</b>推进的 —— 每页写一次 Mongo
     * 意味着一个批次最多几百次写，只为展示进度，不划算。
     * 内存里每页只换一个对象引用（{@link AtomicReference#set}），成本可忽略。
     *
     * <p>代价是「进程被杀时最后几页的进度丢了」—— 那种情况下整批本来就没意义了
     * （启动时会被 {@code resetStaleRunning()} 标成已中断）。
     *
     * <h3>为什么是 {@code AtomicReference<List>} 而不是可变 List</h3>
     * 写方是采集线程（单线程），读方是 HTTP 线程，两者并发。
     * 每次发布<b>一份全新的列表 + 全新的元素对象</b>，靠 {@code set/get}
     * 的 happens-before 保证读方看到的是<b>一致的一份</b>，
     * 而不是「改了一半」的中间态。列表只有 8 个元素，拷一份不值一提。
     *
     * <p>⚠️ 只在 {@link #batchRunning} 为 true 时有意义；批次结束后页面读 DB 里的终态。
     */
    private final AtomicReference<List<RefreshDetail>> liveDetails =
            new AtomicReference<List<RefreshDetail>>();

    /** 手动触发的执行线程。见类注释「为什么需要自己的线程池」 */
    private final ThreadPoolTaskExecutor batchExecutor = new ThreadPoolTaskExecutor();

    public RefreshServiceImpl(RefreshJobRepository repository,
                              CollectLoop collectLoop,
                              TaskRegistry registry,
                              TaskProperties taskProperties) {
        this.repository = repository;
        this.collectLoop = collectLoop;
        this.registry = registry;
        this.taskProperties = taskProperties;
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @PostConstruct
    public void init() {
        batchExecutor.setCorePoolSize(1);
        batchExecutor.setMaxPoolSize(1);
        // 队列只留 1 个位置：正常情况永远用不到（batchRunning 已经挡住重复提交），
        // 留一个是为了极端并发下「拒绝」而不是「静默排队」
        batchExecutor.setQueueCapacity(1);
        batchExecutor.setThreadNamePrefix("refresh-");
        batchExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        batchExecutor.setWaitForTasksToCompleteOnShutdown(true);
        batchExecutor.setAwaitTerminationSeconds(20);
        batchExecutor.initialize();

        // 清理上次进程留下的假死 RUNNING。
        // 包 try-catch：Mongo 暂时连不上不该让应用起不来 ——
        // 页面首次访问时 loadOrCreate() 还会兜一次。
        try {
            if (repository.resetStaleRunning() > 0) {
                log.warn("定时刷新：把上次进程遗留的「执行中」状态标为已中断");
            }
        } catch (Exception e) {
            log.warn("定时刷新：启动清理假死状态失败（不影响启动）：{}", e.getMessage());
        }
        log.info("定时刷新已就绪：{} 个目标（{}）",
                RefreshTarget.ALL.size(), RefreshTarget.ALL);
    }

    @PreDestroy
    public void destroy() {
        batchExecutor.shutdown();
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Override
    public RefreshJobVO getJob() {
        RefreshJob job = repository.loadOrCreate();
        RefreshStatus status = RefreshStatus.of(job.getLastRunStatus());
        boolean running = batchRunning.get();

        // 执行中读内存快照（逐页刷新，页面上看得见「跑到哪了」）；
        // 否则读 DB 里上一次的终态。
        // ⚠️ 不存在「已停止但 DB 还没写终态」的空窗：doBatch 一定是
        //    「先 markFinished 写库、返回后才 batchRunning=false」（见 doBatch 的调用方）。
        List<RefreshDetail> details;
        if (running) {
            List<RefreshDetail> live = liveDetails.get();
            details = live == null ? Collections.<RefreshDetail>emptyList() : live;
        } else {
            details = job.getLastDetails() == null
                    ? Collections.<RefreshDetail>emptyList() : job.getLastDetails();
        }

        int success = 0;
        int failed = 0;
        for (RefreshDetail d : details) {
            if (RefreshStatus.SUCCESS.name().equals(d.getStatus())) {
                success++;
                continue;
            }
            if (RefreshStatus.RUNNING.name().equals(d.getStatus())) {
                // 执行中的占位项**既不算成功也不算失败** —— 它还没跑完。
                // 不排除的话，执行中会渲染出「成功 2 · 未成功 1」这种看不懂的统计。
                // （它自己那行的状态列会显示「执行中」，不需要在这里再数一遍。）
                continue;
            }
            if (RefreshStatus.ABORTED.name().equals(d.getStatus())) {
                // ⚠️ ABORTED 也不算「失败」：那个目标压根没跑，是被中止点挡住的。
                // 把它算进 failed 会渲染出「成功 0 · 未成功 1」这种看不懂的统计 ——
                // 明明 8 个目标，为什么是 1？中止的原因写在 lastRunMessage 里。
                continue;
            }
            failed++;
        }

        return RefreshJobVO.builder()
                .cron(job.getCron())
                .enabled(Boolean.TRUE.equals(job.getEnabled()))
                .nextFireTime(nextFireTime(job))
                .running(running)
                .targets(RefreshTarget.ALL)
                .lastRunAt(job.getLastRunAt())
                .lastRunFinishedAt(job.getLastRunFinishedAt())
                .lastRunStatus(status.name())
                .lastRunStatusLabel(status.getLabel())
                .lastRunTrigger(job.getLastRunTrigger())
                .lastRunMessage(job.getLastRunMessage())
                .lastRunSuccess(success)
                .lastRunFailed(failed)
                .lastDetails(details)
                .build();
    }

    @Override
    public String currentCron() {
        String cron = repository.loadOrCreate().getCron();
        return (cron == null || cron.trim().isEmpty()) ? RefreshJob.DEFAULT_CRON : cron.trim();
    }

    // ==================================================================
    // 改配置
    // ==================================================================

    @Override
    public RefreshJobVO updateConfig(String cron, Boolean enabled) {
        boolean cronGiven = cron != null && !cron.trim().isEmpty();
        if (!cronGiven && enabled == null) {
            throw new BizException(ResultBean.code_warn,
                    "没有需要修改的字段（cron / enabled 至少给一个）");
        }
        if (cronGiven && !CronSequenceGenerator.isValidExpression(cron.trim())) {
            // 早失败：非法 cron 存进去的话，定时触发会在算下一次时间时抛异常，
            // 那才是真正难查的（页面看起来一切正常，就是永远不触发）
            throw new BizException(ResultBean.code_warn,
                    "cron 表达式不合法（需要 6 段：秒 分 时 日 月 周）：" + cron.trim());
        }
        RefreshJob saved = repository.saveConfig(cron, enabled);
        log.info("定时刷新配置已更新：cron={}，enabled={}", saved.getCron(), saved.getEnabled());
        return getJob();
    }

    // ==================================================================
    // 执行
    // ==================================================================

    @Override
    public RefreshJobVO trigger() {
        if (!batchRunning.compareAndSet(false, true)) {
            throw new BizException(ResultBean.code_warn,
                    "定时刷新正在执行中，请等它跑完再试");
        }
        // 立刻丢掉上一批的进度快照：在 doBatch 真正开始之前的这几毫秒里，
        // 页面若轮询过来，应该看到「空的、刚起步」而不是「上一次的明细」
        liveDetails.set(null);
        try {
            // 同步把 RUNNING 写进 DB：前端拿到响应后立刻开始轮询，
            // 如果这一步交给异步线程去做，轮询的头一两秒会看到旧状态，可能提前停止轮询
            repository.markRunning(TRIGGER_MANUAL, new Date());
        } catch (RuntimeException e) {
            batchRunning.set(false);
            throw e;
        }
        try {
            batchExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        doBatch(TRIGGER_MANUAL);
                    } finally {
                        batchRunning.set(false);
                    }
                }
            });
        } catch (RuntimeException e) {
            batchRunning.set(false);
            log.error("定时刷新：提交执行线程失败", e);
            throw new BizException(ResultBean.code_warn,
                    "刷新线程池繁忙，本次未能启动，请稍后重试");
        }
        log.info("定时刷新：已提交手动执行，共 {} 个目标", RefreshTarget.ALL.size());
        return getJob();
    }

    @Override
    public void runScheduled() {
        if (!Boolean.TRUE.equals(repository.loadOrCreate().getEnabled())) {
            log.debug("定时刷新未启用，跳过本次定时触发");
            return;
        }
        if (!batchRunning.compareAndSet(false, true)) {
            log.warn("定时刷新：上一次批次还在执行，跳过本次定时触发");
            return;
        }
        // 同 trigger()：先丢掉上一批的进度快照
        liveDetails.set(null);
        try {
            doBatch(TRIGGER_SCHEDULED);
        } finally {
            batchRunning.set(false);
        }
    }

    /**
     * 跑一整批。由 {@link #trigger()} 的异步线程或定时触发线程调用。
     *
     * <p><b>不抛异常</b>：批次是无人值守的（尤其是定时那条路），
     * 异常穿透出去只会变成线程池里一条没人看的堆栈，而 DB 里的状态会永远停在 RUNNING。
     * 所以所有异常都在这里收口，并保证一定写出终态。
     */
    private void doBatch(String trigger) {
        Date startedAt = new Date();
        List<RefreshDetail> details = new ArrayList<RefreshDetail>();
        // 立刻发布一份空快照：页面此刻轮询过来，看到的是「刚起步」，
        // 而不是上一批残留的明细
        publishLive(details);
        boolean aborted = false;

        try {
            repository.markRunning(trigger, startedAt);
            log.info("定时刷新批次开始（{}），共 {} 个目标", trigger, RefreshTarget.ALL.size());

            // ---- 全局并发闸门：与 TaskService.launch 同一口径 ----
            // 不检查的话，刷新批次会和用户手动跑着的采集任务并行；
            // 两边各有自己的限速器（SimpleRateLimiter 是 CollectLoop 的实例字段），
            // 请求频率直接翻倍，有被阅读平台限流的风险。
            int max = Math.max(1, taskProperties.getMaxConcurrentRuns());
            if (registry.activeCount() >= max) {
                aborted = true;
                details.add(abortDetail(null, describeActive()));
                publishLive(details);
                log.warn("定时刷新中止：已有采集在运行（{}），本次未开始", describeActive());
            } else {
                for (RefreshTarget target : RefreshTarget.ALL) {
                    TaskHandle handle;
                    try {
                        handle = registry.acquire(REFRESH_TASK_ID, null,
                                target.getType().name(), target.getTargetId(), target.getName());
                    } catch (BizException e) {
                        // 中止整批：不再尝试后面的目标。
                        // 已经跑完的目标不回滚 —— 它们是幂等 upsert 的产物，数据是对的。
                        aborted = true;
                        details.add(abortDetail(target, e.getMessage()));
                        publishLive(details);
                        log.warn("定时刷新中止：目标「{}」正被手动采集占用（{}）",
                                target.getName(), e.getMessage());
                        break;
                    }
                    // ★ 先占位再开跑：一个目标要跑 1~2 分钟，
                    //   不占位的话页面在它跑完之前什么都看不到（就是「黑盒等待」）。
                    //   占位之后页面立刻显示「正在采：X」，页数/本数由逐页回调实时更新。
                    int index = details.size();
                    details.add(runningDetail(target));
                    publishLive(details);
                    try {
                        details.set(index, collectOne(target, handle,
                                liveListener(target, details, index)));
                        publishLive(details);
                    } catch (Throwable t) {
                        // CollectLoop 承诺不抛异常，这里兜住它自己可能出的错（写库失败等）
                        log.error("定时刷新：目标「{}」执行异常", target.getName(), t);
                        details.set(index, failedDetail(target, t));
                        publishLive(details);
                    } finally {
                        // ★ 无论如何都要释放采集权，否则该目标永远无法再被采集
                        registry.release(handle.key());
                    }
                }
            }
        } catch (Throwable t) {
            log.error("定时刷新批次异常终止", t);
            details.add(failedDetail(null, t));
            publishLive(details);
        }

        RefreshStatus status = decideStatus(aborted, details);
        String message = buildMessage(status, details, aborted);
        try {
            repository.markFinished(status, message, details, new Date());
        } catch (Exception e) {
            log.error("定时刷新：写批次终态失败（{}）", message, e);
        }
        log.info("定时刷新批次结束：{} —— {}", status.getLabel(), message);
    }

    /** 采一个目标。不抛异常（异常由调用方兜）。{@code listener} 可为 null（不关心实时进度时） */
    private RefreshDetail collectOne(RefreshTarget target, TaskHandle handle,
                                     CollectProgressListener listener) {
        CollectCommand cmd = CollectCommand.builder()
                // 无 TaskRun。ProgressReporter 对 runId=null 静默降级，
                // ApiRequestRepository 也整体包了 try-catch（见各自的类注释）
                .runId(null)
                .taskId(REFRESH_TASK_ID)
                .targetType(target.getType())
                .targetId(target.getTargetId())
                .targetName(target.getName())
                // 期刊杂志「只采本月」不在这里配置：它按书自己判
                // （categories[0].categoryId == 1700000），在 CollectLoop 里生效。
                // 不限页数：一直翻到空页为止
                .maxPages(0)
                // skipCursor=true 时这个字段不生效，写 false 是为了表明「不是重置语义」
                .resetCursor(false)
                // ★ 全程不读、不写、不重置游标
                .skipCursor(true)
                .control(handle)
                // ★ 逐页把进度回传给批次（纯内存，不落库）
                .progressListener(listener)
                .build();

        CollectResult r = collectLoop.run(cmd);

        RefreshStatus status;
        if (r.isCanceled()) {
            status = RefreshStatus.CANCELED;
        } else if (r.isFailed()) {
            status = RefreshStatus.FAILED;
        } else {
            status = RefreshStatus.SUCCESS;
        }

        RefreshDetail detail = RefreshDetail.builder()
                .targetType(target.getType().name())
                .targetId(target.getTargetId())
                .targetName(target.getName())
                .status(status.name())
                .pagesDone(r.getPagesDone())
                .booksSaved(r.getBooksSaved())
                .booksInserted(r.getBooksInserted())
                .totalCount(r.getTotalCount())
                .costMs(r.getCostMs())
                .message(describe(target, r, status))
                .build();
        log.info("定时刷新：{} → {}（{}）", target, status.getLabel(), detail.getMessage());
        return detail;
    }

    // ==================================================================
    // 实时进度（内存快照）
    // ==================================================================

    /**
     * 发布一份进度快照给 {@link #getJob()} 读。
     *
     * <p>传的是<b>拷贝</b>：调用方之后继续往 {@code details} 里加/替换元素，
     * 不会影响已发布的那一份。元素本身发布后不再被改动
     * （已跑完的目标是定死的；正在跑的目标每次回调都造新对象）。
     */
    private void publishLive(List<RefreshDetail> details) {
        liveDetails.set(new ArrayList<RefreshDetail>(details));
    }

    /**
     * 目标开跑前的占位明细。
     *
     * <p>一个目标要跑 1~2 分钟。不占位的话，页面在它跑完之前
     * 拿到的明细是空的 —— 那就是用户说的「黑盒等待」。
     * 占位之后页面立刻能显示「正在采：X」。
     */
    private RefreshDetail runningDetail(RefreshTarget target) {
        return RefreshDetail.builder()
                .targetType(target.getType().name())
                .targetId(target.getTargetId())
                .targetName(target.getName())
                .status(RefreshStatus.RUNNING.name())
                .message("采集中…")
                .build();
    }

    /**
     * 造一个把逐页进度写进内存快照的回调。
     *
     * <p>⚠️ 每次都<b>新建</b> {@link RefreshDetail} 再发布，而不是原地改字段 ——
     * 已发布的快照可能正被 HTTP 线程读着，原地改会让它读到「改了一半」的对象。
     * 新建 + {@link AtomicReference#set} 才是一致性发布。
     *
     * @param details 批次的可变明细列表（只有采集线程会改它）
     * @param index   当前目标在列表里的位置
     */
    private CollectProgressListener liveListener(final RefreshTarget target,
                                                 final List<RefreshDetail> details,
                                                 final int index) {
        return new CollectProgressListener() {
            @Override
            public void onProgress(int pagesDone, int booksSaved, int booksInserted,
                                   Integer totalCount) {
                List<RefreshDetail> snapshot = new ArrayList<RefreshDetail>(details);
                snapshot.set(index, RefreshDetail.builder()
                        .targetType(target.getType().name())
                        .targetId(target.getTargetId())
                        .targetName(target.getName())
                        .status(RefreshStatus.RUNNING.name())
                        .pagesDone(pagesDone)
                        .booksSaved(booksSaved)
                        .booksInserted(booksInserted)
                        .totalCount(totalCount)
                        .message("采集中…")
                        .build());
                liveDetails.set(snapshot);
            }
        };
    }

    // ==================================================================
    // 组装结果
    // ==================================================================

    /** 一个目标的结果摘要。成功时说「采了多少」，失败时说「为什么」 */
    private String describe(RefreshTarget target, CollectResult r, RefreshStatus status) {
        StringBuilder sb = new StringBuilder();
        // 用户 2026-10-08 要求看到增量，所以这里把「新增」和「更新」分开说。
        // 整期榜单（200 本）会被 upsert 重写一遍，其中绝大多数是老书 ——
        // 只说「写入 200 本」会让人误以为采回来 200 本新书。
        int updated = r.getBooksSaved() - r.getBooksInserted();
        sb.append("采集 ").append(r.getPagesDone()).append(" 页，新增 ")
                .append(r.getBooksInserted()).append(" 本、更新 ").append(updated).append(" 本");
        if (r.getTotalCount() != null) {
            sb.append("（接口报告该目标共 ").append(r.getTotalCount()).append(" 本）");
        }
        if (r.isCanceled()) {
            sb.append("；已被取消（应用正在关闭？）");
        } else if (r.getErrorMsg() != null) {
            sb.append("；原因：").append(r.getErrorMsg());
        }
        return sb.toString();
    }

    /**
     * 中止点的记录。
     *
     * @param target 被占用而中止的目标；{@code null} 表示整批还没开始就中止了
     * @param reason 具体原因（写进 message 的括号里）
     */
    private RefreshDetail abortDetail(RefreshTarget target, String reason) {
        return RefreshDetail.builder()
                .targetType(target == null ? null : target.getType().name())
                .targetId(target == null ? null : target.getTargetId())
                .targetName(target == null ? "（批次未开始）" : target.getName())
                .status(RefreshStatus.ABORTED.name())
                .message(reason)
                .build();
    }

    /** 异常兜底的结果。{@code target == null} 表示是批次级异常，不是某个目标的问题 */
    private RefreshDetail failedDetail(RefreshTarget target, Throwable t) {
        return RefreshDetail.builder()
                .targetType(target == null ? null : target.getType().name())
                .targetId(target == null ? null : target.getTargetId())
                .targetName(target == null ? "（批次级异常）" : target.getName())
                .status(RefreshStatus.FAILED.name())
                .message(t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : "：" + t.getMessage()))
                .build();
    }

    /** 当前有哪些采集在跑，用于报错信息。形如「目标「文学」」 */
    private String describeActive() {
        List<TaskHandle> active = registry.listActive();
        if (active.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < active.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append("「").append(active.get(i).targetLabel()).append("」");
        }
        return sb.toString();
    }

    private RefreshStatus decideStatus(boolean aborted, List<RefreshDetail> details) {
        if (aborted) {
            return RefreshStatus.ABORTED;
        }
        int ok = 0;
        int bad = 0;
        for (RefreshDetail d : details) {
            if (RefreshStatus.SUCCESS.name().equals(d.getStatus())) {
                ok++;
            } else {
                bad++;
            }
        }
        if (bad == 0) {
            return RefreshStatus.SUCCESS;
        }
        return ok == 0 ? RefreshStatus.FAILED : RefreshStatus.PARTIAL;
    }

    private String buildMessage(RefreshStatus status, List<RefreshDetail> details, boolean aborted) {
        int total = RefreshTarget.ALL.size();
        int ok = 0;
        int bad = 0;
        long saved = 0L;
        long inserted = 0L;
        for (RefreshDetail d : details) {
            if (RefreshStatus.SUCCESS.name().equals(d.getStatus())) {
                ok++;
                saved += d.getBooksSaved() == null ? 0L : d.getBooksSaved();
                inserted += d.getBooksInserted() == null ? 0L : d.getBooksInserted();
            } else if (!RefreshStatus.ABORTED.name().equals(d.getStatus())) {
                bad++;
            }
        }

        if (aborted) {
            // 中止点那条 detail 不算「已执行」——它压根没跑
            return ABORT_MESSAGE + "（中止前已执行 " + (ok + bad) + " / " + total + " 个目标）";
        }
        // 「新增」在前：用户关心的是增量，不是 upsert 总数
        String delta = "新增 " + inserted + " 本、更新 " + (saved - inserted) + " 本";
        if (status == RefreshStatus.SUCCESS) {
            return total + " 个目标全部刷新完成，" + delta;
        }
        if (status == RefreshStatus.FAILED) {
            return total + " 个目标全部失败，请查看各目标的原因";
        }
        return ok + " 个目标成功、" + bad + " 个目标失败，" + delta;
    }

    /**
     * 下一次触发时间。定时未启用、或算不出来时返回 {@code null}。
     *
     * <p>⚠️ 用的是 {@link CronSequenceGenerator} 而不是 {@code CronExpression} ——
     * 后者是 <b>Spring 5.3</b> 才引入的，本项目锁在 Spring Boot 2.3.12
     * （Spring Framework 5.2.15.RELEASE），<b>没有那个类</b>。
     * 升级 Spring Boot 到 2.4+ 之后应该换成 {@code CronExpression.parse(cron).next(...)}，
     * 因为 {@code CronSequenceGenerator} 在 5.3 起已废弃。
     */
    private Date nextFireTime(RefreshJob job) {
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
            // 例如「2 月 30 日」这种永远匹配不到的表达式：格式合法，但算不出下一次
            log.warn("定时刷新：cron 算不出下一次触发时间（{}）：{}", cron, e.getMessage());
            return null;
        }
    }
}
