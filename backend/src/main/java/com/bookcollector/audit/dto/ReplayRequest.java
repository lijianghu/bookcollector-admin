package com.bookcollector.audit.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 「回放」的请求体（2026-10-15 晚 redesign 新增）。
 *
 * <h3>为什么回放从「按记录 ID 原样重发」改成「提交要发的请求」</h3>
 * 用户看了第一版回放弹窗后要求：「url、参数和请求头都可以修改，按本次最新值
 * 发起请求」。原设计只收一个记录 ID、URL 用库里那份原样重发，
 * 满足不了「改一改再发」—— 排查时最常做的就是拿当时那个请求改个参数试试。
 * 所以入参变成「这次要发的请求」：前端把弹窗里可编辑的 URL 与请求头提交上来。
 *
 * <h3>⚠️ URL 是唯一真相，参数不从这里进</h3>
 * 弹窗里的「请求参数」行是<b>URL query 的可编辑视图</b>：改参数行 = 前端重建
 * query 串回写 URL；直接改 URL = 前端重新解析出行。所以后端<b>不单独收 params</b> ——
 * 收两份必然出现「两份不一致时听谁的」这种歧义。
 *
 * <h3>🔴 skey 不信任入参</h3>
 * 弹窗里的 skey 是掩码值（前端拿不到明文）。后端对 skey 头的处理：
 * 书单域名的请求<b>强制</b>用当前内存凭证的 skey（缺项直接拒），
 * 其余域名发了 skey 头的也一律替换成当前值 —— 绝不把 {@code Qp0****}
 * 这种掩码当真发出去。
 */
@Data
public class ReplayRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 这次要发的完整 URL（含 query）。空 / 全空白会被服务层拒掉 */
    private String url;

    /** 请求头（键值原样发送；{@code skey} 会被后端替换成当前凭证的值） */
    private Map<String, String> requestHeaders;
}
