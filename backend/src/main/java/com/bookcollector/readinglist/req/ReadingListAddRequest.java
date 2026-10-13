package com.bookcollector.readinglist.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 「加入阅读清单」请求体。
 *
 * <p>只带 {@code bookId}（+ 可选 {@code status}）：书名 / 作者 / 封面等快照由<b>后端</b>
 * 从 {@code books} 里读，不接受前端传值 —— 否则客户端能往阅读清单塞任意内容，
 * 而「清单里的书必须真实存在于图书库」是这张表的前提（同 {@code ShelfAddRequest}）。
 *
 * <p>{@code status} 可选：不传 = {@code WANT_TO_READ}（想读）。
 * 带上它可以「加入并直接标记为在读」，省掉一次额外的改状态请求。
 */
@Data
@Schema(description = "加入阅读清单请求")
public class ReadingListAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "图书 ID", required = true)
    private String bookId;

    @Schema(description = "初始阅读状态：WANT_TO_READ / READING / FINISHED；不传 = WANT_TO_READ")
    private String status;
}
