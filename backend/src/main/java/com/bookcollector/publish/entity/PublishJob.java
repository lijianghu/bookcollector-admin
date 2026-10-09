package com.bookcollector.publish.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 发布的<b>调度配置</b>（集合 {@code ai_publish_job}，单例文档）。
 *
 * <h3>为什么单独一张表，而不是塞进 {@code settings} 的 KV</h3>
 * 与 {@code refresh_jobs} 同构：这里除了 cron / enabled 还有一组「最近一次执行」的
 * 派生状态（{@code lastRunStatus} / {@code lastRunMessage} …）。
 * 塞进 KV 的话，一个逻辑对象会被拆成好几条 key，还要自己拼前缀 —— 得不偿失。
 *
 * <h3>为什么用固定主键强制单例</h3>
 * 全库只允许一条配置。用固定 {@code _id} 比「查询时 limit 1」可靠：
 * 多写一条都写不进去（{@code _id} 冲突）。同 {@code RefreshJob.SINGLETON_ID} 的做法。
 *
 * <h3>⚠️ 这张表是<b>程序写的</b>，不是用户手工维护的那四张</h3>
 * 用户手工维护的是 {@code ai_model} / {@code ai_yuque_token} /
 * {@code ai_taxonomy_config} / {@code ai_sensitive_words}（程序只读）。
 * 本表由 {@code PublishServiceImpl} 读写。
 *
 * <p>⚠️ 审计字段只在<b>按实体保存</b>时生效（{@code mongoTemplate.save(entity)}）。
 * 改配置走 save（审计生效）；写执行状态走 {@code Update}（审计不生效，
 * 那几个字段本身有 {@code lastRunAt} / {@code lastRunFinishedAt} 表达时间）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_publish_job")
public class PublishJob implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 单例文档的固定主键 */
    public static final String SINGLETON_ID = "default";

    /**
     * 默认 cron：每天凌晨 3:00（Spring 的 6 段式：秒 分 时 日 月 周）。
     *
     * <p>与定时刷新错开 —— 那个默认也是 3:00，但两者都是「无人值守的长任务」，
     * 真要同时跑会互相抢网络。首次启用时建议手动改一个（如 4:00）。
     */
    public static final String DEFAULT_CRON = "0 0 4 * * ?";

    /** 默认不启用。让用户自己确认「我知道它会自动跑」之后再打开 */
    public static final boolean DEFAULT_ENABLED = false;

    @Id
    private String id;

    /** cron 表达式（6 段式），保存时校验合法性 */
    private String cron;

    /** 是否启用定时触发。「立即生成并发布」不受它影响 */
    private Boolean enabled;

    /** 最近一次执行的开始时间 */
    private Date lastRunAt;

    /** 最近一次执行的结束时间 */
    private Date lastRunFinishedAt;

    /**
     * 最近一次执行的状态。
     *
     * <p>⚠️ 取值<b>不是</b> {@link PublishStatus} —— 这里描述的是「这次任务跑得怎么样」，
     * 用的是 {@code PublishServiceImpl} 里的 {@code RUN_*} 常量：
     * {@code GENERATING}（执行中）/ {@code SUCCESS} / {@code FAILED} / {@code SKIPPED}。
     * 单条记录的解读状态才是 {@link PublishStatus}，两者别混。
     */
    private String lastRunStatus;

    /** 最近一次执行的触发方式：MANUAL / SCHEDULED */
    private String lastRunTrigger;

    /** 最近一次执行的一句话总结，直接展示给用户 */
    private String lastRunMessage;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
