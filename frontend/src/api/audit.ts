import { request } from './request'
import type { ApiRequest, ApiRequestQuery, PageResult, RequestSummary } from '@/types'

/**
 * 请求审计接口（2 个：列表 + 摘要）。
 *
 * 这一层记录**每一次对微信读书接口的 HTTP 请求**（由 `ApiRequestRepository`
 * 在采集过程中写入）。它是排查「为什么这批数据不对」的唯一抓手：
 * 能看到每一页的 `statusCode` / `responseTimeMs` / `totalCount` / `hasMore`。
 *
 * ⚠️ 写入侧的约束是「只插不改、失败不抛异常」—— 审计写失败绝不能影响采集。
 * 所以这里的数据可能有缺口（某次请求没记上），不能当账本用。
 * 查询侧则相反，条件写错就该报错。
 *
 * ⚠️ **只读**：后端不提供删除接口，前端也不该做「清空日志」的按钮。
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
