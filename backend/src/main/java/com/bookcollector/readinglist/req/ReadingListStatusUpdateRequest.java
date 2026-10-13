package com.bookcollector.readinglist.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 「修改阅读状态」请求体 —— 想读 / 在读 / 已读 三选一。
 *
 * <p>⚠️ 状态值<b>必须合法</b>：非法值后端返回 {@code code=400}，
 * <b>不会</b>被静默改成「想读」（见 {@code ReadingStatus.parse} 的注释 ——
 * 静默降级会让用户以为操作成功了，比报错更糟）。
 */
@Data
@Schema(description = "修改阅读状态请求")
public class ReadingListStatusUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "目标状态：WANT_TO_READ / READING / FINISHED", required = true)
    private String status;
}
