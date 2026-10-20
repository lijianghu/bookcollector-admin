package com.bookcollector.publish.controller;

import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;
import com.bookcollector.publish.req.CandidateBuildRequest;
import com.bookcollector.publish.req.InterpretationBatchDeleteRequest;
import com.bookcollector.publish.req.InterpretationQuery;
import com.bookcollector.publish.req.PublishConfigRequest;
import com.bookcollector.publish.service.PublishService;
import com.bookcollector.publish.vo.CandidateBookVO;
import com.bookcollector.publish.vo.InterpretationVO;
import com.bookcollector.publish.vo.PublishJobVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 书籍解读（挑一本高质量的书 → 生成解读 → 存本地 → 发语雀）。
 *
 * <h3>两类端点</h3>
 * <ul>
 *   <li><b>长任务</b>（{@code candidates/init}、{@code trigger}）：<b>提交即返回</b>，
 *       前端靠轮询 {@code GET /api/publish/job} 跟进进度。生成一本要 30~120 秒，
 *       同步等会让页面卡在请求里。</li>
 *   <li><b>短操作</b>（配置、分页、重置）：同步返回结果。</li>
 * </ul>
 *
 * <p>鉴权由 {@code WebMvcConfig} 统一拦截 {@code /api/**} 覆盖，这里不用额外声明。
 */
@Tag(name = "10. AI 书籍解读", description = "挑一本高质量图书，生成解读文章并发布到语雀")
@RestController
@RequestMapping("/api/publish")
public class PublishController {

    private final PublishService service;

    public PublishController(PublishService service) {
        this.service = service;
    }

    // ==================================================================
    // 页面主数据
    // ==================================================================

    @Operation(summary = "页面全部数据",
            description = "一个接口拿齐：cron / 是否启用 / 下次触发时间 / 分类白名单 / "
                    + "模型与语雀的配置情况（脱敏）/ 两条任务的运行状态与进度 / 清单各状态条数")
    @GetMapping("/job")
    public ResultBean<PublishJobVO> job() {
        return ResultBean.ok(service.getJob());
    }

    @Operation(summary = "修改配置",
            description = "cron / enabled 都可选（null = 不改）。cron 必须是 6 段式"
                    + "（秒 分 时 日 月 周）。改完立即生效，不需要重启后端")
    @PutMapping("/job")
    public ResultBean<PublishJobVO> update(@Validated @RequestBody PublishConfigRequest req) {
        return ResultBean.ok(service.updateConfig(req.getCron(), req.getEnabled()));
    }

    // ==================================================================
    // 候选清单
    // ==================================================================

    @Operation(summary = "初始化候选",
            description = "按分类白名单 + 评级档位 + 评价人数下限，从图书库里挑出符合条件的高质量图书，"
                    + "写进候选清单（已有的跳过，可反复执行）。提交后立即返回，进度看 GET /api/publish/job")
    @PostMapping("/candidates/init")
    public ResultBean<PublishJobVO> initCandidates() {
        return ResultBean.ok(service.initializeCandidates());
    }

    @Operation(summary = "精选预览",
            description = "按「精选配置」的筛选条件从图书库查一页，**只读不写**。"
                    + "条件与「精选」完全共用，保证「预览到什么、精选的就是什么」。"
                    + "平台分类（categoryIds）必填；评价标签 / 推荐值下限 / 书名 / 作者 至少给一个")
    @PostMapping("/candidates/preview")
    public ResultBean<PageResult<CandidateBookVO>> previewCandidates(
            @RequestBody CandidateBuildRequest req,
            @RequestParam(name = "page", defaultValue = "1") Integer page,
            @RequestParam(name = "size", defaultValue = "20") Integer size) {
        return ResultBean.ok(service.previewCandidates(req, page, size));
    }

    @Operation(summary = "精选（批量 / 全部）",
            description = "把符合条件的书快照进候选清单（已有的跳过，可反复执行）。"
                    + "all=true 时忽略 bookIds、按条件全量写入；否则只处理 bookIds 里那几本"
                    + "（= 预览列表里勾选的当页行）。"
                    + "提交后立即返回，进度看 GET /api/publish/job 的 "
                    + "initRunning / initScanned / initInserted")
    @PostMapping("/candidates/collect")
    public ResultBean<PublishJobVO> collectCandidates(@RequestBody CandidateBuildRequest req) {
        return ResultBean.ok(service.collectCandidates(req));
    }

    @Operation(summary = "清空未解读候选",
            description = "只删掉「待解读」的条目，已发布 / 失败 / 生成中的不受影响。"
                    + "换过分类白名单之后，旧候选已经不再符合条件，需要先清空再重新初始化")
    @PostMapping("/candidates/clear")
    public ResultBean<PublishJobVO> clearCandidates() {
        return ResultBean.ok(service.clearCandidates());
    }

    @Operation(summary = "候选清单分页",
            description = "筛选条件与图书库一致：状态 / 书名（模糊）/ 作者（模糊）/ 平台分类（一级）/ "
                    + "推荐值区间 / 阅读人数下限 / 出版时间区间。留空的条件不参与筛选。"
                    + "返回不含正文（正文几千字一条，列表不需要）")
    @GetMapping("/interpretations")
    public ResultBean<PageResult<InterpretationVO>> page(InterpretationQuery query) {
        return ResultBean.ok(service.pageInterpretations(query));
    }

    @Operation(summary = "批量删除候选清单条目",
            description = "⚠️ 不限状态 —— 已发布到语雀的也能删。删的是清单里的这一行，"
                    + "不会删除语雀上的文章，也不影响「发布记录」里的历史流水。"
                    + "返回实际删除条数（可能与请求条数不同）")
    @PostMapping("/interpretations/batch-delete")
    public ResultBean<Map<String, Object>> batchDelete(
            @Validated @RequestBody InterpretationBatchDeleteRequest req) {
        long deleted = service.deleteInterpretations(req.getBookIds());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("requested", req.getBookIds().size());
        data.put("deleted", deleted);
        return ResultBean.ok(data);
    }

    @Operation(summary = "重置为指定状态",
            description = "把某一条的状态改掉。用于捞回卡住的「生成中」（进程被杀会残留）、"
                    + "让失败的再跑一次（失败不自动重试）、或把清单里的状态改成实际发布情况。"
                    + "status 只接受 PENDING（待解读）/ PUBLISHED（已发布）；"
                    + "不传 = PENDING，与老接口行为一致。"
                    + "⚠️ 标记成 PUBLISHED 只改清单状态，不会在语雀上产生文章")
    @PostMapping("/interpretations/{bookId}/reset")
    public ResultBean<Boolean> reset(
            @PathVariable("bookId") String bookId,
            @Parameter(description = "目标状态：PENDING（默认）/ PUBLISHED")
            @RequestParam(value = "status", required = false) String status) {
        return ResultBean.ok(service.resetTo(bookId, status));
    }

    // ==================================================================
    // 执行
    // ==================================================================

    @Operation(summary = "生成并发布（一本）",
            description = "挑一本待解读的书，生成解读文章、保存到本地、发布到语雀。"
                    + "提交后立即返回（不等它跑完），前端轮询 GET /api/publish/job 跟进进度。"
                    + "已有任务在跑时返回 code=400")
    @PostMapping("/trigger")
    public ResultBean<PublishJobVO> trigger() {
        return ResultBean.ok(service.trigger());
    }
}
