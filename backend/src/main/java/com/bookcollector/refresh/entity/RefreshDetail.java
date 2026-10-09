package com.bookcollector.refresh.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 定时刷新批次里<b>单个目标</b>的执行结果。
 *
 * <p>作为 {@code RefreshJob.lastDetails} 的数组元素<b>内嵌</b>存储，不单独建集合。
 * 理由：它没有独立的查询需求（永远是「跟着批次一起读出来」），
 * 也没有跨批次的历史价值（只保留最近一次 —— 这正是「改动更小」的取舍，
 * 用户 2026-10-08 认可）。
 *
 * <p>⚠️ 只保留最近一次意味着：<b>看不到「上一次刷新新增了多少本」</b>。
 * 想知道历史趋势，应该按 {@code books.firstCollectedAt} 去统计，而不是靠这个数组。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    /** CATEGORY / RANKING */
    private String targetType;

    /** 目标 ID */
    private String targetId;

    /** 目标名快照 */
    private String targetName;

    /**
     * 结果，取值见 {@link RefreshStatus}：
     * SUCCESS / FAILED / CANCELED / ABORTED，以及<b>执行中的占位</b> RUNNING。
     *
     * <p>{@link RefreshStatus#ABORTED} 表示<b>这个目标本身没跑</b> ——
     * 它正被手动采集占用，整批在这里中止，它是被记下来的「中止点」。
     *
     * <p>{@link RefreshStatus#RUNNING} 只出现在<b>内存快照</b>里
     * （{@code RefreshServiceImpl.liveDetails}），落库的终态里不会有它。
     */
    private String status;

    /** 采完的页数。没跑成时为 null；执行中表示「已采的页数」 */
    private Integer pagesDone;

    /** 写入/更新的图书数（upsert 计数，含重复更新的） */
    private Integer booksSaved;

    /**
     * 其中<b>新增</b>的图书数（库里原本没有这个 {@code bookId}）。
     *
     * <p>用户 2026-10-08 要求「要看增量，而不是拿总数去和今日新增对比」：
     * 整期榜单（200 本）会被重写一遍，其中绝大多数是老书，
     * 只看 {@link #booksSaved} 会严重高估这次采回来多少新书。
     *
     * <p>更新数 = {@code booksSaved - booksInserted}。
     */
    private Integer booksInserted;

    /** 接口报告的该目标总书数 */
    private Integer totalCount;

    /** 耗时（毫秒） */
    private Long costMs;

    /** 说明：成功时是摘要，失败/中止时是原因 */
    private String message;
}
