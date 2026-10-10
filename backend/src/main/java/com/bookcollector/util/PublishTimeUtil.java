package com.bookcollector.util;

/**
 * 出版时间区间查询的<b>上界补位</b>规则。
 *
 * <h3>为什么需要「补位」</h3>
 * 用户填 {@code "2023"} 想表达的是「2023 年全年」，但 {@code publishTime} 在库里是
 * 定长零填充的字符串（{@code "2022-08-01 00:00:00"}），直接拿 {@code "2023"} 做
 * {@code $lte} 会把 {@code "2023-08-01 00:00:00"} <b>排除掉</b>
 * （因为字典序上 {@code "2023" < "2023-08-01"}）。补成
 * {@code "2023-12-31 23:59:59"} 才正确覆盖全年。
 *
 * <p>⚠️ 用<b>字符串比较</b>而不是日期解析：{@code publishTime} 库里本来就有脏值
 * （如只有 {@code "2022"} 这种），转 {@code Date} 会引入解析失败，收益为零。
 *
 * <h3>⚠️ {@code 2023-06-31} 是个不存在的日期</h3>
 * 没关系 —— 我们做的是字符串比较不是日期比较，{@code "-31 23:59:59"} 只起
 * 「该月最大值」的作用，不会被解析成日期。所以这里不需要区分大小月。
 *
 * <h3>为什么单独抽出来</h3>
 * 这条规则同时被「图书库」和「发布配置的候选清单」用到。两处各写一遍的话，
 * 哪天补位口径变了（比如要支持 {@code "2023-Q1"}），必然改一处漏一处 ——
 * 而漏掉的那处不报错，只是筛出来的结果少几条。
 */
public final class PublishTimeUtil {

    private PublishTimeUtil() {
        throw new AssertionError("PublishTimeUtil 工具类不允许实例化");
    }

    /**
     * 把「粗粒度」的上界输入补齐成可直接做字符串比较的形式。
     *
     * <table border="1">
     *   <tr><th>输入</th><th>补成</th><th>含义</th></tr>
     *   <tr><td>{@code 2023}</td><td>{@code 2023-12-31 23:59:59}</td><td>2023 全年</td></tr>
     *   <tr><td>{@code 2023-06}</td><td>{@code 2023-06-31 23:59:59}</td><td>2023 年 6 月</td></tr>
     *   <tr><td>其它</td><td>原样</td><td>已经是完整时间，不再猜</td></tr>
     * </table>
     *
     * @return 输入为 {@code null} / 空白时返回 {@code null}（表示「不筛上界」）
     */
    public static String padUpperBound(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() == 4) {
            return v + "-12-31 23:59:59";
        }
        if (v.length() == 7) {
            return v + "-31 23:59:59";
        }
        return v;
    }
}
