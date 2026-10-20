package com.bookcollector.booklist.req;

import java.util.List;

/**
 * 「同步书单」（链路 A）的请求体 —— 本次要同步哪些平台分类。
 *
 * <h3>为什么是可选参数</h3>
 * 书单配置页把 16 个平台分类平铺出来，用户可以只勾其中几个再点确认。
 * <b>不传 / 传空数组 = 全部 16 个</b>（接口层面保留「一键全量」的能力，
 * 页面默认也是全选）。
 *
 * <p>⚠️ 校验在 {@code BooklistCategories.byIds(...)}：出现清单外的 ID 时
 * <b>直接报错</b>，而不是「忽略不认识的、同步剩下的」——
 * 后者会表现为「你勾了 5 个，实际只同步了 3 个，页面不报错」，
 * 是最难发现的一类静默偏差。
 */
public class BooklistSyncRequest {

    /**
     * 要同步的平台分类 ID 列表（字符串，如 {@code "200000"} / {@code "topic"}）。
     *
     * <p>为 {@code null} 或空时表示<b>全部 16 个分类</b>。
     */
    private List<String> categoryIds;

    public List<String> getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(List<String> categoryIds) {
        this.categoryIds = categoryIds;
    }
}
