import { request } from './request'
import type { PageResult, ShelfBook, ShelfCategory } from '@/types'

/**
 * 藏书阁接口（5 个）。
 *
 * <h3>2026-10-10：从 2 个只读接口扩到 5 个</h3>
 * 第一版藏书阁是只读的（类目 + 取书）。现在它有自己的表，
 * 图书库里能添加/移除藏书，列表也改成分页（一次 30 条、下滑加载）。
 *
 * <h3>图书详情不在这里</h3>
 * 详情抽屉直接复用 `GET /api/books/{bookId}`，没有为藏书阁另造一套。
 *
 * <h3>⚠️ 「是否已在藏书阁」为什么单独问一次</h3>
 * 图书库列表接口不带 `onShelf` 字段 —— 那样 `book` 模块就要依赖 `shelf`。
 * 所以改成拉完列表后，拿当前页的 bookId 调一次 {@link fetchShelfStatus}。
 */

/** 一级类目 + 数量（按数量降序）。类目来自数据本身，不是写死的常量 */
export function listShelfCategories() {
  return request<ShelfCategory[]>({
    url: '/shelf/categories',
    method: 'get',
  })
}

/**
 * 分页取藏书。
 *
 * 后端排序是 `newRating DESC` → `newRatingCount DESC` → `_id DESC`。
 * 主序沿用旧版书架「评分最高先看」；后两级只为**确定性** ——
 * 分页的正确性依赖排序是个全序，否则同分的书在翻页时会重复/漏。
 *
 * @param category 一级类目名，如「文学」；传空 = 取全部。传带二级的完整名也能用（后端截到一级）
 * @param page     页码，从 1 开始
 * @param size     每页条数，默认 30（一屏 10 列 × 3 行）
 */
export function pageShelfBooks(category: string, page = 1, size = 30) {
  return request<PageResult<ShelfBook>>({
    url: '/shelf/books',
    method: 'get',
    params: { category, page, size },
  })
}

/**
 * 把一本书加进藏书阁（后端从图书库取快照）。
 *
 * @returns `true` = 新加入；`false` = 本来就在（幂等，不报错）
 */
export function addToShelf(bookId: string) {
  return request<boolean>({
    url: '/shelf/books',
    method: 'post',
    data: { bookId },
  })
}

/**
 * 从藏书阁移除一本书。
 *
 * @returns `true` = 确实删掉了；`false` = 本来就不在（幂等，不报错）
 */
export function removeFromShelf(bookId: string) {
  return request<boolean>({
    url: `/shelf/books/${encodeURIComponent(bookId)}`,
    method: 'delete',
  })
}

/**
 * 批量查「哪些书已在藏书阁」。图书库列表用它决定每行显示哪个按钮。
 *
 * @param bookIds 待查的图书 ID（一般是当前页）
 * @returns 已在藏书阁的 bookId 数组
 */
export function fetchShelfStatus(bookIds: string[]) {
  return request<string[]>({
    url: '/shelf/status',
    method: 'post',
    data: { bookIds },
  })
}
