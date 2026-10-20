package com.bookcollector.credential.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 接口凭证的视图 —— <b>2026-10-15 起明文回显</b>。
 *
 * <h3>🔴 为什么不再掩码（用户 2026-10-15 明确决定）</h3>
 * 原口径是「skey 只回掩码、输入框不回填」，因为「前端拿不到明文」被当作
 * 敏感信息最小暴露的手段。但书单凭证从 3 项扩到 8 项之后，掩码语义
 * （留空=不改）让表单没法回显已存的值 —— 用户每次改一个字段都得把
 * skey 重新输一遍，这反而催生「复制粘贴到聊天工具」这种更大的泄漏面。
 * 新口径：<b>表单明文回显、明文保存</b>；敏感值（skey / synckey）的防线收敛到
 * <b>不进注释、不进日志、不进 toString</b>（见 {@code WereadCredential} 的
 * {@code @ToString.Exclude} 与 {@code WereadCredentialStore} 的日志口径）。
 *
 * <h3>⚠️ synckey 是只读展示，没有输入框</h3>
 * 用户当天拍板「自动生成吧，这个不用配置了」，当晚又定了取值口径：
 * 「synckey 不是从 root.synckey 取，而是后端 new Date() 的秒值」——
 * 它由 {@code WereadCredentialStore#refreshSynckeyNow()} 在每次书单请求
 * 发出前按本地时间生成并刷新。VO 回的是<b>最近一次请求用的值</b>，
 * 只为在表单下方显示，并可与请求审计对照。
 */
@Data
@Builder
public class WereadCredentialVO {

    @Schema(description = "登录态（明文，供回显）")
    private String skey;

    @Schema(description = "最近一次书单请求使用的 synckey（后端按请求时刻本地时间生成，只读展示；表单没有它的输入框）")
    private String synckey;

    @Schema(description = "App 端请求头 channelid")
    private String channelid;

    @Schema(description = "App 端请求头 vid")
    private String vid;

    @Schema(description = "App 端请求头 priority")
    private String priority;

    @Schema(description = "App 端请求头 accept-language")
    private String acceptLanguage;

    @Schema(description = "App 端请求头 basever")
    private String basever;

    @Schema(description = "App 端请求头 v")
    private String v;

    @Schema(description = "书单接口的基础地址（页面上的 sUrl）")
    private String booklistUrl;

    @Schema(description = "分类 / 榜单接口的基础地址")
    private String listUrl;

    @Schema(description = "书单链路是否可用（八项凭证全配了）")
    private boolean booklistReady;

    @Schema(description = "最近保存时间")
    private Date updatedAt;
}
