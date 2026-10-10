package com.bookcollector.refresh.controller;

import com.bookcollector.common.ResultBean;
import com.bookcollector.refresh.req.RefreshConfigRequest;
import com.bookcollector.refresh.service.RefreshService;
import com.bookcollector.refresh.vo.RefreshJobVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时刷新（7 个榜单 + 期刊杂志）。
 *
 * <p>只有 3 个端点，而且<b>没有</b>「查历史批次」的接口 —— 本功能只保留
 * 最近一次的执行结果（见 {@code RefreshJob.lastDetails}）。这是刻意的取舍：
 * 用户 2026-10-08 要的是「一键把新数据采回来」，
 * 不是又一套运行流水台账（那套已经有了，在「任务配置」里）。
 *
 * <p>鉴权由 {@code WebMvcConfig} 统一拦截 {@code /api/**} 覆盖，这里不用额外声明。
 */
@Tag(name = "09. 增量配置", description = "7 个榜单 + 期刊杂志的定期全量刷新（不落游标、不建任务）")
@RestController
@RequestMapping("/api/refresh")
public class RefreshController {

    private final RefreshService service;

    public RefreshController(RefreshService service) {
        this.service = service;
    }

    @Operation(summary = "刷新配置与最近一次结果",
            description = "一个接口拿齐页面全部数据：cron / 是否启用 / 下次触发时间 / "
                    + "8 个目标清单 / 最近一次执行状态与各目标明细")
    @GetMapping("/job")
    public ResultBean<RefreshJobVO> job() {
        return ResultBean.ok(service.getJob());
    }

    @Operation(summary = "修改配置",
            description = "cron / enabled 都可选（null = 不改）。cron 必须是 6 段式"
                    + "（秒 分 时 日 月 周）。改完立即生效，不需要重启后端")
    @PutMapping("/job")
    public ResultBean<RefreshJobVO> update(@Validated @RequestBody RefreshConfigRequest req) {
        return ResultBean.ok(service.updateConfig(req.getCron(), req.getEnabled()));
    }

    @Operation(summary = "立即执行",
            description = "提交后立即返回（不等采集跑完），前端轮询 GET /api/refresh/job 跟进进度。"
                    + "若已有批次在跑，或某个目标正被手动采集占用，会返回 code=400 并给出原因")
    @PostMapping("/trigger")
    public ResultBean<RefreshJobVO> trigger() {
        return ResultBean.ok(service.trigger());
    }
}
