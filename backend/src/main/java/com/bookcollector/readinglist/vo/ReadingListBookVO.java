package com.bookcollector.readinglist.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 阅读清单对外返回的一本书。
 *
 * <p>是 {@code readinglist.entity.ReadingListItem} 的对外投影：暴露 9 个展示快照字段
 * + 阅读状态三件套（{@code status} / {@code statusLabel} / {@code statusUpdatedAt}），
 * 不吐内部的 {@code _id} / {@code addedAt}。
 *
 * <p>留一层 VO 而不是实体直出：把<b>接口形状</b>与<b>存储</b>解耦（同 {@code ShelfBookVO}）。
 */
@Data
@Schema(description = "阅读清单里的一本书")
public class ReadingListBookVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "图书 ID")
    private String bookId;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者（可能是「作者 + 译者/编者」）")
    private String author;

    @Schema(description = "封面 URL")
    private String cover;

    @Schema(description = "平台分类，如 文学-经典作品")
    private String category;

    @Schema(description = "推荐值 0~1000（913 = 91.3%），不做换算")
    private Integer newRating;

    @Schema(description = "评价人数")
    private Integer newRatingCount;

    @Schema(description = "阅读人数")
    private Integer readingCount;

    @Schema(description = "官方简介（详情面板用）")
    private String intro;

    @Schema(description = "阅读状态：WANT_TO_READ / READING / FINISHED")
    private String status;

    @Schema(description = "阅读状态的中文文案（后端已给，前端不必再维护一份映射）")
    private String statusLabel;

    /** 状态最后变更时间。序列化成 UTC ISO-8601 字符串，前端必须过 {@code new Date()} 再格式化 */
    @Schema(description = "状态最后变更时间（UTC ISO-8601）")
    private Date statusUpdatedAt;
}
