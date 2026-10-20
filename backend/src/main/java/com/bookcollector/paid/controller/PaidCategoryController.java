package com.bookcollector.paid.controller;

import com.bookcollector.common.ResultBean;
import com.bookcollector.paid.service.PaidCategoryService;
import com.bookcollector.paid.vo.PaidCategoryVO;
import com.bookcollector.paid.vo.PaidCollectVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 付费精选配置（2026-10-20）。
 *
 * <p>列表 = {@code taxonomy_config} 的一级 + enabled=true 分类；
 * 「启动」按 {@code filterCategory} 分页采集付费精选书籍。
 */
@Tag(name = "16. 付费精选", description = "按 taxonomy_config 一级分类采集付费精选书籍")
@RestController
@RequestMapping("/api/paid")
public class PaidCategoryController {

    private final PaidCategoryService service;

    public PaidCategoryController(PaidCategoryService service) {
        this.service = service;
    }

    @Operation(summary = "分类清单",
            description = "taxonomy_config 里一级 + enabled=true 的分类（每行一个 filterCategory）")
    @GetMapping("/categories")
    public ResultBean<List<PaidCategoryVO>> categories() {
        return ResultBean.ok(service.list());
    }

    @Operation(summary = "启动采集",
            description = "按 filterCategory 分页采集付费精选书籍。提交即返回，进度看 GET /api/paid/progress。"
                    + "同一时刻只跑一个分类；不在清单里的 code 返回 code=400。"
                    + "默认从上次断点继续；reset=true 清断点从头采")
    @PostMapping("/categories/{code}/collect")
    public ResultBean<PaidCollectVO> collect(
            @Parameter(description = "分类 code", required = true) @PathVariable String code,
            @Parameter(description = "true = 清断点从头采") @RequestParam(required = false) Boolean reset) {
        return ResultBean.ok(service.start(code, Boolean.TRUE.equals(reset)));
    }

    @Operation(summary = "采集进度",
            description = "当前（或最近一次）付费精选采集的进度快照。没跑过时返回「未运行」的对象")
    @GetMapping("/progress")
    public ResultBean<PaidCollectVO> progress() {
        return ResultBean.ok(service.progress());
    }

    @Operation(summary = "暂停采集", description = "请求暂停，当前页跑完后原地等待，maxIdx 保留")
    @PostMapping("/pause")
    public ResultBean<PaidCollectVO> pause() {
        service.pause();
        return ResultBean.ok(service.progress());
    }

    @Operation(summary = "继续采集", description = "从暂停处继续")
    @PostMapping("/resume")
    public ResultBean<PaidCollectVO> resume() {
        service.resume();
        return ResultBean.ok(service.progress());
    }

    @Operation(summary = "停止采集", description = "请求停止，当前页跑完后退出")
    @PostMapping("/stop")
    public ResultBean<PaidCollectVO> stop() {
        service.stop();
        return ResultBean.ok(service.progress());
    }
}
