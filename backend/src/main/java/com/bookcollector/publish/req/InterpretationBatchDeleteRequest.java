package com.bookcollector.publish.req;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 候选清单批量删除请求体（2026-10-10 新增）。
 *
 * <p>与 {@code book.req.BatchDeleteRequest} 同形但是<b>独立的类</b> ——
 * 两个模块互不 import，理由见 {@code InterpretationQuery} 的类注释。
 *
 * <p>用 {@code POST .../batch-delete} 而不是 {@code DELETE} 带 body：
 * HTTP 规范上 DELETE 的 body 语义不明确，部分代理会直接丢掉它，
 * 而且 {@code axios.delete(url, {data})} 这种写法容易踩坑。
 */
@Schema(description = "候选清单批量删除请求")
public class InterpretationBatchDeleteRequest {

    @Schema(description = "要删除的 bookId 列表", required = true)
    @NotEmpty(message = "不能为空")
    private List<String> bookIds;

    public List<String> getBookIds() {
        return bookIds;
    }

    public void setBookIds(List<String> bookIds) {
        this.bookIds = bookIds;
    }
}
