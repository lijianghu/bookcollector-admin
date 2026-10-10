package com.bookcollector.task.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 一次任务运行记录（含实时进度）。
 *
 * <p><b>为什么不把进度直接写在 {@link CollectTask} 上</b>：任务可以被反复运行，
 * 每次运行的进度需要独立留痕（「上一次跑到第 37 页失败」这件事本身有价值）。
 * 拆开后任务定义是静态的，运行记录是流水，职责清晰。
 *
 * <p><b>写放大控制</b>：进度<b>每页更新一次</b>，不是每本书更新一次。
 * 一页 20 本书，每本都写一次 = 20 倍写放大，而进度精度并没有实际收益。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "collect_task_runs")
public class TaskRun implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 所属任务 ID */
    private String taskId;

    /** 任务名快照（任务被改名/删除后，历史记录仍可读） */
    private String taskName;

    /** 采集目标类型：CATEGORY / RANKING */
    private String targetType;

    /** 采集目标 ID */
    private String targetId;

    /** 目标名称快照 */
    private String targetName;

    private Date startedAt;

    /** 结束时间。未结束时为 null */
    private Date finishedAt;

    /** 运行状态，取值见 {@link com.bookcollector.common.enums.TaskStatus} */
    private String status;

    /** 已完成的页数 */
    private Integer pagesDone;

    /** 累计落库/更新的图书数（upsert 计数，含重复更新的） */
    private Long booksSaved;

    /**
     * 其中<b>新增</b>的图书数（库里原本没有这个 {@code bookId}）。
     *
     * <p>{@link #booksSaved} <b>包含</b>它 —— 更新数 = {@code booksSaved - booksInserted}。
     * 榜单类目标一次会把整期（200 本）重写一遍，其中绝大多数是老书，
     * 只看 {@code booksSaved} 会严重高估「这次采回来多少新书」。
     *
     * <p>数据来自 {@code BookRepository.upsertMany} 返回的
     * {@code book.dto.UpsertStat#getInserted()}，经 {@code CollectResult.booksInserted} 上来。
     */
    private Long booksInserted;

    /**
     * 其中因命中任务的「排除分类」而<b>没写库</b>的图书数。
     *
     * <p>它<b>不</b>包含在 {@link #booksSaved} 里 —— 被排除的书压根没写。
     * 单独记一笔是因为「采回来 20 本、只入库 13 本」在界面上看起来像丢了数据，
     * 有这个数才能把差额解释清楚。没配排除分类的任务恒为 0。
     *
     * <p>数据来自 {@code collector.BookCategoryFilter} 的过滤差额，
     * 经 {@code CollectResult.booksExcluded} 上来。
     */
    private Long booksExcluded;

    /** 当前游标值（= 下一页要用的 maxIndex） */
    private Integer currentCursor;

    /** 接口返回的该目标总书数，用于算进度百分比 */
    private Integer totalCount;

    /**
     * 最近一次进度刷新时间。
     * <b>这是「任务是否假死」的唯一可靠判据</b>：页面轮询时如果
     * {@code now - lastProgressAt > 阈值}，说明卡住了，而不是在慢慢跑。
     */
    private Date lastProgressAt;

    /** 失败原因。成功/取消时为 null */
    private String errorMsg;
}
