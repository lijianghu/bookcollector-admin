package com.bookcollector.refresh.vo;

import com.bookcollector.refresh.RefreshTarget;
import com.bookcollector.refresh.entity.RefreshDetail;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 增量配置页面的<b>全部数据</b>，一个接口拿齐。
 *
 * <h3>为什么把「配置」和「最近一次结果」合成一个 VO</h3>
 * 页面上它们是一个整体（上半配置区、下半结果区），分成两个接口的话前端要处理
 * 「两个请求一个成功一个失败」的中间态，而这里的数据量只有几 KB，没必要省。
 *
 * <h3>为什么把 {@code targets} 也带上</h3>
 * 前端要展示「这次会刷新哪些目标」，而清单是后端写死的
 * （见 {@code RefreshTarget.ALL}）。让前端自己维护一份副本，
 * 迟早会和后端不一致 —— 那正是最难查的一类 bug（页面上显示刷 8 个，实际刷 7 个）。
 *
 * <h3>{@code running} 为什么来自内存而不是 DB</h3>
 * 「批次是不是正在跑」的唯一权威是 {@code RefreshServiceImpl} 里的
 * {@code AtomicBoolean}。DB 里的 {@code lastRunStatus=RUNNING} 只是一个快照，
 * 进程被杀之后它就是假的（要靠启动时清理修正）。所以页面刷新后要不要继续轮询，
 * 得看内存态。
 */
@Data
@Builder
public class RefreshJobVO {

    // ---- 配置 ----

    /** cron 表达式（6 段式） */
    private String cron;

    /** 是否启用定时触发 */
    private Boolean enabled;

    /**
     * 下一次触发时间。
     *
     * <p>{@code null} 有两种情况：定时未启用，或 cron 算不出下一次（理论上不会，
     * 保存时已校验）。前端显示「未启用」或「—」。
     */
    private Date nextFireTime;

    // ---- 内存态 ----

    /** 当前是否有批次正在执行（权威来源是内存里的 AtomicBoolean） */
    private Boolean running;

    // ---- 目标清单 ----

    /** 固定的 8 个刷新目标 */
    private List<RefreshTarget> targets;

    // ---- 最近一次执行 ----

    private Date lastRunAt;

    private Date lastRunFinishedAt;

    /** 取值见 {@code RefreshStatus}。从未执行过时是 IDLE */
    private String lastRunStatus;

    /** 状态的展示文案（「成功」/「已中止」…），省得前端再维护一张映射表 */
    private String lastRunStatusLabel;

    /** MANUAL / SCHEDULED */
    private String lastRunTrigger;

    /** 一句话总结 */
    private String lastRunMessage;

    /** 最近一次执行里成功的目标数（从 lastDetails 现算，不落库，避免两处不一致） */
    private Integer lastRunSuccess;

    /** 最近一次执行里失败/取消的目标数 */
    private Integer lastRunFailed;

    /**
     * 各目标的结果。
     *
     * <p>⚠️ 长度不一定是 8：批次中途被占用而中止时，
     * 中止点之后的目标压根没跑，不会出现在这里。
     */
    private List<RefreshDetail> lastDetails;
}
