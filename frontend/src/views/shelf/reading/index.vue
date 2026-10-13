<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getReadingSummary, pageReadingList } from '@/api/readingList'
import { useBookActions } from '@/composables/useBookActions'
import type { ReadingListItem, ReadingListSummary, ReadingStatus } from '@/types'
import { formatNumber } from '@/utils/format'
import ShelfBookCard from '../components/ShelfBookCard.vue'
import ShelfBookDrawer from '../components/ShelfBookDrawer.vue'

/**
 * 阅读清单 —— 想读 / 在读 / 已读 的阅读计划。
 *
 * <h3>2026-10-12 新增（「藏书阁」分组下的第二个子页）</h3>
 * 与「我的藏书」是**两个独立维度**：
 * <ul>
 *   <li>我的藏书 = 我**收藏**了什么（`shelf_books`）；</li>
 *   <li>阅读清单 = 我**打算读**什么、读到哪了（`reading_list`）。</li>
 * </ul>
 * 🔴 收藏 ≠ 想读：一本没收藏的书也能进这里；把一本书从阅读清单移出，
 * 它在藏书阁里的记录**不受影响**（反之亦然）。所以这一页**不做**任何
 * 「已收藏 / 未收藏」的推导式过滤。
 *
 * <h3>视觉</h3>
 * 与「我的藏书」同一套深色木质基调（复用了 `ShelfBookCard` 与 `ShelfBookDrawer`），
 * 顶部换成四个状态 Tab —— 因为这里的天然分组是「阅读进度」而不是「书籍分类」。
 *
 * <h3>排序</h3>
 * 后端固定 `statusUpdatedAt DESC` → `_id DESC`：「最近操作的先看」。
 * 所以在「在读」Tab 里，刚标为在读的书会排最前面。
 */

/** 一次加载 24 条（8 列 × 3 行），与「我的藏书」一致 */
const PAGE_SIZE = 24

/** 状态 Tab。空串 = 全部（后端把空串归一成「不筛」） */
const TABS: { label: string; value: ReadingStatus | '' }[] = [
  { label: '全部', value: '' },
  { label: '想读', value: 'WANT_TO_READ' },
  { label: '在读', value: 'READING' },
  { label: '已读', value: 'FINISHED' },
]

const activeStatus = ref<ReadingStatus | ''>('')
const summary = ref<ReadingListSummary | null>(null)

