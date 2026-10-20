package com.bookcollector.audit.service;

import com.bookcollector.audit.dto.ReplayRequest;
import com.bookcollector.audit.dto.ReplayResult;
import com.bookcollector.audit.dto.RequestDetail;
import com.bookcollector.audit.dto.RequestSummary;
import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.common.PageResult;

/**
 * 请求审计的查询业务（FR-G2）。
 *
 * <h3>为什么这里只有查询，没有删除</h3>
 * 审计记录的价值恰恰在于「事后还在」。提供一个「清空审计」入口，早晚会有人
 * 手滑点掉正在排查的那批数据。要清理请直接连库操作。
 *
 * <p>页码与每页条数的夹紧（{@code page>=1}、{@code size<=200}）在实现里，
 * 不让前端传 {@code size=100000} 把服务打爆。
 *
 * @see com.bookcollector.audit.service.impl.AuditServiceImpl
 */
public interface AuditService {

    /**
     * 分页查询（按 {@code createdAt} 倒序，{@code _id} 升序兜底）。
     *
     * @param runId      归属运行 ID，可空
     * @param targetType CATEGORY / RANKING，可空
     * @param targetId   目标 ID，可空
     * @param onlyFailed true = 只看失败（{@code statusCode != 200} 或 errorMsg 非空）
     * @param page       页码，从 1 开始；null 或 &lt;1 归一为 1
     * @param size       每页条数，默认 20，上限 200
     */
    PageResult<ApiRequest> page(String runId, String targetType, String targetId,
                                Boolean onlyFailed, Integer page, Integer size);

    /**
     * 聚合摘要：当前筛选条件下的请求次数、失败次数与耗时（平均 / P95 / 最慢）。
     *
     * <p>筛选条件与 {@link #page} 完全一致，但<b>不分页</b> ——
     * 摘要是「这一批请求整体怎么样」，不是「当前这一页怎么样」。
     *
     * @param runId      归属运行 ID，可空
     * @param targetType CATEGORY / RANKING，可空
     * @param targetId   目标 ID，可空
     * @param onlyFailed true = 只看失败（{@code statusCode != 200} 或 errorMsg 非空）
     */
    RequestSummary summary(String runId, String targetType, String targetId, Boolean onlyFailed);

    /**
     * 单条详情：审计记录 + 它所属运行的步骤日志（2026-10-15 新增）。
     *
     * <p>给「一次请求为什么采到 0 条」做最终定位用：记录回答「接口返了什么」，
     * 日志回答「主循环当时进行到哪一步、有没有报错」。两者一起才分得清
     * 「数据访问问题」（接口没给数据）与「解析问题」（给了没解析出来）。
     *
     * @param id 审计记录 ID
     * @throws com.bookcollector.common.BizException 记录不存在（404 语义）
     */
    RequestDetail findDetail(String id);

    /**
     * 「回放」：用最原始的 HTTP 按<b>提交的请求</b>再发一次（2026-10-15 晚新增，当晚 redesign）。
     *
     * <p>用户诉求演进：「做一个『回放』按钮，后端使用最原始的 http 再次请求」→
     * 「url、参数和请求头都可以修改，按本次最新值发起请求，不用落库，只展示」。
     * 所以入参是这次要发的请求（{@link ReplayRequest}），不是记录 ID ——
     * 排查时最常做的就是拿当时那个请求改个参数试试。
     *
     * <p>三点口径：
     * <ul>
     *   <li>URL 是唯一真相：弹窗里的「参数」行是 query 的可编辑视图，后端不单独收 params</li>
     *   <li>skey 不信任入参（掩码值）：书单域名强制取当前内存凭证，其余域名发了也替换</li>
     *   <li>body 原样返回，不过 fastjson、不重序列化；<b>不写审计</b></li>
     * </ul>
     *
     * @param req 这次要发的请求
     * @throws com.bookcollector.common.BizException URL 为空；或书单域名但凭证八项缺一
     */
    ReplayResult replay(ReplayRequest req);
}
