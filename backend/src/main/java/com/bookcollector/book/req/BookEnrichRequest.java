package com.bookcollector.book.req;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 「完善书籍信息」请求体（2026-10-16）。
 *
 * <p>不复用 {@code BatchDeleteRequest}：虽然字段名一样都是 {@code bookIds}，
 * 但一个是「删」一个是「补」，共用一个类会让 Swagger 上的请求体示例
 * 和实际语义对不上。
 */
@Schema(description = "完善书籍信息请求")
public class BookEnrichRequest {

    @Schema(description = "要补齐的图书 ID 列表（通常是当前页选中的那些）", required = true)
    @NotEmpty(message = "不能为空")
    private List<String> bookIds;

    public List<String> getBookIds() {
        return bookIds;
    }

    public void setBookIds(List<String> bookIds) {
        this.bookIds = bookIds;
    }
}
