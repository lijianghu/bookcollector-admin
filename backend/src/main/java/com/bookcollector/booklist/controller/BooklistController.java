package com.bookcollector.booklist.controller;

import com.bookcollector.book.entity.Book;
import com.bookcollector.booklist.entity.Booklist;
import com.bookcollector.booklist.req.BooklistCreateRequest;
import com.bookcollector.booklist.req.BooklistQuery;
import com.bookcollector.booklist.req.BooklistSyncRequest;
import com.bookcollector.booklist.service.BooklistCollectService;
import com.bookcollector.booklist.service.BooklistService;
import com.bookcollector.booklist.service.BooklistSyncService;
import com.bookcollector.booklist.vo.BooklistBackfillVO;
import com.bookcollector.booklist.vo.BooklistCollectVO;
import com.bookcollector.booklist.vo.BooklistOptionVO;
import com.bookcollector.booklist.vo.BooklistSyncVO;
import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 书单（采集目标之一）。
 *
 * <h3>两组端点</h3>
 * <table border="1">
 *   <tr><th>组</th><th>端点</th><th>用途</th></tr>
 *   <tr><td>读（{@link BooklistService}）</td>
 *       <td>{@code GET /api/booklists} · {@code GET /api/booklists/categories}
 *           · {@code GET /api/booklists/{booklistId}/books}</td>
 *       <td>书单配置页的列表、16 个分类、书单详情弹窗里的书</td></tr>
 *   <tr><td>写（{@link BooklistSyncService}，链路 A）</td>
 *       <td>{@code POST /api/booklists/sync} · {@code GET /api/booklists/sync/progress}</td>
 *       <td>分页发现书单、写进书单表</td></tr>
 * </table>
 *
 * <p>同步是<b>长任务</b>，所以照 {@code PublishController} 的做法：
 * {@code POST /sync} 提交即返回，前端靠轮询 {@code GET /sync/progress} 跟进。
 *
 * <p>分页列表直接收 {@link BooklistQuery} 作为查询参数对象 —— Spring MVC 会把
 * {@code ?categoryId=200000&minCollectCount=10000&page=2} 自动绑上去，
 * 不写 {@code @RequestParam} 逐个声明（同 {@code BookController#page}）。
 *
 * <p>鉴权由 {@code WebMvcConfig} 统一拦截 {@code /api/**} 覆盖，这里不用额外声明。
 */
@Tag(name = "15. 书单", description = "书单列表（筛选/分页）+ 按平台分类分页发现书单（链路 A）")
@RestController
@RequestMapping("/api/booklists")
public class BooklistController {

    private final BooklistService service;
    private final BooklistSyncService syncService;
    private final BooklistCollectService collectService;

    public BooklistController(BooklistService service,
                              BooklistSyncService syncService,
                              BooklistCollectService collectService) {
        this.service = service;
        this.syncService = syncService;
        this.collectService = collectService;
    }

    // ==================================================================
    // 读
    // ==================================================================

    @Operation(summary = "分页查询书单",
            description = "支持按 平台分类ID（字符串）/ 采书状态 / 收藏人数区间 / 书籍数区间 / "
                    + "只看待回填名称 筛选；关键词同时匹配 名称 与 书单ID（包含匹配）。"
                    + "支持按 收藏人数/书籍数/已采数/最近扫到/首次发现/最近采集 排序。")
    @GetMapping
    public ResultBean<PageResult<Booklist>> page(BooklistQuery query) {
        return ResultBean.ok(service.page(query));
    }

    @Operation(summary = "书单下拉选项",
            description = "全量书单的「ID + 名称」，供图书库页的「书单」筛选下拉用（2026-10-16）。"
                    + "不分页，只投影两个字段。")
    @GetMapping("/options")
    public ResultBean<List<BooklistOptionVO>> options() {
        return ResultBean.ok(service.options());
    }

    @Operation(summary = "平台分类清单",
            description = "书单配置页顶部要平铺出来的 16 个分类（含 categoryId 与 title）。"
                    + "它是业务事实（代码常量），不是从接口响应里读的")
    @GetMapping("/categories")
    public ResultBean<List<Booklist.PlatformCategory>> categories() {
        return ResultBean.ok(service.categories());
    }

    @Operation(summary = "书单内的图书（分页）",
            description = "书单详情弹窗用：按 booklistId 查 books（匹配 bookLists.bookListId），"
                    + "评分降序 + _id 兜底排序，与图书库列表默认排序一致。"
                    + "page 从 1 开始，size 默认 20、上限 200；书单还没采过书时返回空列表")
    @GetMapping("/{booklistId}/books")
    public ResultBean<PageResult<Book>> books(
            @Parameter(description = "书单 ID，形如 352003193_8cKur0WC3", required = true)
            @PathVariable String booklistId,
            @Parameter(description = "页码，从 1 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，默认 20，上限 200") @RequestParam(required = false) Integer size) {
        return ResultBean.ok(service.pageBooks(booklistId, page, size));
    }

    // ==================================================================
    // 写（手工维护）
    // ==================================================================

    @Operation(summary = "新增书单",
            description = "手工录入一张书单（2026-10-20）。只写 booklistId 与可选 name，"
                    + "书籍数/收藏人数等由后续「同步」采集回填。同一 booklistId 已存在时返回 code=400")
    @PostMapping
    public ResultBean<Booklist> create(@RequestBody BooklistCreateRequest req) {
        return ResultBean.ok(service.create(req.getBooklistId(), req.getName()));
    }

    // ==================================================================
    // 写（链路 A）
    // ==================================================================

    @Operation(summary = "同步书单",
            description = "对选中的平台分类（不传 = 全部 16 个），按「最多收藏」排序分页拉取书单，"
                    + "写进书单表（按 booklistId 幂等，可反复执行）。"
                    + "闸门：书数 >= 50 且 收藏人数 >= 10000；收藏人数一旦不达标即停止该分类的翻页。"
                    + "提交后立即返回，进度看 GET /api/booklists/sync/progress。"
                    + "已有同步在跑时返回 code=400；请求体里有清单外的分类 ID 也返回 code=400")
    @PostMapping("/sync")
    public ResultBean<BooklistSyncVO> sync(
            @RequestBody(required = false) BooklistSyncRequest req) {
        return ResultBean.ok(syncService.sync(req == null ? null : req.getCategoryIds()));
    }

    @Operation(summary = "同步进度",
            description = "返回当前（或最近一次）同步的进度快照。没跑过时返回一份「全零、未运行」的对象")
    @GetMapping("/sync/progress")
    public ResultBean<BooklistSyncVO> progress() {
        return ResultBean.ok(syncService.progress());
    }

    @Operation(summary = "一键完善书单信息",
            description = "异步刷新所有书单的元数据（名称/简介/作者/计数/时间，2026-10-20），"
                    + "已有数据会被覆盖更新。提交即返回，进度看 GET /api/booklists/backfill/progress")
    @PostMapping("/backfill")
    public ResultBean<BooklistBackfillVO> backfill() {
        return ResultBean.ok(syncService.backfillMetadata());
    }

    @Operation(summary = "完善书单信息进度",
            description = "返回「一键完善书单信息」的进度快照。没跑过时返回「未运行」的对象")
    @GetMapping("/backfill/progress")
    public ResultBean<BooklistBackfillVO> backfillProgress() {
        return ResultBean.ok(syncService.backfillProgress());
    }

    // ==================================================================
    // 写（链路 B：按书单采书）
    // ==================================================================

    @Operation(summary = "同步书单书籍",
            description = "采集这张书单里的书（2026-10-20）。复用 collect_tasks（targetType=BOOKLIST）："
                    + "没有任务就建，有就按状态 start/retry/resume。提交即返回，"
                    + "进度看 GET /api/booklists/{booklistId}/collect/progress")
    @PostMapping("/{booklistId}/collect")
    public ResultBean<BooklistCollectVO> collect(
            @Parameter(description = "书单 ID", required = true) @PathVariable String booklistId) {
        return ResultBean.ok(collectService.collect(booklistId));
    }

    @Operation(summary = "书单采集进度",
            description = "返回这张书单最近一次采集的状态与进度（页数/入库数/新增数/错误）")
    @GetMapping("/{booklistId}/collect/progress")
    public ResultBean<BooklistCollectVO> collectProgress(
            @Parameter(description = "书单 ID", required = true) @PathVariable String booklistId) {
        return ResultBean.ok(collectService.progress(booklistId));
    }
}