const books = ref<ReadingListItem[]>([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
/** 已取完（没有下一页了） */
const noMore = ref(false)
/**
 * 首屏是否初始化完成。滚动哨兵的首次回调是**异步**送达的，可能落在初始化中间，
 * 会把第 2 页也顺带拉进来（同「我的藏书」的处理）。
 */
const bootstrapped = ref(false)
const errorMsg = ref('')

const drawerVisible = ref(false)
const detailBook = ref<ReadingListItem | null>(null)

/**
 * 两个维度的状态与动作 —— 与图书库 / 精选图书 / 我的藏书**同一套实现**。
 *
 * <p>`onReadingRemoved`：从本页移出后，这本书已经不属于这个列表了，把卡片去掉。
 * ⚠️ 它**不影响**藏书阁 —— 回调里也不碰 `onShelfIds`。
 */
const {
  busy: actionBusy,
  isOnShelf,
  readingStatusOf,
  loadStatus,
  toggleShelf,
  toggleReadingList,
  setReadingStatus,
} = useBookActions({
  onReadingRemoved: (bookId) => {
    books.value = books.value.filter((b) => b.bookId !== bookId)
    total.value = Math.max(0, total.value - 1)
    void loadSummary()
  },
})

const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/** 请求令牌。切 Tab 时 +1，用来丢弃「切换瞬间还在飞的旧请求」 */
let token = 0

/** Tab 角标：当前 Tab 对应的数量（「全部」用 summary.total） */
function countOf(value: ReadingStatus | ''): number {
  const s = summary.value
  if (!s) {
    return 0
  }
  if (value === 'WANT_TO_READ') {
    return s.wantToRead
  }
  if (value === 'READING') {
    return s.reading
  }
  if (value === 'FINISHED') {
    return s.finished
  }
  return s.total
}

/** 页头「当前状态条数」。有 Tab 选中时用它，否则用分页返回的 total */
const activeCount = computed(() => (activeStatus.value === '' ? total.value : countOf(activeStatus.value)))

async function loadSummary(): Promise<void> {
  try {
    summary.value = await getReadingSummary()
  } catch {
    // 角标拿不到不影响列表本身
    summary.value = null
  }
}

/** 拉下一页并追加 */
async function loadMore(): Promise<void> {
  if (!bootstrapped.value || loading.value || noMore.value) {
    return
  }
  const my = token
  loading.value = true
  errorMsg.value = ''
  try {
    const next = page.value + 1
    const res = await pageReadingList({
      status: activeStatus.value,
      page: next,
      size: PAGE_SIZE,
    })
    if (my !== token) {
      // 期间切了 Tab，这次结果作废
      return
    }
    books.value = books.value.concat(res.list)
    total.value = res.total
    page.value = next
    noMore.value = res.list.length === 0 || books.value.length >= res.total
    // 两个维度的状态**增量合并**（只查新到的一页）
    await loadStatus(res.list, true)
  } catch (e) {
    if (my !== token) {
      return
    }
    errorMsg.value = e instanceof Error ? e.message : String(e)
    // 出错就停下，别让哨兵反复触发同一个失败请求
    noMore.value = true
  } finally {
    if (my === token) {
      loading.value = false
    }
  }
}

/** 清空并从第一页重来（切 Tab 时用） */
function reload(): void {
  token += 1
  books.value = []
  total.value = 0
  page.value = 0
  noMore.value = false
  loading.value = false
  // 列表清空了，两个维度的状态也一起清（否则旧书的标记会残留）
  void loadStatus([])
  void loadMore()
}

function onStatusChange(value: ReadingStatus | ''): void {
  if (value === activeStatus.value) {
    return
  }
  activeStatus.value = value
  reload()
}

function openDetail(book: ReadingListItem): void {
  detailBook.value = book
  drawerVisible.value = true
}

/** 抽屉里点「加入 / 移除藏书」 */
function onDrawerToggleShelf(): void {
  if (detailBook.value?.bookId) {
    void toggleShelf(detailBook.value.bookId)
  }
}

/** 抽屉里点「移出阅读清单」 */
function onDrawerToggleReading(): void {
  if (detailBook.value?.bookId) {
    void toggleReadingList(detailBook.value.bookId)
  }
}

/**
 * 抽屉里改阅读状态。
 *
 * <p>⚠️ 如果当前正停在某个状态 Tab 下，而新状态与该 Tab 不符，
 * 这本书就该从这个列表里消失（它已经不属于这个筛选了）——
 * 否则用户会看到一个「在读」的书待在「想读」Tab 里，下次刷新才消失。
 */
async function onDrawerSetStatus(status: ReadingStatus): Promise<void> {
  const book = detailBook.value
  if (!book?.bookId) {
    return
  }
  await setReadingStatus(book.bookId, status)
  if (activeStatus.value !== '' && activeStatus.value !== status) {
    books.value = books.value.filter((b) => b.bookId !== book.bookId)
    total.value = Math.max(0, total.value - 1)
  }
  void loadSummary()
}

onMounted(async () => {
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        void loadMore()
      }
    },
    { rootMargin: '300px' },
  )
  if (sentinel.value) {
    observer.observe(sentinel.value)
  }

  await loadSummary()
  bootstrapped.value = true
  await loadMore()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <div class="reading-page">
    <!-- ================= 顶部 ================= -->
    <header class="head">
      <div class="head-main">
        <h1>阅读清单</h1>
        <p class="tagline">想读的、在读的、读完的，都在这里</p>
      </div>
      <div class="head-stats">
        <span>清单 <b>{{ formatNumber(summary?.total ?? 0) }}</b> 本</span>
        <i />
        <span>
          当前「{{ activeStatus === '' ? '全部' : TABS.find((t) => t.value === activeStatus)?.label }}」
          <b>{{ formatNumber(activeCount) }}</b> 本
        </span>
      </div>
    </header>

    <!-- ================= 状态 Tab ================= -->
    <section class="tabs">
      <button
        v-for="tab in TABS"
        :key="tab.value || 'all'"
        type="button"
        class="tab"
        :class="{ active: activeStatus === tab.value }"
        :disabled="loading"
        @click="onStatusChange(tab.value)"
      >
        {{ tab.label }}
        <span class="tab-count">{{ formatNumber(countOf(tab.value)) }}</span>
      </button>
    </section>

    <el-alert
      v-if="errorMsg"
      class="err"
      type="error"
      :closable="false"
      show-icon
      :title="errorMsg"
    />

    <!-- ================= 网格（8 列 × 3 行） ================= -->
    <div class="grid-wrap">
      <div v-if="loading && books.length === 0" class="grid skeleton-grid">
        <div v-for="n in PAGE_SIZE" :key="n" class="skeleton-card">
          <div class="skeleton-cover" />
          <span class="skeleton-line" />
          <span class="skeleton-line short" />
        </div>
      </div>

      <div v-else-if="books.length > 0" class="grid">
        <ShelfBookCard
          v-for="book in books"
          :key="book.bookId"
          :book="book"
          @select="openDetail"
        />
      </div>

      <el-empty
        v-else-if="!errorMsg"
        :description="activeStatus === '' ? '阅读清单还是空的 —— 在图书库或精选图书里点「加入阅读清单」' : '这个状态下还没有书'"
      />

      <div ref="sentinel" class="sentinel">
        <p v-if="loading && books.length > 0" class="tip">正在加载更多…</p>
        <p v-else-if="noMore && books.length > 0" class="tip">已经到底啦</p>
      </div>
    </div>

    <ShelfBookDrawer
      v-model="drawerVisible"
      :book="detailBook"
      :on-shelf="isOnShelf(detailBook?.bookId)"
      :reading-status="detailBook ? readingStatusOf(detailBook.bookId) : null"
      :busy="actionBusy"
      @toggle-shelf="onDrawerToggleShelf"
      @toggle-reading="onDrawerToggleReading"
      @set-status="onDrawerSetStatus"
    />
  </div>
</template>

<style scoped>
.reading-page {
  /* 抵消 BasicLayout 内容区的 16px padding + 灰底，换成整块深色（同「我的藏书」） */
  margin: -16px;
  min-height: calc(100vh - 56px);
  box-sizing: border-box;
  padding: 26px 30px 48px;
  background:
    radial-gradient(126% 62% at 50% 0%, rgba(126, 82, 44, 0.4) 0%, rgba(20, 12, 8, 0) 62%),
    repeating-linear-gradient(
      91deg,
      rgba(255, 255, 255, 0.012) 0 6px,
      rgba(0, 0, 0, 0.022) 6px 13px
    ),
    linear-gradient(178deg, #241811 0%, #1a1009 48%, #120a05 100%);
  color: #e9dcc9;
}

/* ---------- 顶部 ---------- */
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.head h1 {
  margin: 0;
  font-size: 23px;
  font-weight: 600;
  letter-spacing: 2px;
  color: #f6ecdc;
  text-shadow: 0 2px 10px rgba(0, 0, 0, 0.6);
}

.tagline {
  margin: 7px 0 0;
  font-size: 13px;
  color: rgba(233, 220, 201, 0.52);
  letter-spacing: 0.6px;
}

.head-stats {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: rgba(233, 220, 201, 0.6);
}

.head-stats b {
  color: #e8b96f;
  font-size: 16px;
  font-variant-numeric: tabular-nums;
  margin: 0 2px;
}

.head-stats i {
  width: 1px;
  height: 13px;
  background: rgba(198, 158, 106, 0.28);
}

/* ---------- 状态 Tab ---------- */
/*
 * 木质 Tab 条：与「我的藏书」的搜索栏 / 分类木牌同一套色，
 * 让它看起来是这座书阁的一部分。用原生 <button>（要的是渐变选中态，
 * el-button 的底色来自 CSS 变量，塞不进渐变）。
 */
.tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 18px;
  padding: 12px 14px;
  border: 1px solid rgba(196, 156, 104, 0.22);
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.3), rgba(0, 0, 0, 0.16));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.045), inset 0 -1px 0 rgba(0, 0, 0, 0.35);
}

.tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 16px;
  border: 1px solid rgba(196, 156, 104, 0.3);
  border-radius: 5px;
  background: linear-gradient(180deg, rgba(74, 49, 25, 0.9), rgba(58, 37, 19, 0.9));
  color: rgba(236, 216, 186, 0.82);
  font-size: 13px;
  letter-spacing: 1px;
  cursor: pointer;
  transition: transform 0.14s ease, border-color 0.14s ease, color 0.14s ease,
    box-shadow 0.14s ease;
}

.tab:hover:not(:disabled) {
  color: #faecd6;
  border-color: rgba(222, 178, 116, 0.56);
  transform: translateY(-1px);
}

.tab.active {
  border-color: rgba(238, 202, 142, 0.9);
  background: linear-gradient(180deg, #e0b878 0%, #c79a5b 100%);
  color: #2a1a0c;
  font-weight: 600;
}

.tab:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.tab-count {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  opacity: 0.72;
}

/* ---------- 网格 ---------- */
.grid-wrap {
  margin-top: 6px;
}

.grid {
  display: grid;
  /* 8 列 × 3 行 = 一屏 24 本，与「我的藏书」保持一致 */
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 30px 12px;
  margin: 0 auto;
}

.err {
  margin-top: 16px;
}

/* ---------- 骨架 ---------- */
.skeleton-card {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.skeleton-cover {
  width: 100%;
  aspect-ratio: 3 / 4;
  border-radius: 4px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.055), rgba(255, 255, 255, 0.02));
  animation: pulse 1.4s ease-in-out infinite;
}

.skeleton-line {
  height: 11px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.05);
}

.skeleton-line.short {
  width: 55%;
}

/* ---------- 哨兵 / 状态行 ---------- */
.sentinel {
  max-width: 980px;
  margin: 0 auto;
  min-height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.tip {
  margin: 18px 0 0;
  font-size: 12.5px;
  color: rgba(233, 220, 201, 0.42);
  letter-spacing: 0.5px;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 0.9;
  }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton-cover {
    animation: none;
  }
  .tab {
    transition: none;
  }
  .tab:hover:not(:disabled) {
    transform: none;
  }
}
</style>
