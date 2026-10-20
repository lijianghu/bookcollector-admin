package com.bookcollector.booklist.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;

/**
 * 书单<b>发现</b>接口（{@code /booklist/category}）的响应信封。
 *
 * <pre>
 * {
 *   synckey:     1791953800,
 *   hasMore:     1,                 ← 还有下一页（数字，不是布尔）
 *   totalCount:  2000,              ← 该分类下书单总数
 *   categories:  [ { categoryId, title }, ... ],
 *   tags:        [ "最多收藏", ... ],
 *   datas:       [ { booklistId, name, collectCount, totalCount, ... }, ... ]
 * }
 * </pre>
 * 本类只做三件事：<b>拦错误信封</b>、<b>取 datas</b>、<b>判断还有没有下一页</b>。
 * 把 {@code datas[]} 元素映射成 {@code Booklist} 是
 * {@link com.bookcollector.booklist.BooklistParser} 的事（同
 * {@code WereadPage} / {@code BookParser} 的分工）。
 *
 * <h3>🔴 错误信封必须在这里拦下</h3>
 * 登录态失效时接口回的是 {@code {"errcode": -2012, "errmsg": "登录超时"}} ——
 * 它没有 {@code datas} 字段。不拦的话会走到「解析出 0 张书单 → 本分类没有书单 →
 * 静默跳过」，界面上显示「同步完成、新增 0 张」，而真实原因是登录态过期。
 *
 * <h3>🔴 datas 元素的形状也必须校验</h3>
 * 同一个域名下还有<b>另一种</b>响应（书单首页，抓包文件 {@code 首页书单列表 39.txt}），
 * 它的 {@code datas[]} 是「板块」（元素带 {@code content} / {@code style} / {@code type}），
 * 书单藏在更深处。两者字段名不重叠，如果拿板块响应来喂这里的解析，
 * 会得到「datas 有 18 条、但一条 booklistId 都没有」——
 * 于是「采到 0 张书单」而毫无提示。所以这里<b>显式校验第一个元素带 booklistId</b>，
 * 不满足就直接抛异常并把实际字段名带出去。
 *
 * <h3>⚠️ hasMore / totalCount 允许缺失</h3>
 * 这两个字段是「翻页辅助」，不是判断书单内容的依据。取不到时返回 {@code null}
 * 而不是默认 {@code false} —— 默认 false 会让「字段名变了」表现成
 * 「只翻了第一页就结束」，又是一次静默截断。翻页的终止条件由调用方
 * 结合「本页条数 &lt; count」「闸门命中」一起判（见 {@code BooklistSyncServiceImpl}）。
 */
@Getter
public class BooklistPage {

    private final JSONArray datas;

    /** 还有没有下一页。{@code null} = 响应里没有这个字段 */
    private final Boolean hasMore;

    /** 该分类下书单总数。{@code null} = 响应里没有这个字段 */
    private final Integer totalCount;

    private BooklistPage(JSONArray datas, Boolean hasMore, Integer totalCount) {
        this.datas = datas;
        this.hasMore = hasMore;
        this.totalCount = totalCount;
    }

    /**
     * 解析响应体。
     *
     * @throws IllegalArgumentException body 不是合法 JSON、接口回了错误信封、
     *         或 {@code datas} 元素不是书单
     */
    public static BooklistPage parse(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("响应体为空");
        }
        JSONObject root;
        try {
            root = JSON.parseObject(body);
        } catch (Exception e) {
            String head = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            throw new IllegalArgumentException("响应不是合法 JSON，开头为：" + head, e);
        }
        if (root == null) {
            throw new IllegalArgumentException("响应解析为 null");
        }

        // ---- 0. 错误信封优先（登录超时等）----
        Integer errcode = root.getInteger("errcode");
        if (errcode != null && errcode.intValue() != 0) {
            String msg = root.getString("errmsg");
            throw new IllegalArgumentException("接口返回错误 errcode=" + errcode
                    + (msg == null || msg.trim().isEmpty() ? "" : "：" + msg.trim()));
        }

        JSONArray datas = root.getJSONArray("datas");
        if (datas == null) {
            throw new IllegalArgumentException(
                    "书单发现响应里没有 datas 数组，顶层字段：" + root.keySet());
        }

        // ---- 1. 形状校验：datas 元素必须是书单 ----
        if (!datas.isEmpty()) {
            JSONObject first = datas.getJSONObject(0);
            if (first == null || !first.containsKey("booklistId")) {
                throw new IllegalArgumentException(
                        "书单发现响应的 datas 元素不是书单（缺 booklistId），实际字段："
                                + (first == null ? "非对象" : first.keySet().toString()));
            }
        }

        Boolean hasMore = root.containsKey("hasMore") ? root.getBoolean("hasMore") : null;
        Integer totalCount = root.containsKey("totalCount") ? root.getInteger("totalCount") : null;
        return new BooklistPage(datas, hasMore, totalCount);
    }

    public boolean isEmpty() {
        return datas == null || datas.isEmpty();
    }

    public int size() {
        return datas == null ? 0 : datas.size();
    }
}
