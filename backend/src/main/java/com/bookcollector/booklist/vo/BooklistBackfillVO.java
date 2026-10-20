package com.bookcollector.booklist.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 「一键完善书单信息」的进度快照（2026-10-20）。
 *
 * <p>数据来自后端内存，刷新页面后还能接着看；进程重启则归零。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BooklistBackfillVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean running;

    /** 待完善的书单总数 */
    private int total;

    /** 已处理数 */
    private int processed;

    /** 成功回填数 */
    private int updated;

    /** 失败数（接口没给 / 请求失败） */
    private int failed;

    /** 当前正在处理的 booklistId */
    private String currentBooklistId;

    private String message;

    private long startedAtMs;

    private long finishedAtMs;

    private String errorMsg;
}
