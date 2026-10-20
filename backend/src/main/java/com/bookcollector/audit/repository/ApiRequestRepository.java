package com.bookcollector.audit.repository;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.collector.dto.WereadPage;
import com.bookcollector.collector.dto.WereadRawResponse;
import com.bookcollector.common.enums.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * 请求审计落库 —— 原 MySQL 的 {@code api_requests} 表。
 *
 * <p>每打完一次接口就落一条，用途：
 * <ul>
 *   <li>排查「某页为什么没数据」—— 能看到 statusCode / resultCount / errorMsg</li>
 *   <li>观测接口健康度 —— {@code responseTimeMs} 的分布</li>
 * </ul>
 *
 * <p><b>只插不改</b>。写入量大（21 分类 × 数百页），改一条审计记录没有意义。
 *
 * <p>审计失败<b>不抛异常</b>：它不该影响采集主流程。
 *
 * <p>注解从 {@code @Component} 改为 {@code @Repository}：语义上它确实是数据访问层，
 * 与 {@link ApiRequestQueryRepository} 对称（后者一直是 {@code @Repository}）。
 */
@Repository
public class ApiRequestRepository {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestRepository.class);

    private final MongoTemplate mongoTemplate;

    public ApiRequestRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 记录一次分页请求。
     *
     * <p>2026-10-15：除原有摘要字段外，把「完整现场」一并落库 ——
     * queryParams（URL 参数）/ requestHeaders（请求头快照，skey 已掩码）/
     * rawResponse（body 原文）。⚠️ rawResponse 的截断规则按类型分：
     * 书单（BOOKLIST）整份存（不分页、低频、约 1MB），列表/榜单截断到
     * {@value ApiRequest#RAW_RESPONSE_MAX} 字符。
     * 用户要的是「点重试后自己判断是数据访问问题还是解析问题」，
     * 这三样就是判断依据。调用方 {@code CollectLoop} 无需改动 ——
     * 它早就把 {@code resp} 整个传进来了。
     *
     * @param runId     归属的运行 ID，可为 null（手工探针触发时）
     * @param type      目标类型
     * @param targetId  目标 ID
     * @param pageIndex 第几页（从 1 开始，给人看的）
     * @param maxIndex  本次用的游标值（给机器看的）
     * @param resp      原始响应
     * @param page      解析后的信封；解析失败时传 null
     * @param resultCount 本页返回的记录条数
     */
    public void recordPage(String runId, TargetType type, String targetId,
                           int pageIndex, int maxIndex,
                           WereadRawResponse resp, WereadPage page, int resultCount) {
        try {
            ApiRequest record = ApiRequest.builder()
                    .runId(runId)
                    .targetType(type == null ? null : type.name())
                    .targetId(targetId)
                    .pageIndex(pageIndex)
                    .maxIndex(maxIndex)
                    .url(resp.getUrl())
                    .statusCode(resp.getStatusCode())
                    .responseTimeMs((int) resp.getCostMs())
                    .resultCount(resultCount)
                    // 措辞和「任务失败原因」共用同一个实现，界面上两处说法才不会打架。
                    // 注意这里**不是**只看 resp.getError()：HTTP 4xx/5xx 时 error 是 null，
                    // 只有 statusCode 有值，光看 error 会让审计页的「原因」一栏空着。
                    .errorMsg(truncate(resp.describeFailure(), 500))
                    // ---- 2026-10-15 新增：完整发现场 ----
                    .queryParams(resp.getQueryParams())
                    .requestHeaders(resp.getRequestHeaders())
                    // 🔴 2026-10-15：书单响应整份存、不截断。
                    // 书单一次给全（不分页），接口上限 500 本 ≈ 1MB；而 books 数组
                    // 埋在响应最深处 —— 之前统一截到 8000 字符，「书单任务 0 本」
                    // 在审计详情里根本看不见 books 长什么样，用户自己定位的链路
                    // 断在这里（本次 499 本全跳过就是这么白绕一圈的）。
                    // 列表/榜单照旧截 8000：那些有分页、单次数十 KB、量大，全存撑库。
                    // 书单请求本身低频（一个任务一次，无翻页），整份存没有量的风险。
                    .rawResponse(type == TargetType.BOOKLIST
                            ? resp.getBody()
                            : truncate(resp.getBody(), ApiRequest.RAW_RESPONSE_MAX))
                    .build();

            if (page != null) {
                record.setSynckey(page.getSynckey());
                record.setTotalCount(page.getTotalCount());
                record.setHasMore(page.isHasMore());
            }

            mongoTemplate.insert(record);
        } catch (Exception e) {
            log.warn("写请求审计失败（{}/{} 第 {} 页）：{}", type, targetId, pageIndex, e.getMessage());
        }
    }

    /** 错误信息可能很长（含整个 HTML 响应），截断后再存 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
