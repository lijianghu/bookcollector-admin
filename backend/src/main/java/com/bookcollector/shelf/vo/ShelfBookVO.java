package com.bookcollector.shelf.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 藏书阁对外返回的一本书。
 *
 * <h3>2026-10-10：数据源改成 {@code shelf_books}</h3>
 * 第一版是直接投影 {@code book_interpretations}（AI 解读候选清单）。现在藏书阁
 * 有自己的一张表 {@code shelf_books}（见 {@code shelf.entity.ShelfBook}），
 * 本 VO 是它的对外投影 —— 只暴露展示用的 9 个字段，
 * 不吐内部的 {@code _id} / {@code addedAt}。
 *
 * <p>为什么还留一层 VO 而不是实体直出：把<b>接口形状</b>与<b>存储</b>解耦。
 * 以后 {@code ShelfBook} 加内部字段（备注 / 分组…）不会顺带泄露到接口上，
 * 前端也不用跟着改。
 */
@Data
@Schema(description = "书架上的单本书")
public class ShelfBookVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "图书 ID")
    private String bookId;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者（可能是「作者 + 译者/编者」，见 REFERENCE 的 author 口径）")
    private String author;

    @Schema(description = "封面 URL。实测 CDN 无防盗链，可直连")
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
}
