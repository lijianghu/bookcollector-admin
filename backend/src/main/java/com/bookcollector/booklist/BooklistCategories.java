package com.bookcollector.booklist;

import com.bookcollector.booklist.entity.Booklist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 「分类书单」要遍历的平台分类清单（<b>固定 16 个</b>）。
 *
 * <h3>为什么是代码常量，而不是从接口响应里读</h3>
 * 接口响应里确实带一个 {@code categories[]}，但它的内容<b>随请求而变</b>
 * （抓包样例里是 15 个，不含 {@code topic}）—— 拿它当遍历源会漏掉分类，
 * 而漏掉的表现只是「某些分类的书单没同步到」，没有任何报错。
 *
 * <p>用户 2026-10-14 明确给出这份清单并说明「<b>只有这些分类</b>」，
 * 所以它是<b>业务事实</b>，写死在代码里反而更可控：改分类就是改这里一处。
 * （同 {@code refresh/RefreshTarget} 的 8 个固定目标，是同一个做法。）
 *
 * <h3>⚠️ {@code categoryId} 是字符串，且第一个不是数字</h3>
 * {@code "topic"} 是「主题书单」，其余 15 个是数字串。
 * <b>不要</b>把它转成 int 去和 {@code books.categories[].categoryId}（那是 int）比 ——
 * 本项目在「字符串 vs int」上踩过「恒 0 条、不报错」的坑。
 *
 * <h3>顺序</h3>
 * 按用户给的顺序（{@code topic} 在最前）。同步链路按这个顺序逐个分类跑，
 * 页面上「当前分类」的展示与它一致。
 */
public final class BooklistCategories {

    /** 「主题书单」的分类 ID。它不是数字 */
    public static final String TOPIC = "topic";

    private static final List<Booklist.PlatformCategory> ALL = Collections.unmodifiableList(
            Arrays.asList(
                    new Booklist.PlatformCategory(TOPIC, "主题书单"),
                    new Booklist.PlatformCategory("100000", "精品小说"),
                    new Booklist.PlatformCategory("1100000", "经济理财"),
                    new Booklist.PlatformCategory("300000", "文学"),
                    new Booklist.PlatformCategory("1000000", "个人成长"),
                    new Booklist.PlatformCategory("200000", "历史"),
                    new Booklist.PlatformCategory("900000", "社会文化"),
                    new Booklist.PlatformCategory("600000", "哲学宗教"),
                    new Booklist.PlatformCategory("800000", "心理"),
                    new Booklist.PlatformCategory("1600000", "生活百科"),
                    new Booklist.PlatformCategory("1500000", "科学技术"),
                    new Booklist.PlatformCategory("1200000", "政治军事"),
                    new Booklist.PlatformCategory("1400000", "教育学习"),
                    new Booklist.PlatformCategory("400000", "艺术"),
                    new Booklist.PlatformCategory("700000", "计算机"),
                    new Booklist.PlatformCategory("2100000", "医学健康")));

    private BooklistCategories() {
    }

    /** 全部 16 个分类，<b>不可变</b> */
    public static List<Booklist.PlatformCategory> all() {
        return ALL;
    }

    /** 按分类 ID 查一个。认不出返回 {@code null} */
    public static Booklist.PlatformCategory find(String categoryId) {
        if (categoryId == null) {
            return null;
        }
        String v = categoryId.trim();
        for (Booklist.PlatformCategory c : ALL) {
            if (c.getCategoryId().equals(v)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 把前端勾选的分类 ID 列表解析成分类对象 —— <b>同步链路的入参校验口</b>。
     *
     * <h3>三种入参 → 三种结果</h3>
     * <table border="1">
     *   <tr><th>入参</th><th>返回</th></tr>
     *   <tr><td>{@code null} / 空集合 / 全是空白串</td><td>全部 16 个（= 不筛，一键全量）</td></tr>
     *   <tr><td>全是清单内的 ID</td><td>命中的那几个，<b>按 {@link #ALL} 的固定顺序</b>排（不是入参顺序）</td></tr>
     *   <tr><td>含清单外的 ID</td><td><b>抛 {@link IllegalArgumentException}</b>，消息里列出认不出的 ID</td></tr>
     * </table>
     *
     * <p><b>为什么按固定顺序而不是入参顺序</b>：同步是逐个分类串行跑的，
     * 顺序决定「页面上当前分类」的推进方向。固定顺序让「同一个选择跑两次」
     * 的进度表现完全一致，也便于对着日志复盘。
     *
     * <p><b>为什么认不出就抛异常</b>：静默忽略会表现成
     * 「勾了 5 个，实际只同步了 3 个，没有任何报错」。
     * 这个方法的返回值同时也是进度里的 {@code totalCategories}，
     * 静默忽略会让「3/3 完成」看起来完全正常。
     *
     * @throws IllegalArgumentException 入参里有清单外的分类 ID
     */
    public static List<Booklist.PlatformCategory> byIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return ALL;
        }

        // 先去重（用户可能重复勾选 / 前端重复提交），再去空白
        List<String> wanted = new ArrayList<String>();
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            String v = id.trim();
            if (!v.isEmpty() && !wanted.contains(v)) {
                wanted.add(v);
            }
        }
        if (wanted.isEmpty()) {
            return ALL;
        }

        List<String> unknown = new ArrayList<String>();
        for (String id : wanted) {
            if (find(id) == null) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("未知的平台分类 ID：" + unknown
                    + "。可用的分类 ID：" + idsOfAll());
        }

        // 按 ALL 的固定顺序输出（见方法注释）
        List<Booklist.PlatformCategory> picked = new ArrayList<Booklist.PlatformCategory>();
        for (Booklist.PlatformCategory c : ALL) {
            if (wanted.contains(c.getCategoryId())) {
                picked.add(c);
            }
        }
        return Collections.unmodifiableList(picked);
    }

    /** 全部可用分类 ID，形如 {@code [topic, 100000, ...]}。拼错误消息用 */
    public static List<String> idsOfAll() {
        List<String> ids = new ArrayList<String>(ALL.size());
        for (Booklist.PlatformCategory c : ALL) {
            ids.add(c.getCategoryId());
        }
        return Collections.unmodifiableList(ids);
    }
}
