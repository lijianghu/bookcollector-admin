import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { addToShelf, fetchShelfStatus, removeFromShelf } from '@/api/shelf'
import {
  addToReadingList,
  fetchReadingStatus,
  removeFromReadingList,
  updateReadingStatus,
} from '@/api/readingList'
import type { ReadingStatus } from '@/types'

/**
 * 「加入 / 移除藏书」「加入 / 移除阅读清单」「修改阅读状态」五个动作的公共实现。
 *
 * <h3>为什么要抽出来</h3>
 * 需求要求**四个列表页**（图书库 / 精选图书 / 我的藏书 / 阅读清单）都提供这五个动作。
 * 各写一遍的话，迟早有一处漏掉「两个维度互不影响」这条 —— 那是个**静默**的错误：
 * 界面看着都对，只是某一次移除把另一个维度也带走了。
 *
 * <h3>🔴 两个维度各自独立更新</h3>
 * `onShelfIds`（在藏书阁）与 `readingMap`（在阅读清单 → 状态）是**两份互不推导的状态**：
 * - 移除藏书**不动** `readingMap`；
 * - 移除阅读清单**不动** `onShelfIds`；
 * - 谁都不是从谁算出来的（收藏 ≠ 想读）。
 *
 * <h3>⚠️ 不整页重拉</h3>
 * 每个动作成功后**只改本地状态**。藏书 / 阅读清单都不参与列表本身的筛选与排序
 * （图书库按评分、精选图书按状态+评价人数、我的藏书按评分），重拉只会让用户的
 * 滚动位置和翻页丢失 —— 这是 `views/book/index.vue` 原 `toggleShelf()` 就定下的做法。
 */

/** 只要求带 `bookId` —— 四个页面的行/卡片类型不同，但都有这个字段 */
interface HasBookId {
  bookId?: string | null
}

export interface BookActionsOptions {
  /**
   * 某本书被**移除藏书**后的回调。
   *
   * <p>「我的藏书」页需要它把卡片从网格里去掉（那本书已经不在这个列表里了）；
   * 图书库 / 精选图书不需要（它们列的是全部图书，与藏书状态无关），不传即可。
   */
  onShelfRemoved?: (bookId: string) => void
  /** 某本书被**移除阅读清单**后的回调（「阅读清单」页需要，其余不传） */
  onReadingRemoved?: (bookId: string) => void
}

