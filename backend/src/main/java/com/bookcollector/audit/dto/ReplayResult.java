package com.bookcollector.audit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 「回放」一次审计请求的结果（2026-10-15 晚新增，当晚 redesign）。
 *
 * <h3>回放是什么</h3>
 * 用户诉求：「请求审计的请求详情弹窗，做一个『回放』按钮，后端使用最原始的 http
 * 再次请求，然后打印出输入url、参数、请求头、返回」。当晚看了第一版后又要求：
 * 「url、参数和请求头都可以修改，按本次最新值发起请求，不用落库，只展示」
 * —— 所以入参见 {@link ReplayRequest}，本类是「按提交的那份请求发出去之后」的现场。
 *
 * <h3>🔴 body 原样返回，不过 fastjson</h3>
 * 用户原话：「直接拿到最原始的 http 返回的 body，不要使用 fastjson 转换，
 * 将 json 展示出来」。所以 body 存的是 InputStream 读出来、按 UTF-8 解码后的
 * <b>原文</b> —— 不 parse、不重序列化。重序列化会改变键序与空白，
 * 而排查时要看的恰恰是「服务端到底发了什么字节」。
 * （{@link #responseSynckey} 是唯一的例外：只从 body 里<b>取一个字段</b>供栅格展示，
 * 失败不影响 body 本身，更不会改写入库的任何东西。）
 *
 * <p>{@code requestHeaders} 与审计记录同一口径：{@code skey} 是掩码值
 * （{@code WereadHeaders#snapshot}），明文登录态不进任何回显与落库路径。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplayResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实际发出的 URL（= 提交的那个，原样） */
    private String url;

    /** 实际发出 URL 的 query 拆解（编码态原样，从 {@link #url} 解析） */
    private Map<String, String> queryParams;

    /** 实际发送的请求头；{@code skey} 为掩码值（提交了真值就用真值，否则用当前凭证） */
    private Map<String, String> requestHeaders;

    /**
     * 本次发出的 skey 来自哪里（2026-10-15 深夜新增）。
     *
     * <p>回答用户那句「除了我提供的 url / 参数 / 请求头，你额外做了什么」——
     * skey 是唯一一处<b>可能不照抄提交值</b>的地方，必须让它在界面上自证：
     * <ul>
     *   <li>{@code SUBMITTED} —— 用了提交的真值（想完全复刻 Postman 时就是这种）</li>
     *   <li>{@code CREDENTIAL} —— 提交的是掩码或留空，已替换为当前凭证的 skey</li>
     *   <li>{@code null} —— 本次根本没发 skey 头（非书单域名且没提交这一行）</li>
     * </ul>
     */
    private String skeyFrom;

    /**
     * 响应形状的<b>一行指纹</b>（2026-10-15 深夜新增）—— 直接回答「为什么没有 bookInfo」。
     *
     * <p>书单响应可达约 90 万字符，让人在 body 里翻 {@code books[0]} 找 bookInfo 太费眼；
     * 这里把关键判据浓缩成一句，例如：
     * <ul>
     *   <li>{@code 错误信封：errcode=-2012（登录超时）} —— 登录态问题，不是形状问题</li>
     *   <li>{@code 书单响应 499 条；books[0] 无 bookInfo（拍平形态，元素即书籍对象），元素键=[…]}
     *       —— ⚠️ 这是<b>合法形态</b>，采集侧 {@code BookParser} 照常解析，不是错误</li>
     *   <li>{@code 书单响应 499 条；books[0] 带 bookInfo（包装形态）} —— 另一种合法形态</li>
     * </ul>
     *
     * <p>⚠️ 它只是<b>诊断指纹</b>，不是解析：body 仍原样返回；真正的解析口径在
     * {@code WereadPage} / {@code BookParser}，改那边时不用改这里（这里只做提示）。
     */
    private String shapeHint;

    /** HTTP 状态码；请求没发出去（网络异常等）时为 0 */
    private int statusCode;

    /** 耗时（毫秒） */
    private long costMs;

    /**
     * 响应 body 里的 {@code synckey}（书单的同步时间点）—— 只取一个字段展示，取不到为 {@code null}。
     *
     * <p>与审计记录里的 {@code synckey} 是两回事：那是<b>当时那次</b>请求响应带回的值；
     * 这个是<b>本次回放</b>响应带回的。排查时对照两者能看出数据源有没有推进。
     */
    private Long responseSynckey;

    /** 原始 body 原文（UTF-8 解码，未经任何解析 / 重序列化）；无响应体时为空串 */
    private String body;

    /** 失败原因（异常类型 + 消息）；成功为 {@code null} */
    private String errorMsg;
}
