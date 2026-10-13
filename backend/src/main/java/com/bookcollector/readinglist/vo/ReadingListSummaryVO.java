package com.bookcollector.readinglist.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 阅读清单的各状态计数 —— 给页面顶部的状态 Tab 显示角标。
 *
 * <p>{@code total} 是三者之和（= 清单总条数），冗余给出来省得前端自己加。
 */
@Data
@Schema(description = "阅读清单各状态计数")
public class ReadingListSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "想读的数量")
    private long wantToRead;

    @Schema(description = "在读的数量")
    private long reading;

    @Schema(description = "已读的数量")
    private long finished;

    @Schema(description = "总数（三者之和）")
    private long total;
}
