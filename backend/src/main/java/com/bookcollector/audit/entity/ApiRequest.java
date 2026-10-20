package com.bookcollector.audit.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;

/**
 * 请求审计记录。原 MySQL 的 {@code api_requests} 表。
 *
 * <p>每成功/失败地打完一次平台接口，就落一条。用途：
 * <ul>
 *   <li>排查「某页为什么没数据」—— 能看到 statusCode / resultCount / errorMsg，
 *       以及 <b>2026-10-15 新增的完整现场</b>：queryParams / requestHeaders /
 *       rawResponse（配合审计详情页，用来区分「接口没给数据」与「给了没解析出来」）</li>
 *   <li>观测接口健康度——responseTimeMs 的分布</li>
 * </ul>
 *
 * <p>🔴 敏感字段不进 toString：{@link #rawResponse} 可达 8KB、
 * {@link #requestHeaders} 带登录态（skey 已掩码）—— 三个新字段都加了
 * {@code @ToString.Exclude}，别为了「调试方便」撤掉。
 *
 * <p>写入量大（21 分类 × 数百页），所以只做「插入」，不做更新，也不建太多索引。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "api_requests")
public class ApiRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 归属的运行 ID（collect_task_runs.id）。手工探针触发时为空 */
    private String runId;

    /** 采集目标类型：CATEGORY / RANKING */
    private String targetType;

    /** 采集目标 ID："300000" / "rising" */
    private String targetId;

    /** 第几页，从 1 开始（人看的） */
    private Integer pageIndex;

    /** 本次请求用的 maxIndex（游标值，机器看的） */
    private Integer maxIndex;

    /** 完整请求 URL */
    private String url;

    /** HTTP 状态码 */
    private Integer statusCode;

    /** 耗时（毫秒） */
    private Integer responseTimeMs;

    /** 响应里的 synckey */
    private Long synckey;

    /** 响应里的 totalCount（该分类/榜单的总书数） */
    private Integer totalCount;

    /** 响应里的 hasMore */
    private Boolean hasMore;

    /** 本页返回的图书条数 */
    private Integer resultCount;

    /** 失败时的错误摘要。成功时为 null */
    private String errorMsg;

    /**
     * URL 上的 query 参数（key→value，原样编码态）—— 2026-10-15 新增。
     *
     * <p>{@code ?} 后面的键值对拆开存，与 {@link #url} 内容一致。
     * 排查「参数发对了没」时不用盯着一长串 URL 找。
     * ⚠️ 历史记录没有这个字段（读出来是 null），前端要按「没有」处理。
     */
    @ToString.Exclude
    private Map<String, String> queryParams;

    /**
     * 请求头快照 —— 2026-10-15 新增。
     *
     * <p>🔴 {@code skey} 只存<b>掩码值</b>（前 2 位 + {@code ****}）：
     * 明文登录态绝不落库（用户 2026-10-08 立的规则，掩码实现在
     * {@code collector.WereadHeaders#snapshot}）。
     *
     * <p>拼不出 URL（凭证没配）时为 null —— 域名未知就无法判断该用哪套头。
     */
    @ToString.Exclude
    private Map<String, String> requestHeaders;

    /**
     * 返回原始响应 body 原文 —— 2026-10-15 新增。
     *
     * <p>⚠️ 截断规则按请求类型分（2026-10-15 改，别再当成「统一 8000」）：
     * <ul>
     *   <li><b>书单（BOOKLIST）整份存</b>：一次给全、不分页、接口上限 500 本（≈1MB），
     *       且 books 数组埋在响应最深处 —— 截断了用户在审计详情里就看不见形状，
     *       「自己定位」的链路会断。</li>
     *   <li><b>列表/榜单截到 {@value #RAW_RESPONSE_MAX} 字符</b>：有分页、单次数十 KB、
     *       量大，全存会把审计集合撑爆。截断处会拼 {@code "..."} 提示被截断。</li>
     * </ul>
     *
     * <p>🔴 它是「排查数据访问 vs 解析」的关键：{@link #resultCount} 是 0 时，
     * 看一眼 body 就知道是接口没给数据（数据访问问题），还是给了但我们没解析出来
     * （解析问题）。
     *
     * <p>@ToString.Exclude：整份 body 不该被 toString 带进日志。
     * 失败请求（没发出 / 无 body）为 null。
     */
    @ToString.Exclude
    private String rawResponse;

    /** {@link #rawResponse} 的截断上限（字符数）。再长只留开头 */
    public static final int RAW_RESPONSE_MAX = 8000;

    /** 请求时间。走 {@code save()} 时由审计自动填充 */
    @CreatedDate
    private Date createdAt;
}
