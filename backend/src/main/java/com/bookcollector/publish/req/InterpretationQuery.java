package com.bookcollector.publish.req;

import com.bookcollector.util.PublishTimeUtil;
import com.bookcollector.util.RegexUtil;

import lombok.Data;

import java.util.regex.Pattern;

/**
 * 候选清单（{@code book_interpretations}）的查询条件。
 *
 * <h3>🔴 为什么不复用 {@code book.req.BookQuery}</h3>
 * {@code book_interpretations} 是 {@code books} 的<b>完整快照</b>，字段名与 {@code books}
 * 一致，所以「技术上」可以直接复用 {@code BookQuery}。但本项目刻意让
 * {@code book} 与 {@code publish} 两个包互不 import（见 {@code InterpretationRepository}
 * 里「写死字面量：跨模块引用会形成不必要的耦合」那条）——
 * 一旦复用，以后改图书库的查询口径会<b>顺带改掉候选清单的行为</b>，
 * 而这种影响在 code review 里几乎看不出来。
 *
 * <p>所以这里是一份<b>有意的副本</b>：只保留候选清单真正用得上的条件
 * （状态 / 书名 / 作者 / 平台分类 / 推荐值区间 / 阅读人数下限 / 出版时间区间）。
 * <b>刻意不含「采集来源」</b>（{@code targetType} + {@code targetId}）：
 * 候选清单里的书是「被挑出来的候选」，它的采集来源对「找出某本书并删掉它」
 * 这个使用场景没有价值，为它多加载一份分类配置字典不划算。
 * 需要的话再补 —— 字段与图书库同名，加进来成本很低。
 *
 * <h3>字段名与 {@code books} / {@code BookQuery} 保持一致</h3>
 * {@code title} / {@code author} / {@code newRating} / {@code newRatingCount} /
 * {@code publishTime} 都是快照里的原名。{@code categoryId} 匹配的是
 * {@code categories.categoryId}（<b>数组内路径</b>，不是顶层字段）。
 */
@Data
public class InterpretationQuery {

    /** 每页最大条数，防止前端传 {@code size=100000} 把服务打爆 */
    public static final int MAX_SIZE = 200;

    /** 默认每页条数 */
    public static final int DEFAULT_SIZE = 20;

    /**
     * 解读状态：{@code PENDING} / {@code GENERATING} / {@code PUBLISHED} / {@code FAILED}。
     * 空白 = 全部。
     */
    private String status;

    /**
     * 书名关键词，<b>包含匹配</b>（模糊搜索）。
     *
     * <p>⚠️ 未锚定的正则用不上索引，这个条件会让查询退化成全集合扫描。
     * 候选清单只有几千条，可以接受（同 {@code BookQuery} 的口径）。
     */
    private String title;

    /** 作者关键词，<b>包含匹配</b>（模糊搜索） */
    private String author;

    /**
     * 按平台分类 ID 精确筛（匹配快照里的 {@code categories.categoryId}）。
     *
     * <p>⚠️ 只传<b>一级分类</b>的 code —— 二级分类的 code 是 {@code subCategoryId}，
     * 是另一个字段，传进来恒不匹配且<b>静默返回 0 条</b>。这条坑与图书库同源。
     */
    private Integer categoryId;

    /** 推荐值下限（含）。路径是顶层 {@code newRating}（0~1000） */
    private Integer minRating;

    /** 推荐值上限（含） */
    private Integer maxRating;

    /** 阅读人数下限（含） */
    private Integer minReadingCount;

    /** 出版时间下限，形如 {@code "2020"} 或 {@code "2020-06"}。字符串比较，见 PublishTimeUtil */
    private String minPublishTime;

    /** 出版时间上限，形如 {@code "2023-12"}。传 {@code "2023"} 时语义是「2023 年全年」 */
    private String maxPublishTime;

    /** 页码，从 1 开始 */
    private int page = 1;

    /** 每页条数 */
    private int size = DEFAULT_SIZE;

    // ------------------------------------------------------------------
    // 归一化
    // ------------------------------------------------------------------

    /** 页码至少为 1 */
    public int getSafePage() {
        return page < 1 ? 1 : page;
    }

    /** 每页条数夹在 [1, MAX_SIZE] */
    public int getSafeSize() {
        if (size < 1) {
            return DEFAULT_SIZE;
        }
        return size > MAX_SIZE ? MAX_SIZE : size;
    }

    /** 状态：空白归一成 null（= 不筛状态）。统一转大写，与库里的枚举名对齐 */
    public String resolveStatus() {
        String v = blankToNull(status);
        return v == null ? null : v.toUpperCase();
    }

    /** 书名关键词。空白串归一成 null */
    public String resolveTitle() {
        return blankToNull(title);
    }

    /** 作者关键词。空白串归一成 null */
    public String resolveAuthor() {
        return blankToNull(author);
    }

    /** 出版时间下限。空白串归一成 null */
    public String resolveMinPublishTime() {
        return blankToNull(minPublishTime);
    }

    /** 出版时间上限，粗粒度输入补齐成可比较形式（同图书库的口径） */
    public String resolveMaxPublishTime() {
        return PublishTimeUtil.padUpperBound(maxPublishTime);
    }

    // ------------------------------------------------------------------
    // 正则
    // ------------------------------------------------------------------

    /** 书名「包含匹配」正则。关键词为空时返回 null（调用方据此跳过该条件） */
    public Pattern titlePattern() {
        return RegexUtil.contains(resolveTitle());
    }

    /** 作者「包含匹配」正则。关键词为空时返回 null */
    public Pattern authorPattern() {
        return RegexUtil.contains(resolveAuthor());
    }

    private String blankToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }
}
