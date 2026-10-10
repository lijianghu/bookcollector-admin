package com.bookcollector.shelf.controller;

import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;
import com.bookcollector.shelf.req.ShelfAddRequest;
import com.bookcollector.shelf.req.ShelfStatusRequest;
import com.bookcollector.shelf.service.ShelfService;
import com.bookcollector.shelf.vo.ShelfBookVO;
import com.bookcollector.shelf.vo.ShelfCategoryVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 藏书阁（沉浸式浏览页 + 图书库的「添加/移除藏书」）。
 *
 * <h3>2026-10-10：从只读变可写</h3>
 * 第一版只有两个只读端点，数据源是 {@code book_interpretations}。
 * 现在藏书阁有自己的一张表（{@code shelf_books}），所以补了三个端点：
 * 加入 / 移除 / 批量查状态。列表也改成分页（前端一次 30 条、下滑加载）。
 *
 * <p>图书详情仍复用 {@code GET /api/books/{bookId}}，不在这里复制一份 ——
 * 「不要为了藏书阁再复制一套详情数据接口」。
 *
 * <p>挂在 {@code /api/**} 下，和其他接口一样需要登录（没有白名单例外）。
 */
@Tag(name = "11. 藏书阁", description = "沉浸式书架：类目导航 + 分页取书 + 添加/移除藏书")
@RestController
@RequestMapping("/api/shelf")
public class ShelfController {

    private final ShelfService service;

    public ShelfController(ShelfService service) {
        this.service = service;
    }

    @Operation(summary = "一级类目列表（含数量）",
            description = "按数量降序。类目来自数据本身，不是写死的常量")
    @GetMapping("/categories")
    public ResultBean<List<ShelfCategoryVO>> categories() {
        return ResultBean.ok(service.listCategories());
    }

    @Operation(summary = "分页取书",
            description = "默认每页 30 条（前端一屏 10 列 × 3 行）。"
                    + "category 可传一级或带二级的完整类目名（内部截到一级）；不传 = 取全部")
    @GetMapping("/books")
    public ResultBean<PageResult<ShelfBookVO>> books(
            @Parameter(description = "一级类目名，如 文学；不传 = 全部")
            @RequestParam(required = false) String category,
            @Parameter(description = "页码，从 1 开始")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，默认 30，上限 200")
            @RequestParam(required = false) Integer size) {
        return ResultBean.ok(service.pageBooks(category, page, size));
    }

    @Operation(summary = "添加藏书",
            description = "从图书库取快照写入藏书阁。重复添加幂等（返回 false，不报错）")
    @PostMapping("/books")
    public ResultBean<Boolean> add(@RequestBody ShelfAddRequest request) {
        return ResultBean.ok(service.add(request == null ? null : request.getBookId()));
    }

    @Operation(summary = "移除藏书", description = "本来就不在时返回 false，不报错")
    @DeleteMapping("/books/{bookId}")
    public ResultBean<Boolean> remove(
            @Parameter(description = "图书 ID", required = true)
            @PathVariable String bookId) {
        return ResultBean.ok(service.remove(bookId));
    }

    @Operation(summary = "批量查藏书状态",
            description = "图书库列表用它决定每行显示「添加藏书」还是「移除藏书」。返回已在藏书阁的 bookId")
    @PostMapping("/status")
    public ResultBean<Set<String>> status(@RequestBody ShelfStatusRequest request) {
        return ResultBean.ok(service.statusOf(request == null ? null : request.getBookIds()));
    }
}
