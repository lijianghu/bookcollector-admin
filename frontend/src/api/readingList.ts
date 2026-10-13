import { request } from './request'
import type {
  PageResult,
  ReadingListItem,
  ReadingListStatusItem,
  ReadingListSummary,
  ReadingStatus,
} from '@/types'

/**
 * 阅读清单接口（6 个）。
 *
 * <h3>它与藏书阁（`api/shelf.ts`）是**并列的两套**</h3>
 * 「收藏」与「阅读计划」是两个独立维度：一本书可以只收藏不想读、只想读不收藏。
 * 所以这里**不会**去调 `api/shelf.ts`，反之亦然 —— 四个列表页需要两个维度时，
 * 由 `composables/useBookActions.ts` **并发**发两组请求。
 *
 * <h3>⚠️ 批量查状态返回的是 `[{bookId, status}]`，不是 `string[]`</h3>
 * 四个页面既要判「在不在清单」（决定按钮文案），也要显示**当前状态**
 * （想读 / 在读 / 已读）。只回一个 ID 数组只能答前半句 ——
 * 这是它与 `fetchShelfStatus()`（返回 `string[]`）唯一的结构差异。
 */

/**
 * 分页取阅读清单。
 *
 * 后端排序是 `statusUpdatedAt DESC` → `_id DESC`（「最近操作的先看」）。
 *
 * @param params.status   阅读状态；空串 = 全部
 * @param params.category 一级类目名，如「文学」；空 = 不筛。传带二级的完整名也能用（后端截到一级）
 * @param params.title    书名关键词，**包含匹配**（模糊，大小写不敏感）；空 = 不筛
 * @param params.author   作者关键词，**包含匹配**（模糊，大小写不敏感）；空 = 不筛
 * @param params.page     页码，从 1 开始
 * @param params.size     每页条数（不传则后端兜底 30）
 */
export function pageReadingList(params: {
  status?: ReadingStatus | ''
  category?: string
  title?: string
  author?: string
  page?: number
  size?: number
}) {
  return request<PageResult<ReadingListItem>>({
    url: '/reading-list/books',
    method: 'get',
    params: {
      status: params.status ?? '',
      category: params.category ?? '',
      title: params.title ?? '',
      author: params.author ?? '',
      page: params.page ?? 1,
      size: params.size ?? 30,
    },
  })
}

/** 各状态计数（给状态 Tab 的角标） */
export function getReadingSummary() {
  return request<ReadingListSummary>({
    url: '/reading-list/summary',
    method: 'get',
  })
}

/**
 * 把一本书加进阅读清单（后端从图书库取快照）。
 *
 * <p>⚠️ **不要求这本书已在藏书阁** —— 收藏与阅读计划是独立维度。
 *
 * @param status 初始状态，不传 = `WANT_TO_READ`（想读）。
 *               传了可以「加入并直接标记为在读」，省一次请求
 * @returns `true` = 新加入；`false` = 本来就在（幂等，不报错）
 */
export function addToReadingList(bookId: string, status?: ReadingStatus) {
  return request<boolean>({
    url: '/reading-list/books',
    method: 'post',
    data: { bookId, status },
  })
}

/**
 * 从阅读清单移除一本书。
 *
 * <p>⚠️ 只动阅读清单 —— 这本书在**藏书阁里的记录不受影响**。
 *
 * @returns `true` = 确实删掉了；`false` = 本来就不在（幂等，不报错）
 */
export function removeFromReadingList(bookId: string) {
  return request<boolean>({
    url: `/reading-list/books/${encodeURIComponent(bookId)}`,
    method: 'delete',
  })
}

/**
 * 修改阅读状态。
 *
 * <p>状态值非法后端返回 `code=400`（**不会**静默改成「想读」）。
 *
 * @returns `true` = 这本书现在处于目标状态（含「本来就相同」）；`false` = 它不在清单里
 */
export function updateReadingStatus(bookId: string, status: ReadingStatus) {
  return request<boolean>({
    url: `/reading-list/books/${encodeURIComponent(bookId)}/status`,
    method: 'put',
    data: { status },
  })
}

/**
 * 批量查「哪些书已在阅读清单、分别什么状态」。
 *
 * @param bookIds 待查的图书 ID（一般是当前页）
 * @returns 只含**已在清单里**的项
 */
export function fetchReadingStatus(bookIds: string[]) {
  return request<ReadingListStatusItem[]>({
    url: '/reading-list/status',
    method: 'post',
    data: { bookIds },
  })
}
