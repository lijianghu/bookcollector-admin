package com.bookcollector.audit.controller;

import com.bookcollector.audit.dto.ReplayRequest;
import com.bookcollector.audit.dto.ReplayResult;
import com.bookcollector.audit.dto.RequestDetail;
import com.bookcollector.audit.dto.RequestSummary;
import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.audit.service.AuditService;
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

/**
 * 请求审计（FR-G2）。
 *
 * <h3>这张表是排查「某页为什么没数据」的第一现场</h3>
 * 采集出问题时，能回答「到底发了几次请求、每次什么状态码、返回几条」的
 * 只有 {@code api_requests}。所以列表默认按时间倒序，且支持
 * {@code onlyFailed=true} 一键过滤出异常请求。
 *
 * <p>只有查询，没有删除。审计记录的价值恰恰在于「事后还在」——
 * 提供一个「清空审计」按钮，早晚会有人手滑点掉正在排查的那批数据。
 * 要清理请直接连库操作。
 *
 * <p>2026-10-15 晚新增的「回放」（{@link #replay}）同样<b>不改审计</b>：
 * 它只是把用户提交的请求（URL / 参数 / 请求头都可在弹窗里改）用最原始的 HTTP
 * 重发一次、把现场返回给前端，什么都不落库 —— 诊断动作不能污染
 * 「这个目标被采了多少次」的口径。
 *
 * <p>这一层是<b>极薄的转发</b>：参数归一化与查询都在 {@link AuditService} 里。
 */
@Tag(name = "08. 请求审计", description = "平台接口调用记录")
@RestController
@RequestMapping("/api/requests")
public class RequestAuditController {

    private final AuditService service;

    public RequestAuditController(AuditService service) {
        this.service = service;
    }

    @Operation(summary = "请求审计列表",
            description = "分页，按请求时间倒序。可按 runId / targetType / targetId 筛选，"
                    + "onlyFailed=true 只看失败请求")
    @GetMapping
    public ResultBean<PageResult<ApiRequest>> page(
            @Parameter(description = "按运行 ID 筛选") @RequestParam(required = false) String runId,
            @Parameter(description = "目标类型：CATEGORY / RANKING") @RequestParam(required = false) String targetType,
            @Parameter(description = "目标 ID") @RequestParam(required = false) String targetId,
            @Parameter(description = "只看失败（状态码非 200 或有错误摘要）") @RequestParam(required = false) Boolean onlyFailed,
            @Parameter(description = "页码，从 1 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，默认 20，上限 200") @RequestParam(required = false) Integer size) {
        return ResultBean.ok(service.page(runId, targetType, targetId, onlyFailed, page, size));
    }

    @Operation(summary = "请求审计摘要",
            description = "当前筛选条件下的请求次数、失败次数与耗时（平均 / P95 / 最慢）。"
                    + "筛选参数与列表接口一致，但不分页 —— 摘要是「这一批请求整体怎么样」")
    @GetMapping("/summary")
    public ResultBean<RequestSummary> summary(
            @Parameter(description = "按运行 ID 筛选") @RequestParam(required = false) String runId,
            @Parameter(description = "目标类型：CATEGORY / RANKING") @RequestParam(required = false) String targetType,
            @Parameter(description = "目标 ID") @RequestParam(required = false) String targetId,
            @Parameter(description = "只看失败（状态码非 200 或有错误摘要）") @RequestParam(required = false) Boolean onlyFailed) {
        return ResultBean.ok(service.summary(runId, targetType, targetId, onlyFailed));
    }

    /**
     * 单条请求的<b>完整详情</b>（2026-10-15 新增）。
     *
     * <p>返回三样现场 + 该次运行的步骤日志：
     * <ul>
     *   <li>{@code queryParams} —— URL 参数（原样）</li>
     *   <li>{@code requestHeaders} —— 请求头快照（{@code skey} 为掩码值，明文不落库）</li>
     *   <li>{@code rawResponse} —— 接口返回的原始 body（截断到 8000 字符）</li>
     *   <li>{@code logs} —— 该 {@code runId} 的核心步骤日志（时间正序，最多 300 条）</li>
     * </ul>
     * 用途：一次请求采到 0 条时，用它区分是<b>数据访问问题</b>（body 里就没有数据）
     * 还是<b>解析问题</b>（有数据但没解析出来，看日志里解析阶段的记录）。
     */
    @Operation(summary = "请求详情",
            description = "单条审计记录的完整现场：URL 参数 / 请求头（skey 掩码）/ 原始响应 / "
                    + "HTTP 状态 / 该次运行的核心步骤日志")
    @GetMapping("/{id}")
    public ResultBean<RequestDetail> detail(
            @Parameter(description = "审计记录 ID") @PathVariable String id) {
        return ResultBean.ok(service.findDetail(id));
    }

    /**
     * 「回放」：用最原始的 HTTP（不走 RestTemplate / 拦截器）按<b>提交的请求</b>再发一次
     * （2026-10-15 晚新增，当晚改成可编辑请求）。
     *
     * <p>用户诉求演进：「请求审计的请求详情弹窗，做一个『回放』按钮，后端使用最原始的
     * http 再次请求，然后打印出输入 url、参数、请求头、返回」→「url、参数和请求头
     * 都可以修改，按本次最新值发起请求，不用落库，只展示」。
     *
     * <p>入参是「这次要发的请求」：URL 是唯一真相（弹窗里的参数行是 query 的可编辑视图，
     * 前端改行即重建 query 回写 URL，所以后端不单独收 params）；请求头以提交的为准，
     * 但 skey 一律用当前内存凭证替换（提交上来的是掩码值，见 {@code RequestReplayer}）。
     * 返回 {@link ReplayResult}：url / 参数 / 请求头（掩码）/ 状态 / 耗时 /
     * 响应 synckey / body 原文（不过 fastjson）。
     * ⚠️ <b>不写审计</b>：回放是诊断动作，记进去会污染采集次数的口径。
     */
    @Operation(summary = "回放一次请求",
            description = "按提交的 URL 与请求头用最原始 HTTP 重发（skey 用当前凭证替换、返回掩码），"
                    + "返回 url / 参数 / 请求头 / 响应 synckey / body 原文；不写审计")
    @PostMapping("/replay")
    public ResultBean<ReplayResult> replay(@RequestBody ReplayRequest req) {
        return ResultBean.ok(service.replay(req));
    }
}
