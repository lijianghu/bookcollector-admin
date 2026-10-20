package com.bookcollector.paid.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 「付费精选配置」列表的一行（2026-10-20）。
 *
 * <p>数据来自 {@code taxonomy_config}：只取一级 + enabled=true 的分类，
 * {@code code} 就是请求里的 {@code filterCategory}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaidCategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分类 code（= filterCategory） */
    private String code;

    /** 分类名 */
    private String name;
}