export function useBookActions(options: BookActionsOptions = {}) {
  /** 已在藏书阁的 bookId（当前页） */
  const onShelfIds = ref<string[]>([])

  /**
   * 已在阅读清单的 bookId → 状态。
   *
   * <p>用对象而不是 Map：整体替换的赋值方式对响应式最稳，且模板里直接 `readingMap[id]` 可读。
   */
  const readingMap = ref<Record<string, ReadingStatus>>({})

  /** 有动作在飞。用来禁用按钮，避免连点 */
  const busy = ref(false)

  // ------------------------------------------------------------------
  // 读本地状态
  // ------------------------------------------------------------------

  function isOnShelf(bookId?: string | null): boolean {
    return !!bookId && onShelfIds.value.includes(bookId)
  }

  function isInReadingList(bookId?: string | null): boolean {
    return !!bookId && readingMap.value[bookId] !== undefined
  }

  /** 不在清单里时返回 null（调用方据此决定按钮文案 / 是否显示状态标签） */
  function readingStatusOf(bookId?: string | null): ReadingStatus | null {
    if (!bookId) {
      return null
    }
    return readingMap.value[bookId] ?? null
  }

  // ------------------------------------------------------------------
  // 拉状态（列表加载后调一次）
  // ------------------------------------------------------------------

  /**
   * 拉一批书在两个维度上的状态。
   *
   * <p>🔴 **两个请求并发**（`Promise.all`），且**各自兜底**：任一个失败都当成
   * 「都不在」并继续 —— 状态接口挂了不该让整个列表渲染不出来。
   *
   * @param list  要查的书（一般是当前页）
   * @param merge `true` = **增量合并**进现有状态，`false`（默认）= 整体替换。
   *              下滑加载的页面（我的藏书 / 阅读清单）要传 `true` ——
   *              它们每次只新查一页，若用替换语义，之前几页的标记会被清掉。
   *              传**空列表**时：`merge=true` 不动状态，`merge=false` 清空状态
   *              （切类目 / 重新搜索时用后者）。
   */
  async function loadStatus(list: HasBookId[], merge = false): Promise<void> {
    const ids = list.map((b) => b.bookId).filter((x): x is string => !!x)
    if (ids.length === 0) {
      if (!merge) {
        onShelfIds.value = []
        readingMap.value = {}
      }
      return
    }

    const [shelfResult, readingResult] = await Promise.all([
      fetchShelfStatus(ids).catch(() => [] as string[]),
      fetchReadingStatus(ids).catch(() => [] as { bookId: string; status: ReadingStatus }[]),
    ])

    if (merge) {
      const merged = new Set(onShelfIds.value)
      for (const id of shelfResult) {
        merged.add(id)
      }
      onShelfIds.value = Array.from(merged)
    } else {
      onShelfIds.value = shelfResult
    }

    const map: Record<string, ReadingStatus> = merge ? { ...readingMap.value } : {}
    for (const item of readingResult) {
      if (item && item.bookId) {
        map[item.bookId] = item.status
      }
    }
    readingMap.value = map
  }

  // ------------------------------------------------------------------
  // 写
  // ------------------------------------------------------------------

  /** 加入 / 移除藏书（按当前状态切换）。**不动阅读清单** */
  async function toggleShelf(bookId: string): Promise<void> {
    if (!bookId || busy.value) {
      return
    }
    busy.value = true
    try {
      if (isOnShelf(bookId)) {
        await removeFromShelf(bookId)
        onShelfIds.value = onShelfIds.value.filter((x) => x !== bookId)
        ElMessage.success('已从藏书阁移除')
        options.onShelfRemoved?.(bookId)
      } else {
        const added = await addToShelf(bookId)
        onShelfIds.value = onShelfIds.value.concat(bookId)
        ElMessage.success(added ? '已添加到藏书阁' : '这本书已在藏书阁中')
      }
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : String(e))
    } finally {
      busy.value = false
    }
  }

  /**
   * 加入 / 移除阅读清单（按当前状态切换）。**不动藏书**。
   *
   * <p>加入时的初始状态是「想读」；想直接标「在读」，用 {@link setReadingStatus}。
   */
  async function toggleReadingList(bookId: string): Promise<void> {
    if (!bookId || busy.value) {
      return
    }
    busy.value = true
    try {
      if (isInReadingList(bookId)) {
        await removeFromReadingList(bookId)
        const next = { ...readingMap.value }
        delete next[bookId]
        readingMap.value = next
        ElMessage.success('已移出阅读清单')
        options.onReadingRemoved?.(bookId)
      } else {
        const added = await addToReadingList(bookId)
        readingMap.value = { ...readingMap.value, [bookId]: 'WANT_TO_READ' }
        ElMessage.success(added ? '已加入阅读清单（想读）' : '这本书已在阅读清单中')
      }
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : String(e))
    } finally {
      busy.value = false
    }
  }

  /**
   * 修改阅读状态。
   *
   * <p>⚠️ 书**不在清单里**时，先「加入并直接设成目标状态」（后端的 `POST /books`
   * 支持带初始状态），而不是先加入再改 —— 少一次请求，也不会出现「刚加入是
   * 想读、紧接着变成在读」的中间态。
   */
  async function setReadingStatus(bookId: string, status: ReadingStatus): Promise<void> {
    if (!bookId || busy.value) {
      return
    }
    if (readingStatusOf(bookId) === status) {
      return
    }
    busy.value = true
    try {
      if (isInReadingList(bookId)) {
        await updateReadingStatus(bookId, status)
      } else {
        await addToReadingList(bookId, status)
      }
      readingMap.value = { ...readingMap.value, [bookId]: status }
      ElMessage.success('阅读状态已更新')
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : String(e))
    } finally {
      busy.value = false
    }
  }

  return {
    onShelfIds,
    readingMap,
    busy,
    isOnShelf,
    isInReadingList,
    readingStatusOf,
    loadStatus,
    toggleShelf,
    toggleReadingList,
    setReadingStatus,
  }
}
