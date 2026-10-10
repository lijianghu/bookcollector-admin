package com.bookcollector.shelf.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 「添加藏书」请求体 —— 图书库操作列点「添加藏书」时提交。
 *
 * <p>只带 {@code bookId}：书名 / 作者 / 封面等快照由<b>后端</b>从 {@code books}
 * 里读，不接受前端传值 —— 否则客户端能往藏书阁塞任意内容，
 * 而「藏书阁里的书必须真实存在于图书库」是这张表的前提。
 */
@Data
@Schema(description = "添加藏书请求")
public class ShelfAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "图书 ID", required = true)
    private String bookId;
}
