<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { listShelfCategories, pageShelfBooks } from '@/api/shelf'
import type { ShelfBook, ShelfCategory } from '@/types'
import { formatNumber } from '@/utils/format'
import CategoryNav from './components/CategoryNav.vue'
import ShelfBookCard from './components/ShelfBookCard.vue'
import ShelfBookDrawer from './components/ShelfBookDrawer.vue'

/**
 * 藏书阁 —— 沉浸式浏览页。
 *
 * <h3>2026-10-10 改版：横向书架 → 网格 + 下滑加载</h3>
 * 旧版是「横向可滑动的木质书架，每层 10 本」，一次只取 20 本。
 * 用户要求改成<b>网格</b>：一屏 <b>10 列 × 3 行 = 30 条</b>，
 * 滑到底自动加载下一页（无限滚动）。
 *
 * <h3>视觉基调</h3>
 * 深胡桃木 + 顶部暖光。整页是「一座私人图书馆」而不是后台列表页，
 * 所以它跟其它页面的白底表格刻意不同：见 `.shelf-page` 的负 margin ——
 * `BasicLayout` 的内容区有 16px padding + 灰底，这里把它抵消掉，换成整块深色。
 * **不动公共布局**，所以其它页面完全不受影响。
 *
 * <h3>数据源</h3>
 * 后端集合 `shelf_books`（2026-10-10 新建）—— 藏书阁是用户自己的一张书单，
 * 图书库的操作列能「添加藏书 / 移除藏书」。不再是 AI 解读候选清单的只读视图。
 */

/** 一次加载 30 条（10 列 × 3 行） */
const PAGE_SIZE = 30

const categories = ref<ShelfCategory[]>([])
const categoriesLoading = ref(false)

const active = ref('')
const books = ref<ShelfBook[]>([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
/** 已取完（没有下一页了） */
const noMore = ref(false)
const errorMsg = ref('')

const drawerVisible = ref(false)
const detailBook = ref<ShelfBook | null>(null)

const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/**
 * 请求令牌。切类目时 +1，用来丢弃「切类目瞬间还在飞的旧请求」——
 * 否则旧类目的第二页会追加到新类目的列表里，看着像数据错乱。
 */
let token = 0

/** 藏书总数 = 各类目数量之和 */
const totalCount = computed(() => categories.value.reduce((sum, c) => sum + c.count, 0))

/** 当前类目的数量（优先用导航里的角标，没有则用分页返回的 total） */
const activeCount = computed(
  () => categories.value.find((c) => c.name === active.value)?.count ?? total.value,
)

async function loadCategories(): Promise<void> {
  categoriesLoading.value = true
  try {
    const list = await listShelfCategories()
    categories.value = list
    // 默认落在第一个类目（接口按数量降序，即藏书最多的那个）
    if (!active.value && list.length > 0) {
      active.value = list[0].name
    }
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    categoriesLoading.value = false
  }
}

/** 拉下一页并追加。滚动哨兵与「加载更多」按钮都走它 */
async function loadMore(): Promise<void> {
  if (loading.value || noMore.value || !active.value) {
    return
  }
  const my = token
  loading.value = true
  errorMsg.value = ''
  try {
    const next = page.value + 1
    const res = await pageShelfBooks(active.value, next, PAGE_SIZE)
    if (my !== token) {
      // 期间切了类目，这次结果作废
      return
    }
    books.value = books.value.concat(res.list)
    total.value = res.total
    page.value = next
    noMore.value = res.list.length === 0 || books.value.length >= res.total
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

/** 清空并从第一页重来（切类目时用） */
function reload(): void {
  token += 1
  books.value = []
  total.value = 0
  page.value = 0
  noMore.value = false
  loading.value = false
  void loadMore()
}

function onCategoryChange(name: string): void {
  if (name === active.value) {
    return
  }
  active.value = name
  reload()
}

function openDetail(book: ShelfBook): void {
  detailBook.value = book
  drawerVisible.value = true
}

onMounted(async () => {
  // 哨兵进入视口前 300px 就预取，滚到底时数据已经在了
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

  await loadCategories()
  await loadMore()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <div class="shelf-page">
    <!-- ================= 顶部 ================= -->
    <header class="head">
      <div class="head-main">
        <h1>我的私人图书馆</h1>
        <p class="tagline">在书架之间，发现下一个值得探索的世界</p>
      </div>
      <div class="head-stats">
        <span>藏书 <b>{{ formatNumber(totalCount) }}</b> 本</span>
        <i />
        <span>
          当前「{{ active }}」<b>{{ formatNumber(activeCount) }}</b> 本
        </span>
      </div>
    </header>

    <!-- ================= 分类导航 ================= -->
    <CategoryNav
      :categories="categories"
      :active="active"
      :loading="categoriesLoading"
      @change="onCategoryChange"
    />

    <el-alert
      v-if="errorMsg"
      class="err"
      type="error"
      :closable="false"
      show-icon
      :title="errorMsg"
    />

    <!-- ================= 网格（10 列 × 3 行） ================= -->
    <div class="grid-wrap">
      <!-- 首屏骨架：铺满一整页（= PAGE_SIZE），与真实网格同列数，避免只填半行 -->
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

      <el-empty v-else-if="!errorMsg" description="这个书架上还没有书" />

      <!-- 滚动哨兵 + 状态行 -->
      <div ref="sentinel" class="sentinel">
        <p v-if="loading && books.length > 0" class="tip">正在加载更多…</p>
        <p v-else-if="noMore && books.length > 0" class="tip">已经到底啦</p>
      </div>
    </div>

    <ShelfBookDrawer v-model="drawerVisible" :book="detailBook" />
  </div>
</template>

<style scoped>
.shelf-page {
  /* 抵消 BasicLayout 内容区的 16px padding + 灰底，换成整块深色。
     负 margin 只作用于本页，公共布局一行都不用改 */
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

/* ---------- 网格 ---------- */
.grid-wrap {
  margin-top: 24px;
}

.grid {
  display: grid;
  /* 10 列 × 3 行 = 一屏 30 本。列多了卡片变小，所以去掉原来的 980px 上限让它铺满、列间距也收窄 */
  grid-template-columns: repeat(10, minmax(0, 1fr));
  gap: 18px 12px;
  margin: 0 auto;
}

/*
 * ------------------------------------------------------------------
 * ⚠️ 临时对比开关（2026-10-10）：只让**第一排**（前 10 本）走 3D 实体书，
 *    后两排保持原来的平面卡片 —— 一张截图就能对比「立体 vs 平面」。
 * 🔴 视觉验收通过后**删掉这一整段**（连同上面那行注释），让整墙都走 3D。
 *    不要把它当成正式功能留着。
 * ------------------------------------------------------------------
 */
.grid :deep(.card:nth-child(n + 11)) .book {
  transform: none;
}

.grid :deep(.card:nth-child(n + 11)) .spine,
.grid :deep(.card:nth-child(n + 11)) .top {
  display: none;
}

.grid :deep(.card:nth-child(n + 11)) .stage::before,
.grid :deep(.card:nth-child(n + 11)) .stage::after {
  display: none;
}

.grid :deep(.card:nth-child(n + 11)) .cover {
  transform: none;
  box-shadow:
    0 10px 18px -8px rgba(0, 0, 0, 0.85),
    0 1px 0 rgba(255, 226, 178, 0.06);
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
}
</style>
