import { request } from './request'
import type { Book, PageResult, StatsCharts, StatsOverview } from '@/types'

/**
 * 仪表盘统计接口（3 个）。
 *
 * <h3>为什么拆成两个端点而不是一个</h3>
 * 「4 个数字」和「4 张图」的耗时差一个数量级：数字是 4 个 `count`，
 * 图表是 4 条聚合管道（其中分类分布还要 `$unwind` 5 万份文档的数组字段）。
 * 合成一个端点会让**数字必须等图表算完**才出现 —— 页面上 4 个指标卡
 * 空白转圈好几秒，体验很差。拆开之后指标卡先出来，图表各自 loading。
 *
 * <h3>「今日采集数」的口径</h3>
 * 按 `firstCollectedAt`（净新增），**不是** `lastCollectedAt`。
 * 后者每次 upsert 都刷新，重采同一批书会让它「今天又采了一万本」。
 * 图表「近 7 天采集量」用同一个口径，保证指标卡和图表不会互相矛盾。
 */

/** 4 个指标卡 + 上下文数字。**先加载这个** */
export function getOverview() {
  return request<StatsOverview>({
    url: '/stats/overview',
    method: 'get',
  })
}

/** 4 张图的数据。空桶 / 空日期后端已补齐，前端直接喂 ECharts 即可 */
export function getCharts() {
  return request<StatsCharts>({
    url: '/stats/charts',
    method: 'get',
  })
}

/**
 * 「今日新增」图书明细。点仪表盘那张指标卡弹出的列表。
 *
 * ⚠️ 后端用的是**同一个条件**（`firstCollectedAt >= 今天零点，Asia/Shanghai`），
 * 所以这里的 `total` 必然等于指标卡上的 `todayCollected`。
 * 两者对不上就说明有一边的口径被改坏了。
 */
export function getTodayCollected(params: { page?: number; size?: number }) {
  return request<PageResult<Book>>({
    url: '/stats/today-collected',
    method: 'get',
    params,
  })
}
