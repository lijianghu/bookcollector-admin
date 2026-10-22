package com.bookcollector.paid.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 「付费精选配置」列表的一行（2026-10-20）。
 *
 * <p>分类来自 {@code taxonomy_config}；状态与断点来自 {@code paid_collect_state}。
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

    /** 最近一次状态：RUNNING / PAUSED / INTERRUPTED / COMPLETED / STOPPED / FAILED；没跑过为 null */
    private String lastStatus;

    /** 已翻页数 */
    private int page;

    /** 接口声明的总书数 */
    private Integer totalCount;

    /** 断点最后更新时间 */
    private Date updatedAt;
}
