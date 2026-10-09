package com.bookcollector.publish.repository;

import com.bookcollector.publish.entity.PublishJob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.Date;

/**
 * AI 发布调度配置（{@code ai_publish_job}）的 Mongo 访问。
 *
 * <p>整体照 {@code RefreshJobRepository} 的范式：所有方法都保证「文档一定存在」，
 * 调用方不必处理「还没建出来」的空窗。
 *
 * <h3>🔴 为什么 {@code updateFirst} 前必须先 {@link #loadOrCreate()}</h3>
 * {@code updateFirst} 在文档不存在时<b>静默什么都不做</b>（matchedCount=0，不报错）。
 * 于是「直接调触发接口、而从来没打开过页面」这条路径会表现为：
 * 生成真的跑了、语雀也发了，但状态永远停在初始值 —— 页面看起来像「什么都没发生」。
 * 这类静默失败最难查，所以这里统一兜底。
 */
@Repository
public class PublishJobRepository {

    private static final Logger log = LoggerFactory.getLogger(PublishJobRepository.class);

    private final MongoTemplate mongoTemplate;

    public PublishJobRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // 读
    // ==================================================================

    /** 读配置。不存在就按默认值建一条。{@return 一定非 null} */
    public PublishJob loadOrCreate() {
        PublishJob job = find();
        if (job != null) {
            return job;
        }
        PublishJob fresh = PublishJob.builder()
                .id(PublishJob.SINGLETON_ID)
                .cron(PublishJob.DEFAULT_CRON)
                .enabled(PublishJob.DEFAULT_ENABLED)
                .lastRunMessage("尚未执行过")
                .build();
        try {
            log.info("AI 发布配置不存在，按默认值创建：cron={}，enabled={}",
                    PublishJob.DEFAULT_CRON, PublishJob.DEFAULT_ENABLED);
            return mongoTemplate.save(fresh);
        } catch (DuplicateKeyException e) {
            // 并发首次访问：另一个线程刚建好，读它的
            return find();
        }
    }

    /** 只读，不存在返回 null */
    public PublishJob find() {
        return mongoTemplate.findById(PublishJob.SINGLETON_ID, PublishJob.class);
    }

    // ==================================================================
    // 写
    // ==================================================================

    /** 改配置。{@code null} 表示「不改这个字段」。走实体 save 让审计生效 */
    public PublishJob saveConfig(String cron, Boolean enabled) {
        PublishJob job = loadOrCreate();
        if (cron != null && !cron.trim().isEmpty()) {
            job.setCron(cron.trim());
        }
        if (enabled != null) {
            job.setEnabled(enabled);
        }
        return mongoTemplate.save(job);
    }

    /**
     * 标记一次执行开始。
     *
     * <p>顺手把上一次的结束时间清掉 —— 否则「执行中」那段时间里页面会同时看到
     * 「上次的结束时间」和「正在执行」，看起来像上一次还没跑完。
     */
    public void markRunning(String trigger, Date startedAt) {
        Update u = new Update()
                .set("lastRunStatus", "GENERATING")
                .set("lastRunTrigger", trigger)
                .set("lastRunAt", startedAt)
                .set("lastRunMessage", "执行中…")
                .unset("lastRunFinishedAt");
        update(u);
    }

    /** 标记一次执行结束 */
    public void markFinished(String status, String message, Date finishedAt) {
        Update u = new Update()
                .set("lastRunStatus", status)
                .set("lastRunMessage", message)
                .set("lastRunFinishedAt", finishedAt);
        update(u);
    }

    // ==================================================================

    private void update(Update u) {
        loadOrCreate();
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(PublishJob.SINGLETON_ID)), u, PublishJob.class);
    }
}
