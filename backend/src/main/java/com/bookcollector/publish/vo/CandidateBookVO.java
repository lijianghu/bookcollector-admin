package com.bookcollector.publish.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import org.bson.Document;

/**
 * 「精选预览」列表的一行（2026-10-12 新增）—— 图书库里的一本书，<b>未进候选清单</b>。
 *
 * <h3>🔴 为什么不复用 {@code book.entity.Book}</h3>
 * 两个原因：
 * <ol>
 *   <li>{@code publish} 与 {@code book} 两个包<b>刻意互不 import</b>
 *       （见 {@code InterpretationQuery} 的类注释）—— 一旦复用，
 *       以后改图书库的返回口径会顺带改掉精选预览的返回，而这种影响在 review 里看不出来；</li>
 *   <li>{@code Book} 有 46 个字段，其中 {@code intro} 是几千字。
 *       预览一页 20 条就是几十 KB 的无用负载（同 {@link InterpretationVO} 的取舍）。</li>
 * </ol>
 *
 * <p>所以这里只声明「挑书时要看的那几个字段」。字段名与 {@code books} 里的键<b>完全一致</b>
 * （{@code newRating} 是顶层字段，不是 {@code newRatingDetail.newRating}）。
 *
 * <h3>为什么是 {@code from(Document)} 而不是 {@code from(Book)}</h3>
 * 预览是直接对 {@code books} 集合做原始 BSON 查询（{@code Document.class} + 显式集合名），
 * 不经过任何实体映射 —— 这样也顺带避开了「{@code books} 里带 {@code _class} 字段、
 * 映射到别的实体类会出意外」这个坑。
 */
@Schema(description = "精选预览的一行（图书库快照，不含正文）")
public class CandidateBookVO {

    @Schema(description = "图书 ID")
    private String bookId;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "平台分类，如 文学-经典作品")
    private String category;

    @Schema(description = "推荐值 0~1000（913 = 91.3%）")
    private Integer newRating;

    @Schema(description = "评价人数")
    private Integer newRatingCount;

    @Schema(description = "阅读人数")
    private Integer readingCount;

    @Schema(description = "评价标签，如 神作 / 好评如潮")
    private String newRatingTitle;

    /** 由原始 BSON 文档转 VO。{@code null} 入参返回 {@code null} */
    public static CandidateBookVO from(Document doc) {
        if (doc == null) {
            return null;
        }
        CandidateBookVO vo = new CandidateBookVO();
        vo.setBookId(asString(doc.get("bookId")));
        vo.setTitle(asString(doc.get("title")));
        vo.setAuthor(asString(doc.get("author")));
        vo.setCategory(asString(doc.get("category")));
        vo.setNewRating(asInt(doc.get("newRating")));
        vo.setNewRatingCount(asInt(doc.get("newRatingCount")));
        vo.setReadingCount(asInt(doc.get("readingCount")));
        vo.setNewRatingTitle(asString(doc.get("newRatingTitle")));
        return vo;
    }

    /**
     * 宽松取字符串。
     *
     * <p>{@code bookId} 在库里是字符串，但上游偶尔给数字 —— 直接强转会在运行时抛
     * {@code ClassCastException}，而这里只要展示，转成字符串就够了。
     */
    private static String asString(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 宽松取整数。不是数字时返回 {@code null}（展示成「-」比抛异常合理） */
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

    // ------------------------------------------------------------------

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getNewRating() {
        return newRating;
    }

    public void setNewRating(Integer newRating) {
        this.newRating = newRating;
    }

    public Integer getNewRatingCount() {
        return newRatingCount;
    }

    public void setNewRatingCount(Integer newRatingCount) {
        this.newRatingCount = newRatingCount;
    }

    public Integer getReadingCount() {
        return readingCount;
    }

    public void setReadingCount(Integer readingCount) {
        this.readingCount = readingCount;
    }

    public String getNewRatingTitle() {
        return newRatingTitle;
    }

    public void setNewRatingTitle(String newRatingTitle) {
        this.newRatingTitle = newRatingTitle;
    }
}
