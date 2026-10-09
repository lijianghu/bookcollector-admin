package com.bookcollector.refresh.repository;

import com.bookcollector.refresh.entity.RefreshDetail;
import com.bookcollector.refresh.entity.RefreshJob;
import com.bookcollector.refresh.entity.RefreshStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 定时刷新配置（{@code refresh_jobs}）的 Mongo 访问。
 *
 * <h3>为什么所有方法都保证「文档一定存在」</h3>
 * 单例文档用固定 {@code _id}（{@link RefreshJob#SINGLETON_ID}）。
 * 如果让调用方自己处理「还没建出来」的情况，每个写方法都要先判空，
 * 而且「第一次访问」这条路径几乎不会被测到 —— 典型的只在生产环境炸的 bug。
 *
 * <p>所以 {@link #loadOrCreate()} 兜住一切：读不到就按默认值建一条。
 * 建的时候有并发风险（两个线程同时发现文档不存在），用 {@code _id} 唯一性挡住 ——
 * 后到的那个吃 {@link DuplicateKeyException}，转去读对方建好的那条。
 *
 * <h3>两条写入路径的区别</h3>
 * <ul>
 *   <li>{@link #saveConfig} 走<b>实体 save</b>：改配置是低频操作，
 *       用实体路径可以让 {@code @LastModifiedDate} 生效（见 {@code MongoConfig} 的说明）</li>
 *   <li>{@link #markRunning} / {@link #markFinished} 走 <b>{@code Update}</b>：
 *       只改运行状态几个字段，不需要读整条文档再写回，
 *       也避免把 {@code lastDetails} 这种数组整体回写（写放大）</li>
 * </ul>
 */
@Repository
public class RefreshJobRepository {

    private static final Logger log = LoggerFactory.getLogger(RefreshJobRepository.class);

    /** 启动时清理假死状态时写进 {@code lastRunMessage} 的原因 */
    public static final String STALE_MESSAGE =
            "JVM 重启导致中断（上次执行的状态没有收尾）";

    private final MongoTemplate mongoTemplate;

    public RefreshJobRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // 读
    // ==================================================================

    /**
     * 读配置。不存在就按默认值建一条。
     *
     * @return 一定非 null
     */
    public RefreshJob loadOrCreate() {
        RefreshJob job = find();
        if (job != null) {
            return job;
        }
        RefreshJob fresh = RefreshJob.builder()
                .id(RefreshJob.SINGLETON_ID)
                .cron(RefreshJob.DEFAULT_CRON)
                .enabled(RefreshJob.DEFAULT_ENABLED)
                .lastRunStatus(RefreshStatus.IDLE.name())
                .lastRunMessage("尚未执行过")
                .build();
        try {
            log.info("定时刷新配置不存在，按默认值创建：cron={}，enabled={}",
                    RefreshJob.DEFAULT_CRON, RefreshJob.DEFAULT_ENABLED);
            return mongoTemplate.save(fresh);
        } catch (DuplicateKeyException e) {
            // 并发首次访问：另一个线程刚建好，读它的
            return find();
        }
    }

    /** 只读，不存在返回 null */
    public RefreshJob find() {
        return mongoTemplate.findById(RefreshJob.SINGLETON_ID, RefreshJob.class);
    }

    // ==================================================================
    // 写
    // ==================================================================

    /**
     * 改配置（cron / enabled）。{@code null} 表示「不改这个字段」。
     *
     * <p>走实体 save，让 {@code @LastModifiedDate} 生效。
     */
    public RefreshJob saveConfig(String cron, Boolean enabled) {
        RefreshJob job = loadOrCreate();
        if (cron != null && !cron.trim().isEmpty()) {
            job.setCron(cron.trim());
        }
        if (enabled != null) {
            job.setEnabled(enabled);
        }
        return mongoTemplate.save(job);
    }

    /**
     * 标记批次开始。
     *
     * <p>顺手把上一次的 {@code lastRunFinishedAt} 和 {@code lastDetails} 清掉 ——
     * 否则「执行中」的那几十秒里，页面上会同时显示「上次的结果」和「执行中」，
     * 看起来像是上一次还没跑完。
     */
    public void markRunning(String trigger, Date startedAt) {
        Update u = new Update()
                .set("lastRunStatus", RefreshStatus.RUNNING.name())
                .set("lastRunTrigger", trigger)
                .set("lastRunAt", startedAt)
                .set("lastRunMessage", "执行中…")
                .set("lastDetails", Collections.<RefreshDetail>emptyList())
                .unset("lastRunFinishedAt");
        update(u);
    }

    /** 标记批次结束。{@code details} 只含实际尝试过的目标 */
    public void markFinished(RefreshStatus status, String message,
                             List<RefreshDetail> details, Date finishedAt) {
        Update u = new Update()
                .set("lastRunStatus", status.name())
                .set("lastRunMessage", message)
                .set("lastDetails", details == null ? Collections.<RefreshDetail>emptyList() : details)
                .set("lastRunFinishedAt", finishedAt);
        update(u);
    }

    /**
     * 启动时清理假死状态：把上次进程留下的 {@code RUNNING} 标成 {@code INTERRUPTED}。
     *
     * <h3>为什么必须做</h3>
     * 与 {@code InterruptedTaskDetector} 是同一个问题：进程被杀时来不及写终态，
     * 库里留下 {@code RUNNING}，而内存里根本没有批次在跑（{@code AtomicBoolean} 是新的）。
     * 于是页面永远显示「执行中」，用户没有任何办法让它结束。
     *
     * <p><b>只有进程启动的那一刻，能确定「所有 RUNNING 都是假的」</b> ——
     * 所以这里在 {@code @PostConstruct} 里跑一次。
     *
     * @return 被修正的文档数（0 或 1）
     */
    public long resetStaleRunning() {
        Query q = Query.query(Criteria.where("_id").is(RefreshJob.SINGLETON_ID)
                .and("lastRunStatus").is(RefreshStatus.RUNNING.name()));
        Update u = new Update()
                .set("lastRunStatus", RefreshStatus.INTERRUPTED.name())
                .set("lastRunMessage", STALE_MESSAGE)
                .set("lastRunFinishedAt", new Date());
        return mongoTemplate.updateFirst(q, u, RefreshJob.class).getModifiedCount();
    }

    // ==================================================================

    /**
     * 执行一次 {@code Update}。
     *
     * <h3>🔴 为什么必须先 {@link #loadOrCreate()}</h3>
     * {@code updateFirst} 在**文档不存在时会静默地什么都不做**（matchedCount=0，不报错）。
     * 于是「直接调 {@code POST /api/refresh/trigger}，而从来没打开过页面」这条路径
     * 会表现为：采集真的跑了、书真的入库了，但状态永远停在 {@code IDLE}、明细也是空的 ——
     * 页面看起来像「什么都没发生」。这类静默失败最难查。
     *
     * <p>代价是每次写多一次 {@code findById}。一个批次只写两次状态，可以忽略。
     */
    private void update(Update u) {
        loadOrCreate();
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(RefreshJob.SINGLETON_ID)), u, RefreshJob.class);
    }
}
