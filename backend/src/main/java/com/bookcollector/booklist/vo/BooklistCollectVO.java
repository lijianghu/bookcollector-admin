package com.bookcollector.booklist.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 「同步某张书单」的进度快照（2026-10-20）。
 *
 * <p>数据直接取自该书的采集任务与最近一次运行（{@code TaskRun}）——
 * 不另建进度表，任务模块就是唯一真相。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BooklistCollectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String booklistId;

    private String taskId;

    private String runId;

    /** {@code TaskStatus} 的枚举名 */
    private String status;

    /** 状态中文名（前端直接展示） */
    private String statusLabel;

    /** 是否正在跑（{@code status == RUNNING}） */
    private boolean running;

    private Integer pagesDone;

    private Long booksSaved;

    private Long booksInserted;

    private Long booksExcluded;

    /** 该目标声明的总书数（书单响应里的 totalCount），用于算进度百分比 */
    private Integer totalCount;

    private Date startedAt;

    private Date finishedAt;

    private String errorMsg;
}
