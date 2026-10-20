package com.bookcollector.credential.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 保存接口凭证（书单链路八项）的请求。
 *
 * <h3>🔴 2026-10-15 起：八项全量提交，缺一项不收</h3>
 * 用户原话：「缺少任意一个，就提示书单配置信息不完整」。
 * 缺任一项都不能带着残配置发请求（静默降级最难查）。
 *
 * <p>⚠️ <b>synckey 已不在本请求里</b>（当天晚些用户拍板「自动生成吧，
 * 这个不用配置了」）：它由后端在每次书单请求发出前按本地时间生成
 * （{@code WereadCredentialStore#refreshSynckeyNow()}），表单只管理人填的八项。
 * {@code listUrl} 同样不在表单里（分类 / 榜单的地址只从库里读，
 * 不再有 yml 兜底），留空 = 保持原值不变。
 */
@Data
public class WereadCredentialRequest {

    @Schema(description = "登录态 skey（抓包得到）")
    private String skey;

    @Schema(description = "App 端请求头 channelid")
    private String channelid;

    @Schema(description = "App 端请求头 vid（与 skey 同一账号）")
    private String vid;

    @Schema(description = "App 端请求头 priority")
    private String priority;

    @Schema(description = "App 端请求头 accept-language")
    private String acceptLanguage;

    @Schema(description = "App 端请求头 basever（客户端版本号）")
    private String basever;

    @Schema(description = "App 端请求头 v（客户端版本号，与 basever 同值）")
    private String v;

    @Schema(description = "书单接口的基础地址（页面上的 sUrl）")
    private String booklistUrl;

    @Schema(description = "分类 / 榜单接口的基础地址。页面不暴露，留空 = 保持原值")
    private String listUrl;
}
