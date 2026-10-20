import { request } from './request'
import type {
  ApiRequest,
  ApiRequestQuery,
  PageResult,
  ReplayRequest,
  ReplayResult,
  RequestDetail,
  RequestSummary,
} from '@/types'

/**
 * 请求审计接口（4 个：列表 + 摘要 + 详情 + 回放）。
 *
 * 这一层记录**每一次对平台接口的 HTTP 请求**（由 `ApiRequestRepository`
 * 在采集过程中写入）。它是排查「为什么这批数据不对」的唯一抓手：
 * 能看到每一页的 `statusCode` / `responseTimeMs` / `totalCount` / `hasMore`，
 * 详情接口还带上完整现场（URL 参数 / 请求头 / 原始响应 / 步骤日志，2026-10-15 新增），
 * 回放接口按提交的 URL 与请求头用最原始 HTTP 重发（2026-10-15 晚新增，可编辑请求）。
 *
 * ⚠️ 写入侧的约束是「只插不改、失败不抛异常」—— 审计写失败绝不能影响采集。
 * 所以这里的数据可能有缺口（某次请求没记上），不能当账本用。
 * 查询侧则相反，条件写错就该报错。
 *
 * ⚠️ **只读**：后端不提供删除接口，前端也不该做「清空日志」的按钮。
 * 「回放」也是只读 —— 它不写审计（诊断动作不能污染采集次数的口径）。
 */

/** 分页查询请求记录 */
export function pageRequests(query: ApiRequestQuery) {
  const params: Record<string, unknown> = {}
  if (query.runId) {
    params.runId = query.runId
  }
  if (query.targetType) {
    params.targetType = query.targetType
  }
  if (query.targetId) {
    params.targetId = query.targetId
  }
  // ⚠️ onlyFailed=false 是「有意义的假值」（显式要求看全部），
  // 但后端只判断 Boolean.TRUE，所以传 false 等价于不传。这里按「不传」处理，URL 更干净
  if (query.onlyFailed === true) {
    params.onlyFailed = true
  }
  if (query.page !== undefined) {
    params.page = query.page
  }
  if (query.size !== undefined) {
    params.size = query.size
  }
  return request<PageResult<ApiRequest>>({
    url: '/requests',
    method: 'get',
    params,
  })
}

/** 摘要的入参：列表那套筛选条件里去掉分页 */
export type RequestSummaryQuery = Pick<
  ApiRequestQuery,
  'runId' | 'targetType' | 'targetId' | 'onlyFailed'
>

/**
 * 聚合摘要（接口健康度）。
 *
 * 与 `pageRequests` 用同一套筛选条件，但**不分页** —— 回答的是
 * 「这一批请求整体怎么样」，所以它不随翻页变化。
 *
 * ⚠️ 刻意与列表分两次请求，而不是塞进分页结果：混在一起会让每次翻页都白拿一份聚合。
 */
export function getRequestSummary(query: RequestSummaryQuery) {
  const params: Record<string, unknown> = {}
  if (query.runId) {
    params.runId = query.runId
  }
  if (query.targetType) {
    params.targetType = query.targetType
  }
  if (query.targetId) {
    params.targetId = query.targetId
  }
  // ⚠️ 与 pageRequests 同一条约定：onlyFailed=false 是「有意义的假值」，
  // 但后端只判断 Boolean.TRUE，所以传 false 等价于不传。这里按「不传」处理，URL 更干净
  if (query.onlyFailed === true) {
    params.onlyFailed = true
  }
  return request<RequestSummary>({
    url: '/requests/summary',
    method: 'get',
    params,
  })
}

/**
 * 单条请求的完整详情（2026-10-15 新增）。
 *
 * 返回 = 审计记录（含 URL 参数 / 请求头快照 / 原始响应 body）
 * + 该次运行的核心步骤日志。一次采集采到 0 条时，用它区分：
 * - **数据访问问题**：`rawResponse` 里就没有书（接口没给）
 * - **解析问题**：`rawResponse` 里有书，但日志显示解析阶段异常
 *
 * ⚠️ `requestHeaders.skey` 是掩码值（后端不存明文登录态）。
 */
export function getRequestDetail(id: string) {
  return request<RequestDetail>({
    url: `/requests/${encodeURIComponent(id)}`,
    method: 'get',
  })
}

/**
 * 「回放」一次请求（2026-10-15 晚新增，当晚改成可编辑请求）。
 *
 * 后端用<b>最原始的 HTTP</b>（JDK HttpURLConnection，不走 RestTemplate / 拦截器）
 * 按**提交的请求**重发：URL 与请求头都是弹窗里改过的那份（URL 是唯一真相，
 * 「参数」行是 query 的可编辑视图，前端改行即重建 URL）。skey 后端用当前凭证的值替换
 * （提交的是掩码）。
 *
 * ⚠️ 三点：
 * - body 是原文，后端不过 fastjson、不重序列化，直接展示
 * - 不写审计（诊断动作不污染采集次数的口径）
 * - URL 为空、书单凭证缺项时后端拒绝并弹提示
 */
export function replayRequest(data: ReplayRequest) {
  return request<ReplayResult>({
    url: '/requests/replay',
    method: 'post',
    data,
  })
}
