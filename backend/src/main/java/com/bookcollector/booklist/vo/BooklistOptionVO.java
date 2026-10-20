package com.bookcollector.booklist.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 书单下拉选项（{@code GET /api/booklists/options}，2026-10-16）。
 *
 * <p>用途：图书库页的「书单」筛选下拉。
 *
 * <p>为什么不直接返回 {@code GET /api/booklists} 的分页结果：那个返回整个
 * {@code Booklist} 文档（含简介、作者头像、平台分类、各种计数…），
 * 228 条能到 1MB 级，而这里只需要两个字段填下拉 —— 不值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BooklistOptionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 书单 ID，形如 {@code 391152476_7sKNjWqRN} */
    private String booklistId;

    /** 书单名称。还没回填时是 null，前端用 ID 兜底显示 */
    private String name;
}
