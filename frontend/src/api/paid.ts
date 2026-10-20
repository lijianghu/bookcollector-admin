import { request } from './request'
import type { PaidCategoryVO, PaidCollectVO } from '@/types'

/**
 * 付费精选配置接口（2026-10-20）。
 *
 * 列表 = `taxonomy_config` 里一级 + enabled=true 的分类；
 * 「启动」按 `filterCategory` 分页采集付费精选书籍，进度用 `getPaidProgress` 轮询。
 */

/** 可采集的分类（一级 + enabled=true） */
export function listPaidCategories() {
  return request<PaidCategoryVO[]>({
    url: '/paid/categories',
    method: 'get',
  })
}

/**
 * 启动某个分类的采集（提交即返回）。
 *
 * @param reset `true` = 清掉断点从头采；默认 `false` = 有断点就从断点继续
 */
export function startPaidCollect(code: string, reset = false) {
  return request<PaidCollectVO>({
    url: `/paid/categories/${encodeURIComponent(code)}/collect`,
    method: 'post',
    params: reset ? { reset: true } : undefined,
  })
}

/** 采集进度（全局一份） */
export function getPaidProgress() {
  return request<PaidCollectVO>({
    url: '/paid/progress',
    method: 'get',
  })
}

/** 请求暂停（当前页跑完后原地等待，maxIdx 保留） */
export function pausePaidCollect() {
  return request<PaidCollectVO>({
    url: '/paid/pause',
    method: 'post',
  })
}

/** 继续采集 */
export function resumePaidCollect() {
  return request<PaidCollectVO>({
    url: '/paid/resume',
    method: 'post',
  })
}

/** 请求停止 */
export function stopPaidCollect() {
  return request<PaidCollectVO>({
    url: '/paid/stop',
    method: 'post',
  })
}
