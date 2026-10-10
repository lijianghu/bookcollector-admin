package com.bookcollector.util;

import java.util.regex.Pattern;

/**
 * 模糊搜索用的正则工具。
 *
 * <h3>为什么单独放一个类</h3>
 * 「书名 / 作者包含匹配」这个需求同时出现在 {@code book} 与 {@code publish} 两个模块
 * （图书库、候选清单）。两处各写一遍转义逻辑，迟早有一处漏掉
 * {@link Pattern#quote} —— 那是个<b>静默</b>的问题：用户输入 {@code "("} 之类的字符时
 * 正则编译直接抛异常，接口 500，而写的时候完全看不出来。
 *
 * <h3>🔴 性能口径（2026-10-10 用户拍板）</h3>
 * 未锚定的正则（{@code /关键词/}）<b>用不上索引</b>，MongoDB 只能全集合扫描。
 * 本项目此前刻意<b>不做</b>模糊搜索，理由正是这个。
 * 2026-10-10 用户明确要求「书名 / 作者都要模糊搜索」，所以这里按需求实现，
 * 代价是这两个条件会把查询退化成全表扫 —— 单机、几万条数据量下可以接受，
 * 但<b>不要</b>再往其他字段上扩散。
 */
public final class RegexUtil {

    private RegexUtil() {
        throw new AssertionError("RegexUtil 工具类不允许实例化");
    }

    /**
     * 把用户输入的关键词编译成「<b>包含</b>匹配 + 大小写不敏感」的正则。
     *
     * <p>{@link Pattern#quote} 把关键词里的元字符（{@code . * + ? ( ) [ ] { } | ^ $ \}）
     * 全部转义成字面量 —— 用户搜 {@code "C++"} 就该搜到含 {@code "C++"} 的书名，
     * 而不是被当成正则去匹配 {@code "C"}。
     *
     * <p>不做 {@code ^...$} 锚定，所以是「包含」语义；也因此用不上索引（见类注释）。
     *
     * @return 关键词为 {@code null} / 空白时返回 {@code null}，表示「不筛这一项」。
     *         调用方据此跳过该条件，而不是拼一个空正则（空正则会匹配所有文档，
     *         看起来「筛了」，实际等于没筛）
     */
    public static Pattern contains(String keyword) {
        if (keyword == null) {
            return null;
        }
        String v = keyword.trim();
        if (v.isEmpty()) {
            return null;
        }
        return Pattern.compile(Pattern.quote(v), Pattern.CASE_INSENSITIVE);
    }
}
