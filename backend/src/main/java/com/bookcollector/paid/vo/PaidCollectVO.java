package com.bookcollector.paid.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 「付费精选」采集的进度快照（2026-10-20）。
 *
 * <p>同一时刻只跑一个分类（后端互斥），所以是一份全局快照，不按行存。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaidCollectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean running;

    /** 是否已暂停（暂停时 {@code running} 仍为 true，线程在等待） */
    private boolean paused;

    /** 当前（或最近一次）采集的 filterCategory */
    private String filterCategory;

    /** 分类名 */
    private String filterCategoryName;

    /** 接口声明的总书数 */
    private Integer totalCount;

    /** 已翻页数 */
    private int page;

    /** 累计解析到的书数 */
    private int booksSaved;

    /** 新增入库数 */
    private long booksInserted;

    /** 已存在、只补了 collectSource / paidCollect 的数 */
    private long booksUpdated;

    private String message;

    private long startedAtMs;

    private long finishedAtMs;

    private String errorMsg;
}
