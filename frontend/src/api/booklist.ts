import { request } from './request'
import type {
  Book,
  Booklist,
  BooklistBackfillVO,
  BooklistCollectVO,
  BooklistOption,
  BooklistPlatformCategory,
  BooklistQuery,
  BooklistSyncRequest,
  BooklistSyncVO,
  PageResult,
} from '@/types'

/**
 * 书单接口（5 个）。
 *
 * <h3>两组端点，读写在同一个 base 下</h3>
 * - 读：`GET /booklists`（列表）、`GET /booklists/categories`（16 个平台分类）、
 *   `GET /booklists/{booklistId}/books`（书单详情弹窗里的书）
 * - 写：`POST /booklists/sync`（链路 A，提交即返回）、`GET /booklists/sync/progress`（轮询进度）
 *
 * <h3>🔴 书单的请求地址与登录态都在库里，不在这里</h3>
 * 阅读平台那个接口的 base URL（页面上的 `sUrl`）和 `skey` 是**运行时配置**，
 * 存在后端库里、启动时加载进内存。所以本文件里**看不到**任何外部地址 ——
 * 这是刻意的（用户要求「URL 不放代码、不打印、不进文档」）。
 */

/**
 * 拼查询参数：**跳过 `undefined` / `null` / 空字符串**。
 *
 * 同 `api/book.ts` 的口径：`false` 和 `0` 这类**假值必须保留**，
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

/** 分页查询书单 */
export function pageBooklists(query: BooklistQuery) {
  return request<PageResult<Booklist>>({
    url: '/booklists',
    method: 'get',
    params: toParams(query as Record<string, unknown>),
  })
}

/**
 * 16 个平台分类（书单配置页顶部要平铺出来的那份清单）。
 *
 * ⚠️ 这份清单是后端**代码常量**（业务事实），不是从平台接口响应里读的 ——
 * 接口响应里的 `categories[]` 随请求而变，拿它当遍历源会静默漏掉分类。
 * 所以这里也不要自己在前端再写一份：两份清单迟早不一致。
 */
export function listBooklistCategories() {
  return request<BooklistPlatformCategory[]>({
    url: '/booklists/categories',
    method: 'get',
  })
}

/**
 * 全量书单的「ID + 名称」清单 —— 图书库页「书单」筛选下拉用（2026-10-16）。
 *
 * 刻意**不分页**（下拉要全量），后端只投影两个字段。
 * 名称还没回填时是 null，展示时用 `booklistId` 兜底。
 */
export function listBooklistOptions() {
  return request<BooklistOption[]>({
    url: '/booklists/options',
    method: 'get',
  })
}

/**
 * 同步书单（链路 A）。
 *
 * ⚠️ **提交后立即返回，不代表已经跑完** —— 一次全量同步要跑几十秒到几分钟。
 * 返回体里的 `running` 会是 true，调用方据此开始轮询 {@link getBooklistSyncProgress}。
 *
 * 两种失败会直接返回 code=400（拦截器已弹提示）：
 * - 已有同步在跑
 * - `categoryIds` 里有清单外的分类 ID（**不会静默忽略**）
 *
 * @param categoryIds 要同步的平台分类 ID。**不传 = 全部 16 个**
 */
export function syncBooklists(categoryIds?: string[]) {
  const data: BooklistSyncRequest = categoryIds && categoryIds.length > 0 ? { categoryIds } : {}
  return request<BooklistSyncVO>({
    url: '/booklists/sync',
    method: 'post',
    data,
  })
}

/** 同步进度快照。没跑过时返回一份「全零、未运行」的对象 */
export function getBooklistSyncProgress() {
  return request<BooklistSyncVO>({
    url: '/booklists/sync/progress',
    method: 'get',
  })
}

/**
 * 书单内的图书（分页）—— 书单详情弹窗用（2026-10-16 新增）。
 *
 * 后端匹配的是 `books.bookLists.bookListId`（书单引用数组，一本书属于
 * 多张书单时每个书单一条引用），所以返回的是「这首歌在书单里的快照」：
 * 同一本书出现在别的书单里不影响这里的结果。
 *
 * 排序由后端定死：推荐值降序 + `_id` 兜底（与图书库列表默认排序一致），
 * 弹窗内不提供排序切换。
 *
 * @param booklistId 书单 ID
 * @param page 页码，从 1 开始
 * @param size 每页条数，默认 20，上限 200
 */
export function pageBooklistBooks(booklistId: string, page?: number, size?: number) {
  return request<PageResult<Book>>({
    url: `/booklists/${encodeURIComponent(booklistId)}/books`,
    method: 'get',
    params: toParams({ page, size } as Record<string, unknown>),
  })
}

/**
 * 新增书单（2026-10-20）。
 *
 * 只提交书单 ID（必填）与可选名称；书籍数/收藏人数由后续「同步」采集回填。
 * 已存在同一 booklistId 时后端返回 code=400（拦截器已提示）。
 */
export function createBooklist(booklistId: string, name?: string) {
  return request<Booklist>({
    url: '/booklists',
    method: 'post',
    data: { booklistId, name },
  })
}

/**
 * 同步某张书单的书籍（链路 B，2026-10-20）。
 *
 * 提交即返回；进度用 {@link getBooklistCollectProgress} 轮询。
 * 复用采集任务体系：没有任务就建，有就按状态续跑。
 */
export function collectBooklist(booklistId: string) {
  return request<BooklistCollectVO>({
    url: `/booklists/${encodeURIComponent(booklistId)}/collect`,
    method: 'post',
  })
}

/** 某张书单的采集进度（页数/入库/新增/错误） */
export function getBooklistCollectProgress(booklistId: string) {
  return request<BooklistCollectVO>({
    url: `/booklists/${encodeURIComponent(booklistId)}/collect/progress`,
    method: 'get',
  })
}

/**
 * 一键完善所有缺元数据的书单（2026-10-20）。
 *
 * 异步：提交即返回，进度用 {@link getBooklistBackfillProgress} 轮询。
 */
export function backfillBooklists() {
  return request<BooklistBackfillVO>({
    url: '/booklists/backfill',
    method: 'post',
  })
}

/** 「一键完善书单信息」的进度 */
export function getBooklistBackfillProgress() {
  return request<BooklistBackfillVO>({
    url: '/booklists/backfill/progress',
    method: 'get',
  })
}
