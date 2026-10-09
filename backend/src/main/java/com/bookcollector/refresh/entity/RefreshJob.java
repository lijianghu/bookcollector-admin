package com.bookcollector.refresh.entity;

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
import java.util.List;

/**
 * 定时刷新任务的<b>配置 + 最近一次执行结果</b>（单例文档）。
 *
 * <h3>为什么是单例</h3>
 * 只有一个刷新任务（固定 8 个目标，见 {@code RefreshTarget.ALL}），
 * 所以全库只允许一条配置。用<b>固定主键</b> {@link #SINGLETON_ID} 强制这一点 ——
 * 比「查询时 limit 1」可靠：多写一条都写不进去（{@code _id} 冲突）。
 *
 * <h3>为什么配置和执行结果放同一条文档</h3>
 * 它们是一体的：页面上就是「一个配置区 + 一个最近结果区」。
 * 拆成两条的话要处理「结果还没建出来」的空窗，纯属自找麻烦。
 *
 * <h3>⚠️ 审计字段的生效范围</h3>
 * {@link #createdAt} / {@link #updatedAt} 只在<b>按实体保存</b>时生效
 * （{@code mongoTemplate.save(entity)}）。所以：
 * <ul>
 *   <li>改配置（cron / enabled）走 {@code save(entity)} → 审计生效</li>
 *   <li>写执行状态（RUNNING / 终态）走 {@code Update} → 审计<b>不</b>生效，
 *       这几个字段本身有 {@link #lastRunAt} / {@link #lastRunFinishedAt} 表达时间</li>
 * </ul>
 * 与 {@code MongoConfig} 的说明一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "refresh_jobs")
public class RefreshJob implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 单例文档的固定主键。
     *
     * <p>用 {@code "default"} 而不是让 Mongo 生成 ObjectId —— 那样就没法保证唯一了。
     */
    public static final String SINGLETON_ID = "default";

    /**
     * 默认 cron：每天凌晨 3:00。
     *
     * <p>选凌晨是因为刷新要跑 1~2 分钟、约 95 次 HTTP 请求，
     * 放在用户可能正在用系统的时段会跟手动采集抢限速。
     *
     * <p>⚠️ 是 <b>Spring 的 6 段式</b>（秒 分 时 日 月 周），不是 Linux 的 5 段式。
     */
    public static final String DEFAULT_CRON = "0 0 3 * * ?";

    /** 默认不启用。让用户自己确认「我知道它会自动跑」之后再打开 */
    public static final boolean DEFAULT_ENABLED = false;

    @Id
    private String id;

    /** cron 表达式（6 段式）。由前端文本框维护，保存时校验合法性 */
    private String cron;

    /** 是否启用定时触发。「立即执行」不受它影响 */
    private Boolean enabled;

    /** 最近一次执行的开始时间 */
    private Date lastRunAt;

    /** 最近一次执行的结束时间 */
    private Date lastRunFinishedAt;

    /** 最近一次执行的状态，取值见 {@link RefreshStatus} */
    private String lastRunStatus;

    /** 最近一次执行的触发方式：MANUAL（手动「立即执行」）/ SCHEDULED（cron） */
    private String lastRunTrigger;

    /** 最近一次执行的一句话总结，直接展示给用户 */
    private String lastRunMessage;

    /**
     * 最近一次执行里各目标的结果。
     *
     * <p>⚠️ 只包含<b>实际尝试过</b>的目标 —— 中途被占用而中止时，
     * 中止点<b>之后</b>的目标不会出现在这里（它们压根没跑）。
     * 所以数组长度不一定是 8。
     */
    private List<RefreshDetail> lastDetails;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
