package com.bookcollector.shelf.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 「批量查藏书状态」请求体 —— 图书库列表渲染完一行后，问这一页里哪些书已在藏书阁。
 *
 * <h3>为什么不让图书库列表接口直接带 {@code onShelf} 字段</h3>
 * 那样 {@code book} 模块就要依赖 {@code shelf} 模块；而本项目里
 * {@code shelf} 一直是「不依赖任何业务模块」的单向叶子（见 {@code ShelfBookRepository} 注释）。
 * 所以改成前端拉完图书列表后，拿这一页的 bookId 来问一次 —— 一次请求解决一页，
 * 模块边界保持干净。
 */
@Data
@Schema(description = "批量查询藏书状态请求")
public class ShelfStatusRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "要查询的图书 ID 列表（一般是当前页的 bookId）")
    private List<String> bookIds;
}
