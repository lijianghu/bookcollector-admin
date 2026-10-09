package com.bookcollector.audit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 请求审计的聚合摘要。
 *
 * <h3>为什么要有它</h3>
 * 审计列表回答的是「某一条请求怎么样」，而「这批请求整体健不健康」要靠聚合 ——
 * 靠人逐页翻 200 条记录去数失败次数，是把原始日志当报表用。
 *
 * <h3>口径：与列表完全相同的筛选条件，但不分页</h3>
 * 摘要是「当前筛选条件下<b>全部</b>请求」的统计，不是「当前这一页」的统计。
 * 后者会随翻页跳动，没有任何诊断意义。
 *
 * <h3>P95 为什么比平均值重要</h3>
 * 上游变慢时，平均值往往只是从 400ms 爬到 600ms，而 P95 会从 800ms 跳到 5s。
 * 「偶尔卡一下」才是采集变慢的真实形态，平均值会把它抹平。
 *
 * @see com.bookcollector.audit.repository.ApiRequestQueryRepository#summary
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前筛选条件下的请求总次数 */
    private long total;

    /** 其中失败的次数（{@code statusCode != 200} 或有 errorMsg）。判据与列表页的「只看失败」一致 */
    private long failed;

    /** 平均耗时（ms）。一条可统计的记录都没有时为 null */
    private Integer avgMs;

    /** P95 耗时（ms）。同上 */
    private Integer p95Ms;

    /** 最慢一次（ms）。同上 */
    private Integer maxMs;
}
