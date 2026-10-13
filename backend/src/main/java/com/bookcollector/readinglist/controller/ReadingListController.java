package com.bookcollector.readinglist.controller;

import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;
import com.bookcollector.readinglist.req.ReadingListAddRequest;
import com.bookcollector.readinglist.req.ReadingListStatusRequest;
import com.bookcollector.readinglist.req.ReadingListStatusUpdateRequest;
import com.bookcollector.readinglist.service.ReadingListService;
import com.bookcollector.readinglist.vo.ReadingListBookVO;
import com.bookcollector.readinglist.vo.ReadingListStatusVO;
import com.bookcollector.readinglist.vo.ReadingListSummaryVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 阅读清单（「藏书阁 → 阅读清单」页 + 其它三个列表页的「加入 / 移除 / 改状态」）。
 *
 * <h3>它与藏书阁（{@code /api/shelf}）的关系</h3>
 * <b>并列、互不影响</b>。「收藏」与「阅读计划」是两个独立维度：
 * 一本书可以只收藏不想读、只想读不收藏，两边各加各的。
 * 所以本 Controller 的任何一个端点都<b>不会</b>去动 {@code shelf_books}，
 * 反之 {@code ShelfController} 也不碰 {@code reading_list}。
 *
 * <h3>四个页面共用</h3>
 * 图书库 / 精选图书 / 我的藏书 / 阅读清单 四处都调这里的
 * {@code POST /status}（批量问状态）与三个写端点。
 *
 * <p>挂在 {@code /api/**} 下，和其他接口一样需要登录（没有白名单例外）。
 */
@Tag(name = "13. 阅读清单", description = "想读 / 在读 / 已读 的阅读计划：分页取书 + 加入/移除 + 改状态")
@RestController
@RequestMapping("/api/reading-list")
public class ReadingListController {

    private final ReadingListService service;

    public ReadingListController(ReadingListService service) {
        this.service = service;
    }

    @Operation(summary = "分页取书",
            description = "默认每页 30 条（前端不传 size 时的兜底值）。"
                    + "status 传 WANT_TO_READ / READING / FINISHED，不传 = 全部；"
                    + "category 可传一级或带二级的完整类目名（内部截到一级）；"
                    + "title / author 是**包含匹配**（模糊搜索，大小写不敏感）。条件之间是「与」")
    @GetMapping("/books")
    public ResultBean<PageResult<ReadingListBookVO>> books(
            @Parameter(description = "阅读状态：WANT_TO_READ / READING / FINISHED；不传 = 全部")
            @RequestParam(required = false) String status,
            @Parameter(description = "一级类目名，如 文学；不传 = 全部")
            @RequestParam(required = false) String category,
            @Parameter(description = "书名关键词，包含匹配（模糊）；不传 = 不筛")
            @RequestParam(required = false) String title,
            @Parameter(description = "作者关键词，包含匹配（模糊）；不传 = 不筛")
            @RequestParam(required = false) String author,
            @Parameter(description = "页码，从 1 开始")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，默认 30，上限 200")
            @RequestParam(required = false) Integer size) {
        return ResultBean.ok(service.pageBooks(status, category, title, author, page, size));
    }

    @Operation(summary = "各状态计数", description = "给页面顶部状态 Tab 显示角标")
    @GetMapping("/summary")
    public ResultBean<ReadingListSummaryVO> summary() {
        return ResultBean.ok(service.summary());
    }

    @Operation(summary = "加入阅读清单",
            description = "从图书库取快照写入。**不要求这本书已在藏书阁** —— 收藏与阅读计划是独立维度。"
                    + "重复加入幂等（返回 false，不报错）。status 可选，不传 = WANT_TO_READ")
    @PostMapping("/books")
    public ResultBean<Boolean> add(@RequestBody ReadingListAddRequest request) {
        return ResultBean.ok(service.add(
                request == null ? null : request.getBookId(),
                request == null ? null : request.getStatus()));
    }

    @Operation(summary = "移除阅读清单",
            description = "只动阅读清单，**不影响藏书阁**。本来就不在时返回 false，不报错")
    @DeleteMapping("/books/{bookId}")
    public ResultBean<Boolean> remove(
            @Parameter(description = "图书 ID", required = true)
            @PathVariable String bookId) {
        return ResultBean.ok(service.remove(bookId));
    }

    @Operation(summary = "修改阅读状态",
            description = "status 必填（WANT_TO_READ / READING / FINISHED），非法值返回 code=400。"
                    + "返回 true = 这本书现在处于目标状态；false = 它不在阅读清单里")
    @PutMapping("/books/{bookId}/status")
    public ResultBean<Boolean> updateStatus(
            @Parameter(description = "图书 ID", required = true)
            @PathVariable String bookId,
            @RequestBody ReadingListStatusUpdateRequest request) {
        return ResultBean.ok(service.updateStatus(
                bookId, request == null ? null : request.getStatus()));
    }

    @Operation(summary = "批量查阅读清单状态",
            description = "四个列表页用它决定每行显示「加入」还是「移除」，并显示当前状态。"
                    + "返回 [{bookId, status}]（只含已在清单里的）")
    @PostMapping("/status")
    public ResultBean<List<ReadingListStatusVO>> status(@RequestBody ReadingListStatusRequest request) {
        return ResultBean.ok(service.statusOf(request == null ? null : request.getBookIds()));
    }
}
