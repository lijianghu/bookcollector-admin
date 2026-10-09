package com.bookcollector.publish.vo;

import com.bookcollector.publish.entity.Interpretation;
import com.bookcollector.publish.entity.PublishStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

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
        vo.setErrorMsg(it.getErrorMsg());
        vo.setTrigger(it.getTrigger());
        return vo;
    }
}
