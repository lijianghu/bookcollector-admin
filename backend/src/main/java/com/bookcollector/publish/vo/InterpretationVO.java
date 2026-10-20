package com.bookcollector.publish.vo;

import com.bookcollector.publish.entity.Interpretation;
import com.bookcollector.publish.entity.PublishStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 候选清单列表的一行。
 *
 * <h3>🔴 为什么不让 {@code Interpretation} 实体直出</h3>
 * 本项目一般允许 Entity 直出（见 {@code backend/AGENTS.md}），但这里不行：
 * {@code Interpretation.content} / {@code rawContent} 是几千字的正文，
 * 一页 20 条就是几十 KB 的无用负载。所以列表<b>必须</b>走这个 VO 收敛掉它们。
 *
 * <p>详情要看正文时另说 —— 当前页面不需要，列表也不提供正文预览。
 */
@Data
@Schema(description = "候选清单的一行（不含正文）")
public class InterpretationVO {

    @Schema(description = "Mongo 主键")
    private String id;

    @Schema(description = "图书 ID")
    private String bookId;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "分类，如 精品小说-社会小说")
    private String category;

    @Schema(description = "推荐值 0~1000")
    private Integer newRating;

    @Schema(description = "评价人数")
    private Integer newRatingCount;

    @Schema(description = "阅读人数")
    private Integer readingCount;

    // ---- 评分明细（来自快照里的 newRatingDetail）----
    // 2026-10-13 加：列表要展示，并且四列都可排序（排序在 Repository 里按
    // newRatingDetail.good / .fair / .poor 这三个路径做）

    @Schema(description = "好评数（newRatingDetail.good）")
    private Integer goodCount;

    @Schema(description = "中评数（newRatingDetail.fair）")
    private Integer fairCount;

    @Schema(description = "差评数（newRatingDetail.poor）")
    private Integer poorCount;

    // ---- 状态 ----

    @Schema(description = "PENDING / GENERATING / PUBLISHED / FAILED")
    private String status;

    @Schema(description = "状态的中文文案，省得前端再维护一张映射表")
    private String statusLabel;

    @Schema(description = "进入候选清单的时间")
    private Date initializedAt;

    @Schema(description = "本次生成的开始时间")
    private Date startedAt;

    @Schema(description = "本次生成的结束时间")
    private Date finishedAt;

    // ---- 产物 ----

    @Schema(description = "本地 Markdown 文件路径")
    private String localPath;

    @Schema(description = "语雀文档 ID")
    private Long yuqueDocId;

    @Schema(description = "语雀文档 URL")
    private String yuqueUrl;

    @Schema(description = "本次使用的模型名")
    private String model;

    @Schema(description = "命中的敏感词个数")
    private Integer sensitiveHitCount;

    /**
     * 本次命中的敏感词<b>原词列表</b>（2026-10-13 加）。
     *
     * <p>页面上「合规替换」那一列点开要看到「替换了哪些词」—— 只有个数不够用。
     * 数据源是 {@code Interpretation.sensitiveHits}（替换时就已经写进快照了）。
     *
     * <p>⚠️ 它与 {@link #sensitiveHitCount} 是<b>同一个事实的两种粒度</b>：
     * 个数 = 列表长度。两个一起返回，前端不用自己算。
     *
     * <p>⚠️ 清单里一本书<b>只有一行</b>，所以这个列表是<b>最近一次发布</b>的命中词。
     * 要看历史某一次的，去「发布记录」页（那里是流水账，每条带自己的 sensitiveHits）。
     */
    private List<String> sensitiveHits;

    @Schema(description = "失败原因（成功时为 null）")
    private String errorMsg;

    @Schema(description = "触发方式：MANUAL / SCHEDULED")
    private String trigger;

    /**
     * 由实体转 VO。
     *
     * <p>⚠️ 刻意<b>不</b>拷贝 {@code content} / {@code rawContent} —— 那两个字段
     * 根本没在 VO 里声明，所以「忘了排除」这件事在这里是编译期就挡住的。
     */
    public static InterpretationVO from(Interpretation it) {
        if (it == null) {
            return null;
        }
        InterpretationVO vo = new InterpretationVO();
        vo.setId(it.getId());
        vo.setBookId(it.getBookId());
        vo.setTitle(it.getTitle());
        vo.setAuthor(it.getAuthor());
        vo.setCategory(it.getCategory());
        vo.setNewRating(it.getNewRating());
        vo.setNewRatingCount(it.getNewRatingCount());
        vo.setReadingCount(it.getReadingCount());
        // 评分明细：快照里的子文档，取用时一律走 asInt 兜底 ——
        // 缺字段 / null / 上游给了字符串都可能，展示成「-」比抛异常合理
        Map<String, Object> detail = it.getNewRatingDetail();
        vo.setGoodCount(asInt(detail == null ? null : detail.get("good")));
        vo.setFairCount(asInt(detail == null ? null : detail.get("fair")));
        vo.setPoorCount(asInt(detail == null ? null : detail.get("poor")));
        vo.setStatus(it.getStatus());
        vo.setStatusLabel(PublishStatus.of(it.getStatus()).getLabel());
        vo.setInitializedAt(it.getInitializedAt());
        vo.setStartedAt(it.getStartedAt());
        vo.setFinishedAt(it.getFinishedAt());
        vo.setLocalPath(it.getLocalPath());
        vo.setYuqueDocId(it.getYuqueDocId());
        vo.setYuqueUrl(it.getYuqueUrl());
        vo.setModel(it.getModel());
        vo.setSensitiveHitCount(it.getSensitiveHits() == null ? null : it.getSensitiveHits().size());
        vo.setSensitiveHits(it.getSensitiveHits());
        vo.setErrorMsg(it.getErrorMsg());
        vo.setTrigger(it.getTrigger());
        return vo;
    }

    /**
     * 宽松取整数。
     *
     * <p>快照里的 {@code newRatingDetail} 是上游 JSON 原样存下来的，值的实际类型不可控
     * （{@code Integer} / {@code Long} / 字符串都出现过）。取不到就返回 {@code null}
     * —— 页面显示成「-」，比抛 {@code ClassCastException} 把整个列表接口打挂合理。
     */
    private static Integer asInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return Integer.valueOf(((Number) v).intValue());
        }
        try {
            return Integer.valueOf(Integer.parseInt(String.valueOf(v).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
