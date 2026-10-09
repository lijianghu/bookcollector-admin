package com.bookcollector.audit.repository;

import com.bookcollector.audit.dto.RequestSummary;
import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.common.PageResult;
import com.bookcollector.util.PageQueryUtil;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求审计的查询（FR-G2）。
 *
 * <h3>为什么查询单独一个类，不塞进写入用的 {@link ApiRequestRepository}</h3>
 * 写入那个类有一个非常明确的约束：<b>它只插不改，且失败不抛异常</b>
 * （因为审计不能影响采集主流程）。查询的约束完全相反 —— 它要能被上层的
 * 异常处理器正常兜住，失败了应该报错。两者的「错误处理哲学」不同，
 * 混在一个类里迟早有人把 {@code try-catch 吞掉} 复制到查询方法上。
 *
 * <h3>筛选条件只有一个来源</h3>
 * 列表（{@link #pageQuery}）与摘要（{@link #summary}）必须看到<b>同一批记录</b>：
 * 「列表说 259 条、摘要说 261 次」这种对不上的数字，会直接毁掉这一页的可信度。
 * 所以条件拼装收敛在 {@link #conditions}，两处共用。
 */
@Repository
public class ApiRequestQueryRepository {

    /** P95 的分位点。写成常量，别让 0.95 散在代码里 */
    private static final double P95 = 0.95D;

    private final MongoTemplate mongoTemplate;

    public ApiRequestQueryRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 分页查询。
     *
     * <p>分页样板（count 短路 / 排序 / skip+limit）统一走 {@link PageQueryUtil}；
     * 本类只负责「条件怎么拼」和「按什么排」。
     *
     * @param runId       归属运行 ID，可空
     * @param targetType  CATEGORY / RANKING，可空
     * @param targetId    目标 ID，可空
     * @param onlyFailed  true = 只看失败（{@code statusCode != 200} 或 errorMsg 非空）
     */
    public PageResult<ApiRequest> pageQuery(String runId, String targetType, String targetId,
                                            Boolean onlyFailed, int page, int size) {
        // 主排序 createdAt 倒序（新的在前）+ _id 升序兜底，
        // 保证同一毫秒写入的多条记录翻页不重不漏
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.ASC, "_id"));
        return PageQueryUtil.getPageResult(mongoTemplate, page, size,
                combine(conditions(runId, targetType, targetId, onlyFailed)), ApiRequest.class, sort);
    }

    /**
     * 聚合摘要：当前筛选条件下的请求次数、失败次数与耗时分布。
     *
     * <p>与 {@link #pageQuery} 用<b>同一套筛选条件</b>，但不分页 —— 摘要是
     * 「这一批请求整体怎么样」，不是「当前这一页怎么样」。
     *
     * <p>为什么不把三次统计拼成一条 {@code $facet} 管道：这里的集合只有几千条，
     * 多两次往返的代价远小于把 {@code $match} 条件在管道里再拼一遍 ——
     * 拼错了不报错，只会静默算少。
     *
     * @param runId       归属运行 ID，可空
     * @param targetType  CATEGORY / RANKING，可空
     * @param targetId    目标 ID，可空
     * @param onlyFailed  true = 只看失败（此时 {@code failed == total}，是符合预期的）
     */
    public RequestSummary summary(String runId, String targetType, String targetId, Boolean onlyFailed) {
        List<Criteria> conds = conditions(runId, targetType, targetId, onlyFailed);

        RequestSummary result = new RequestSummary();
        result.setTotal(mongoTemplate.count(Query.query(combine(conds)), ApiRequest.class));

        // 失败判据必须和列表页的「只看失败」完全一致，所以复用同一个 failedCriteria()
        List<Criteria> failedConds = new ArrayList<Criteria>(conds);
        failedConds.add(failedCriteria());
        result.setFailed(mongoTemplate.count(Query.query(combine(failedConds)), ApiRequest.class));

        fillLatency(result, conds);
        return result;
    }

    // ==================================================================
    // 内部：耗时统计
    // ==================================================================

    /**
     * 填平均 / P95 / 最慢耗时。
     *
     * <p>只统计「有耗时」的记录：{@code responseTimeMs} 缺失时 {@code $avg} 会跳过它，
     * 但算 P95 要按下标去取第 n 条，两处口径必须一致 —— 所以显式先过滤掉 null。
     *
     * <p>先数条数再聚合：条数既是 P95 下标（「第几条」）的来源，
     * 也让「一条都没有」的情况直接短路，不用再去查一次 avg/max。
     */
    private void fillLatency(RequestSummary result, List<Criteria> conds) {
        List<Criteria> latencyConds = new ArrayList<Criteria>(conds);
        latencyConds.add(Criteria.where("responseTimeMs").ne(null));
        Criteria latency = combine(latencyConds);

        long n = mongoTemplate.count(Query.query(latency), ApiRequest.class);
        if (n <= 0) {
            // 三个字段保持 null，前端显示 "-" —— 不是 0，0ms 是「很快」不是「没有数据」
            return;
        }

        List<Document> rows = mongoTemplate.aggregate(Aggregation.newAggregation(
                        Aggregation.match(latency),
                        Aggregation.group()
                                .avg("responseTimeMs").as("avgMs")
                                .max("responseTimeMs").as("maxMs")),
                ApiRequest.class, Document.class).getMappedResults();
        if (!rows.isEmpty()) {
            Document row = rows.get(0);
            result.setAvgMs(toInt(row.get("avgMs")));
            result.setMaxMs(toInt(row.get("maxMs")));
        }
        result.setP95Ms(percentile(latency, n));
    }

    /**
     * P95 耗时：把耗时升序排好，取第 {@code ⌈0.95n⌉} 条。
     *
     * <p>为什么不用 Mongo 7.0 的 {@code $percentile} 累加器：本机确实是 7.0.30（支持它），
     * 但它是<b>近似</b>算法，而这里的数据量（几千条）用精确取值完全够；
     * 少一个「依赖服务端版本 + 依赖近似精度」的隐性前提，排查时也更简单。
     *
     * <p>{@code $skip} 只接受字面量，所以「第几条」只能在 Java 侧算出来再查一次 ——
     * 这也是 {@link #fillLatency} 要先把条数查出来的原因。
     *
     * <p>排序字段没有索引，是内存排序；集合只有几千条，远够不到 32MB 的排序上限。
     */
    private Integer percentile(Criteria criteria, long n) {
        if (n <= 0) {
            return null;
        }
        // 第 ⌈0.95n⌉ 条（1 起）→ 它前面有 rank-1 条，即 $skip 的值
        long rank = (long) Math.ceil(n * P95);
        long skip = Math.max(0L, rank - 1);
        List<ApiRequest> rows = mongoTemplate.aggregate(Aggregation.newAggregation(
                        Aggregation.match(criteria),
                        Aggregation.sort(Sort.Direction.ASC, "responseTimeMs"),
                        Aggregation.skip(skip),
                        Aggregation.limit(1)),
                ApiRequest.class, ApiRequest.class).getMappedResults();
        return rows.isEmpty() ? null : rows.get(0).getResponseTimeMs();
    }

    // ==================================================================
    // 内部：条件拼装
    // ==================================================================

    /**
     * 把筛选条件拆成一组 Criteria（空条件返回空列表，等价于「全部」）。
     *
     * <p>拆成列表而不是直接合成一个 Criteria，是因为摘要要在此基础上
     * <b>再与一个条件相与</b>（失败判据 / 耗时非空）。往一个已经
     * {@code $and} 起来的 Criteria 上再 {@code .and(...)} 拼，读起来太绕。
     */
    private List<Criteria> conditions(String runId, String targetType, String targetId, Boolean onlyFailed) {
        List<Criteria> conds = new ArrayList<Criteria>();
        if (notBlank(runId)) {
            conds.add(Criteria.where("runId").is(runId.trim()));
        }
        if (notBlank(targetType)) {
            conds.add(Criteria.where("targetType").is(targetType.trim().toUpperCase()));
        }
        if (notBlank(targetId)) {
            conds.add(Criteria.where("targetId").is(targetId.trim()));
        }
        if (Boolean.TRUE.equals(onlyFailed)) {
            conds.add(failedCriteria());
        }
        return conds;
    }

    /** 把一组条件合成一个 Criteria；空列表 → 空 Criteria（等价于「全部」） */
    private Criteria combine(List<Criteria> conds) {
        if (conds.isEmpty()) {
            return new Criteria();
        }
        if (conds.size() == 1) {
            return conds.get(0);
        }
        return new Criteria().andOperator(conds.toArray(new Criteria[0]));
    }

    /**
     * 失败判据：{@code statusCode != 200} <b>或</b> {@code errorMsg} 非空。
     *
     * <p>两个判据是「或」关系：有些失败是「HTTP 200 但 body 里没有 books 数组」，
     * 只看状态码会漏掉它们。列表页的「只看失败」与摘要里的「失败次数」
     * 必须走同一个实现，否则同一个页面上两个数字会互相矛盾。
     */
    private Criteria failedCriteria() {
        return new Criteria().orOperator(
                Criteria.where("statusCode").ne(200),
                Criteria.where("errorMsg").ne(null));
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    /** {@code $avg} 返回的是 Double，取整到毫秒即可 —— 界面上不展示小数 */
    private Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }
}
