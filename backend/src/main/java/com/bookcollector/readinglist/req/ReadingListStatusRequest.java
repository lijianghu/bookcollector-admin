package com.bookcollector.readinglist.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 「批量查阅读清单状态」请求体 —— 列表渲染完一页后，问这一页里哪些书已在阅读清单、
 * 分别是什么状态。
 *
 * <h3>为什么不让图书库列表接口直接带 {@code inReadingList} 字段</h3>
 * 同 {@code ShelfStatusRequest} 的理由：那样 {@code book} 模块就要依赖 {@code readinglist}。
 * 本项目里 {@code book} 不依赖任何业务模块（状态类信息一律由前端另问一次），
 * 模块边界保持干净。
 */
@Data
@Schema(description = "批量查询阅读清单状态请求")
public class ReadingListStatusRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "要查询的图书 ID 列表（一般是当前页的 bookId）")
    private List<String> bookIds;
}
