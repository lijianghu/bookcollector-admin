package com.bookcollector.publish.req;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「精选配置」的筛选条件（2026-10-12 新增）。
 *
 * <h3>一个请求体，两个用途</h3>
 * <ul>
 *   <li>{@code POST /api/publish/candidates/preview} —— 只看不写，返回分页列表；</li>
 *   <li>{@code POST /api/publish/candidates/collect} —— 按同一套条件把书<b>写进候选清单</b>。</li>
 * </ul>
 * 两者共用一份条件，是为了让「预览到什么，精选的就是什么」在结构上成立 ——
 * 拆成两个请求体的话，以后改一个忘一个，预览与写入的结果就会悄悄不一致。
 *
 * <h3>与 {@link InterpretationQuery} 的区别</h3>
 * 那个查的是 {@code book_interpretations}（候选清单，是图书库的<b>快照</b>），
 * 这个查的是 {@code books}（图书库本体）。字段名一样（快照字段名与图书库一致），
 * 但语义完全不同：前者回答「清单里有什么」，后者回答「图书库里哪些书符合条件」。
 *
 * <h3>🔴 推荐值的单位是百分制</h3>
 * 前端输入框填的是 {@code 0~100}（与列表里显示的「91.3%」同口径），
 * 而库里 {@code books.newRating} 存的是 {@code 0~1000}（采集时已 ×10）。
 * 换算<b>放在后端</b>（见 {@link #resolveMinRatingValue()}）：
 * 交给前端换算的话，哪一处忘了乘 10 就会静默筛错一个数量级 —— 这种错很难看出来。
 */
@Schema(description = "精选配置的筛选条件（预览与精选共用）")
public class CandidateBuildRequest {

    /** 推荐值换算比例：百分制 → 库值 */
    public static final int RATING_SCALE = 10;

    /** 推荐值百分制上限 */
    public static final int RATING_PERCENT_MAX = 100;

    @Schema(description = "平台分类（一级）code 列表，必填非空。匹配 books.categories[].categoryId")
    private List<Integer> categoryIds;

    @Schema(description = "书单 ID。传了它 = 书单模式：忽略平台分类 / 评价标签 / 推荐值 / 书名作者")
    private String booklistId;

    @Schema(description = "评价标签（books.newRatingTitle），如 [神作, 好评如潮]")
    private List<String> ratingTitles;

    @Schema(description = "推荐值下限（百分制 0~100，含）。后端 ×10 后与 books.newRating 比较")
    private Integer minRating;

    @Schema(description = "书名关键词，包含匹配（模糊搜索）")
    private String title;

    @Schema(description = "作者关键词，包含匹配（模糊搜索）")
    private String author;

    @Schema(description = "隐藏已精选：把 book_interpretations 里已有的 bookId 排除掉")
    private Boolean excludeCollected;

    @Schema(description = "true = 全部精选（忽略 bookIds，按筛选条件全量写入候选清单）")
    private Boolean all;

    @Schema(description = "批量精选：当页勾选的 bookId 列表")
    private List<String> bookIds;

    // ------------------------------------------------------------------
    // 归一化
    // ------------------------------------------------------------------

    /** 平台分类：{@code null} / 空 → 空列表（调用方据此判定「没选分类」） */
    public List<Integer> resolveCategoryIds() {
        List<Integer> out = new ArrayList<Integer>();
        if (categoryIds != null) {
            for (Integer id : categoryIds) {
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** 书单 ID，空白归一成 {@code null} */
    public String resolveBooklistId() {
        return blankToNull(booklistId);
    }

    /** 评价标签：去掉空白项、去重（保序）。空 → 空列表 */
    public List<String> resolveRatingTitles() {
        List<String> out = new ArrayList<String>();
        if (ratingTitles != null) {
            Set<String> seen = new LinkedHashSet<String>();
            for (String t : ratingTitles) {
                String v = blankToNull(t);
                if (v != null && seen.add(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /**
     * 推荐值下限，<b>换算成库值</b>（百分制 ×10）。
     *
     * <p>{@code null} = 不筛这一项。负数按 0 处理，超过 100 按 100 处理 ——
     * 夹取而不是报错：输入框里多打一个 0 是很常见的手滑，
     * 夹到边界至少能得到一个可解释的结果（而不是静默筛出 0 条）。
     */
    public Integer resolveMinRatingValue() {
        if (minRating == null) {
            return null;
        }
        int v = minRating;
        if (v < 0) {
            v = 0;
        }
        if (v > RATING_PERCENT_MAX) {
            v = RATING_PERCENT_MAX;
        }
        return v * RATING_SCALE;
    }

    /** 书名关键词，空白归一成 {@code null} */
    public String resolveTitle() {
        return blankToNull(title);
    }

    /** 作者关键词，空白归一成 {@code null} */
    public String resolveAuthor() {
        return blankToNull(author);
    }

    /** 是否隐藏已精选（{@code null} 视为「不隐藏」—— 只有显式传 true 才排除） */
    public boolean excludeCollected() {
        return Boolean.TRUE.equals(excludeCollected);
    }

    /** 是否「全部精选」 */
    public boolean all() {
        return Boolean.TRUE.equals(all);
    }

    /** 批量精选的 bookId：去掉空白项。空 → 空列表 */
    public List<String> resolveBookIds() {
        List<String> out = new ArrayList<String>();
        if (bookIds != null) {
            for (String id : bookIds) {
                String v = blankToNull(id);
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /**
     * 是否至少给出一个筛选条件（任一非空即可）。
     *
     * <p>不含 {@code excludeCollected} ——「隐藏已精选」是收窄项，单独勾上它等于全库。
     */
    public boolean hasAnyFilter() {
        return !resolveCategoryIds().isEmpty()
                || !resolveRatingTitles().isEmpty()
                || resolveMinRatingValue() != null
                || resolveTitle() != null
                || resolveAuthor() != null
                || resolveBooklistId() != null;
    }

    // ------------------------------------------------------------------
    // getter / setter
    // ------------------------------------------------------------------

    public List<Integer> getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(List<Integer> categoryIds) {
        this.categoryIds = categoryIds;
    }

    public String getBooklistId() {
        return booklistId;
    }

    public void setBooklistId(String booklistId) {
        this.booklistId = booklistId;
    }

    public List<String> getRatingTitles() {
        return ratingTitles;
    }

    public void setRatingTitles(List<String> ratingTitles) {
        this.ratingTitles = ratingTitles;
    }

    public Integer getMinRating() {
        return minRating;
    }

    public void setMinRating(Integer minRating) {
        this.minRating = minRating;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Boolean getExcludeCollected() {
        return excludeCollected;
    }

    public void setExcludeCollected(Boolean excludeCollected) {
        this.excludeCollected = excludeCollected;
    }

    public Boolean getAll() {
        return all;
    }

    public void setAll(Boolean all) {
        this.all = all;
    }

    public List<String> getBookIds() {
        return bookIds;
    }

    public void setBookIds(List<String> bookIds) {
        this.bookIds = bookIds;
    }

    private static String blankToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }
}
