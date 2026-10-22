package com.bookcollector.paid.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 付费精选采集断点（2026-10-20）。
 *
 * <p>每采完一页写一次；重启后点「启动」从 {@link #maxIdx} 继续。
 * {@code _id} 直接用 {@code filterCategory}，天然唯一，不需要额外索引。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "paid_collect_state")
public class PaidCollectState implements Serializable {

    private static final long serialVersionUID = 1L;

    /** = filterCategory */
    @Id
    private String id;

    private String filterCategory;

    /** 分类名称（如「文学」） */
    private String filterCategoryName;

    /**
     * 最近一次状态：RUNNING / PAUSED / INTERRUPTED / COMPLETED / STOPPED / FAILED。
     *
     * <p>重启时 RUNNING 会被启动迁移改成 INTERRUPTED。
     */
    private String lastStatus;

    /** 下一页的 maxIdx（断点） */
    private int maxIdx;

    /** 已翻页数 */
    private int page;

    private Integer totalCount;

    private Date updatedAt;
}
