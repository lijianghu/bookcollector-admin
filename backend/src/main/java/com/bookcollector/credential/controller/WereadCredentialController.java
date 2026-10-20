package com.bookcollector.credential.controller;

import com.bookcollector.common.ResultBean;
import com.bookcollector.credential.req.WereadCredentialRequest;
import com.bookcollector.credential.service.WereadCredentialService;
import com.bookcollector.credential.vo.WereadCredentialVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口凭证（「书单配置」页顶部的表单）。
 *
 * <h3>为什么不做成「设置」页里的通用 KV</h3>
 * 它与采集目标类型强耦合：分类 / 榜单走一个基础地址，书单走另一个。
 * 放进通用 KV 表里，改坏了的表现是「某个类型采不到数据」，
 * 而不是「配置项写错了」—— 这种间接关系最难查。
 *
 * <h3>🔴 2026-10-15：八项凭证 + 明文回显 + synckey 请求前生成</h3>
 * 用户把入口从「任务配置」挪到「书单配置」并亲自定了口径：八项全存库
 * （sUrl / skey / channelid / vid / priority / acceptLanguage /
 * basever / v），缺任一项保存即拒（「书单配置信息不完整」）；
 * GET 明文回显（他要回填表单），但 skey 绝不进注释与日志
 * （{@code WereadCredential} 上有 {@code @ToString.Exclude}）。
 * <b>synckey 不在表单里</b>：当天晚些用户拍板「自动生成吧，这个不用配置了」，
 * 当晚又定了口径「不是从 root.synckey 取，而是后端 new Date() 的秒值」——
 * 每次书单请求发出前由 {@code WereadCredentialStore#refreshSynckeyNow} 生成，
 * GET 回的只是「最近一次请求用的值」，供表单下方只读展示。
 *
 * <p>保存后<b>立即生效</b>，不需要重启：内存副本在这一刻被换掉（见 {@code WereadCredentialStore}）。
 */
@Tag(name = "14. 阅读平台凭证", description = "接口基础地址与登录态（入库、脱敏读取）")
@RestController
@RequestMapping("/api/weread/credential")
public class WereadCredentialController {

    private final WereadCredentialService service;

    public WereadCredentialController(WereadCredentialService service) {
        this.service = service;
    }

    @Operation(summary = "读取凭证", description = "明文回显（用户要求可回填表单）；skey/synckey 不进日志")
    @GetMapping
    public ResultBean<WereadCredentialVO> get() {
        return ResultBean.ok(service.get());
    }

    @Operation(summary = "保存凭证",
            description = "八项书单凭证全量校验，缺任一项即拒（书单配置信息不完整）；保存后立即生效")
    @PostMapping
    public ResultBean<WereadCredentialVO> save(@RequestBody WereadCredentialRequest req) {
        return ResultBean.ok(service.save(req));
    }
}
