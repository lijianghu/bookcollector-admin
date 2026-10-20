import { request } from './request'
import type {
  BatchDeleteResult,
  Book,
  BookQuery,
  BookUpdateRequest,
  EnrichResult,
  PageResult,
} from '@/types'

/**
 * 图书接口（5 个）。
 *
 * 分页接口直接收 `BookQuery` 的 18 个查询参数 —— 后端 `BookQuery` 是
 * 一个普通 POJO，Spring 会按字段名绑定，所以前端只要把非空字段拼进 query 即可。
 */

/**
 * 拼查询参数：**跳过 `undefined` / `null` / 空字符串**。
 *
 * 为什么不直接把整个 query 对象丢给 axios：axios 会把 `undefined` 也序列化掉没问题，
 * 但空字符串会被发成 `?author=`，而后端 `resolveAuthor()` 的 `blankToNull`
 * 会把它当「没传」—— 结果对，但 URL 里全是空参数，排查时看着累。
 *
 * 另外注意 `asc: false` 和 `page: 0` 这类**假值必须保留**，
 * 所以判断条件是 `!== undefined && !== null && !== ''`，不能用 `if (value)`。
 */
function toParams(query: Record<string, unknown>): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  Object.keys(query).forEach((key) => {
    const value = query[key]
    if (value !== undefined && value !== null && value !== '') {
      params[key] = value
    }
  })
  return params
}

/** 分页查询图书 */
export function pageBooks(query: BookQuery) {
  return request<PageResult<Book>>({
    url: '/books',
    method: 'get',
    params: toParams(query as Record<string, unknown>),
  })
}

/** 图书详情。不存在 → 后端返 code=404 */
export function getBook(bookId: string) {
  return request<Book>({
    url: `/books/${encodeURIComponent(bookId)}`,
    method: 'get',
  })
}

/**
 * 编辑图书。
 *
 * ⚠️ 后端是**局部更新**：只写你传了的字段。
 * 所以这里只提交用户真正改过的字段，不要图省事把整个 Book 回传 ——
 * 那会把 `newRating` / `readingCount` 这些「接口给的、用户不能改的」字段一起写进去。
 */
export function updateBook(bookId: string, data: BookUpdateRequest) {
  return request<Book>({
    url: `/books/${encodeURIComponent(bookId)}`,
    method: 'put',
    data,
  })
}

/** 删除单本图书 */
export function deleteBook(bookId: string) {
  return request<BatchDeleteResult>({
    url: `/books/${encodeURIComponent(bookId)}`,
    method: 'delete',
  })
}

/** 批量删除。返回**实际**删除数（请求里可能有 bookId 已经不存在了） */
export function batchDeleteBooks(bookIds: string[]) {
  return request<BatchDeleteResult>({
    url: '/books/batch-delete',
    method: 'post',
    data: { bookIds },
  })
}

/**
 * 完善书籍信息（2026-10-16）—— **提交即返回，不等它跑完**。
 *
 * 把书单链路漏掉的「简介 / 分类」以及一批原来没建模的字段，用 `/book/info` 逐本补齐。
 * 一页最多 200 本、5 并发 + 每请求随机等 1~5 秒，可能要几分钟；同步等会被网关掐断。
 * 所以提交后拿返回的快照开始轮询 {@link getEnrichProgress}。
 *
 * 两种失败直接返回 code=400（拦截器已弹提示）：`bookIds` 为空、已有补齐在跑。
 */
export function enrichBooks(bookIds: string[]) {
  return request<EnrichResult>({
    url: '/books/enrich',
    method: 'post',
    data: { bookIds },
  })
}

/** 完善书籍信息的进度快照。没跑过时返回一份「全零、未运行」的对象 */
export function getEnrichProgress() {
  return request<EnrichResult>({
    url: '/books/enrich/progress',
    method: 'get',
  })
}
