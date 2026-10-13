<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { listShelfCategories, pageShelfBooks } from '@/api/shelf'
import { useBookActions } from '@/composables/useBookActions'
import type { ReadingStatus, ShelfBook, ShelfCategory } from '@/types'
import { formatNumber } from '@/utils/format'
import CategoryNav from './components/CategoryNav.vue'
import ShelfBookCard from './components/ShelfBookCard.vue'
import ShelfBookDrawer from './components/ShelfBookDrawer.vue'

/**
 * 我的藏书（原「藏书阁」页，2026-10-12 改名并成为「藏书阁」分组下的一个子页）。
 *
 * <h3>2026-10-10 改版：横向书架 → 网格 + 下滑加载</h3>
 * 旧版是「横向可滑动的木质书架，每层 10 本」，一次只取 20 本。
 * 用户要求改成<b>网格</b>：一屏 <b>8 列 × 3 行 = 24 条</b>
 * （初版 10 列 × 3 = 30，同日因封面太小改成 8 列），
 * 滑到底自动加载下一页（无限滚动）。
 *
 * <h3>2026-10-12：加了「书名 / 作者」模糊搜索</h3>
 * 后端 `GET /api/shelf/books` 多了 `title` / `author` 两个**包含匹配**参数。
 * 前端在页头下面放了一条木质搜索栏（`.search-band`），**回车 / 点「搜索」才发请求**
 * —— 刻意不做输入即搜，所以「输入框里的值」与「已生效的值」是两组 ref。
 *
 * <h3>2026-10-12 二次：抽屉里能做阅读清单的动作</h3>
 * 用公共的 `useBookActions()` —— 与图书库 / 精选图书 / 阅读清单三页**同一套实现**，
 * 保证「收藏」与「阅读计划」两个维度互不影响。
 *
 * <h3>视觉基调</h3>
 * 深胡桃木 + 顶部暖光。整页是「一座私人图书馆」而不是后台列表页，
 * 所以它跟其它页面的白底表格刻意不同：见 `.shelf-page` 的负 margin。
 * **不动公共布局**，所以其它页面完全不受影响。
 *
 * <h3>数据源</h3>
 * 后端集合 `shelf_books`（2026-10-10 新建）—— 我的藏书是用户自己的一张书单。
 */

/**
 * 一次加载 24 条（8 列 × 3 行）。
 *
 * <p>2026-10-10 由「10 列 × 3 = 30」改成 8 列 —— 10 列时每格封面只有 ~95px 宽，
 * 用户反馈「太小，看不清封面」。列数变少 → 封面变大 → 每页条数跟着改成 24。
 */
const PAGE_SIZE = 24

const categories = ref<ShelfCategory[]>([])
const categoriesLoading = ref(false)

/**
 * 当前选中的一级类目名。**空串 = 全部**（不按类目筛）。
 *
 * <p>2026-10-10：默认从「藏书最多的那个类目」改成**全部**。点进来先看到整座书阁，
 * 而不是被直接塞进某一个类目里。
 *
 * <p>⚠️ 空串是可以直接透到后端的：`ShelfServiceImpl.topCategoryOf("")` 会归一成 `null`
 * = 不筛。所以这里不需要额外的「全部」哨兵值 —— 空串本身就是合法入参。
 */
const active = ref('')

/**
 * 搜索框里**正在输入**的值（尚未提交）。
 *
 * <p>刻意把「输入中的值」与「已生效的值」分成两组 ref —— 打字时不发请求，
 * 只有点「搜索」/ 回车才把输入同步成生效值（见 `doSearch`）。
 */
const titleInput = ref('')
const authorInput = ref('')

/**
 * **已生效**的搜索词。请求里带的是这一组，不是输入框里的那一组。
 *
 * <p>⚠️ 空串是合法值，表示「不筛这一项」（后端 `RegexUtil.contains()` 见到空白返回
 * `null`）。不要引入哨兵值 —— 这里没有「专门找空书名」这种需求。
 */
const appliedTitle = ref('')
const appliedAuthor = ref('')

/** 是否有生效的搜索词。决定页头条数的口径 + 空状态文案 */
const hasSearch = computed(() => appliedTitle.value !== '' || appliedAuthor.value !== '')

const books = ref<ShelfBook[]>([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
/** 已取完（没有下一页了） */
const noMore = ref(false)
/**
 * 首屏是否初始化完成（类目拉回来 + 第一页拉回来）。
 *
 * <p>滚动哨兵在 `onMounted` 里就开始观察，而它的首次回调是**异步**送达的 ——
 * 可能落在 `await loadCategories()` 中间。默认类目改成「全部」（空串）之后，
 * 这次提前触发不再是「无效请求」，会真的发出去，和 `onMounted` 的首次加载并发，
 * 结果把第 2 页也顺带拉进来。所以用这个标记把初始化前的触发拦掉。
 */
const bootstrapped = ref(false)
const errorMsg = ref('')

const drawerVisible = ref(false)
const detailBook = ref<ShelfBook | null>(null)

/**
 * 藏书 / 阅读清单两个维度的状态与动作 —— 与图书库 / 精选图书 / 阅读清单三页**同一套**。
 *
 * <p>🔴 两个维度互不推导：这里只读「在不在藏书阁」与「阅读状态」，
 * 不会用其中一个去算另一个。
 *
 * <p>`onShelfRemoved`：从「我的藏书」里移除一本书后，它已经不属于这个列表了，
 * 所以把卡片从网格里去掉（并修正总数）。⚠️ 移除**阅读清单**不需要这个回调 ——
 * 那不影响本书单。
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
  onShelfRemoved: (bookId) => {
    books.value = books.value.filter((b) => b.bookId !== bookId)
    total.value = Math.max(0, total.value - 1)
  },
})

const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/**
 * 请求令牌。切类目时 +1，用来丢弃「切类目瞬间还在飞的旧请求」——
 * 否则旧类目的第二页会追加到新类目的列表里，看着像数据错乱。
 */
let token = 0

/** 藏书总数 = 各类目数量之和 */
const totalCount = computed(() => categories.value.reduce((sum, c) => sum + c.count, 0))

/**
 * 页头「当前类目条数」。
 *
 * <p>默认用类目木牌上的角标（它是**全量**统计，不随搜索变）。
 * ⚠️ 但**一旦有搜索词**就必须改用分页返回的 `total` —— 木牌角标是全量数字，
 * 搜索时用它会让页头显示一个明显偏大的数，和下面网格里的条数对不上。
 */
const activeCount = computed(() => {
  if (hasSearch.value) {
    return total.value
  }
  return categories.value.find((c) => c.name === active.value)?.count ?? total.value
})

/** 页头显示的当前类目名。空串（全部）时不能显示成「当前「」」 */
const activeLabel = computed(() => active.value || '全部')

async function loadCategories(): Promise<void> {
  categoriesLoading.value = true
  try {
    // 2026-10-10：不再「默认落在第一个类目」—— active 保持空串，即「全部」
    categories.value = await listShelfCategories()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    categoriesLoading.value = false
  }
}

/** 拉下一页并追加。滚动哨兵与「加载更多」按钮都走它 */
async function loadMore(): Promise<void> {
  // ⚠️ 这里原来还有一条 `!active.value` —— 那是「默认落在第一个类目」时代的守卫。
  // 现在 active 为空表示「全部」，是**合法**查询，所以换成 bootstrapped：
  // 只拦「首屏还没初始化完」的提前触发。
  if (!bootstrapped.value || loading.value || noMore.value) {
    return
  }
  const my = token
  loading.value = true
  errorMsg.value = ''
  try {
    const next = page.value + 1
    const res = await pageShelfBooks({
      category: active.value,
      title: appliedTitle.value,
      author: appliedAuthor.value,
      page: next,
      size: PAGE_SIZE,
    })
    if (my !== token) {
      // 期间切了类目，这次结果作废
      return
    }
    books.value = books.value.concat(res.list)
    total.value = res.total
    page.value = next
    noMore.value = res.list.length === 0 || books.value.length >= res.total
    // 两个维度的状态**增量合并**进本地（只查新到的那一页，不重查整列表）
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

/** 清空并从第一页重来（切类目 / 搜索时用） */
function reload(): void {
  token += 1
  books.value = []
  total.value = 0
  page.value = 0
  noMore.value = false
  loading.value = false
  // 列表清空了，两个维度的状态也要一起清（否则旧书的标记会残留）
  void loadStatus([])
  void loadMore()
}

function onCategoryChange(name: string): void {
  if (name === active.value) {
    return
  }
  active.value = name
  reload()
}

/**
 * 提交搜索：把输入框的值同步成**生效值**，再从第一页重来。
 *
 * <p>由「搜索」按钮与两个输入框的 `@keyup.enter` 触发 —— 刻意**不做输入即搜**。
 *
 * <p>搜索条件与类目是**与**的关系：切类目时会带着当前搜索词一起查。
 */
function doSearch(): void {
  appliedTitle.value = titleInput.value.trim()
  appliedAuthor.value = authorInput.value.trim()
  reload()
}

/**
 * 清空搜索并重来。
 *
 * <p>本来就没有任何搜索词（生效的、正在输入的都没有）时直接返回 ——
 * 否则点一下「重置」会白发一次请求。
 */
function resetSearch(): void {
  if (!hasSearch.value && titleInput.value === '' && authorInput.value === '') {
    return
  }
  titleInput.value = ''
  authorInput.value = ''
  appliedTitle.value = ''
  appliedAuthor.value = ''
  reload()
}

function openDetail(book: ShelfBook): void {
  detailBook.value = book
  drawerVisible.value = true
}

/** 抽屉里点「移除藏书」—— 传的是当前打开的那本书 */
function onDrawerToggleShelf(): void {
  if (detailBook.value?.bookId) {
    void toggleShelf(detailBook.value.bookId)
  }
}

/** 抽屉里点「加入 / 移出阅读清单」 */
function onDrawerToggleReading(): void {
  if (detailBook.value?.bookId) {
    void toggleReadingList(detailBook.value.bookId)
  }
}

/** 抽屉里点某个阅读状态 */
function onDrawerSetStatus(status: ReadingStatus): void {
  if (detailBook.value?.bookId) {
    void setReadingStatus(detailBook.value.bookId, status)
  }
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
  // 类目回来后首屏才算初始化完，这时才允许滚动哨兵参与加载
  bootstrapped.value = true
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
          当前「{{ activeLabel }}」<b>{{ formatNumber(activeCount) }}</b> 本
        </span>
      </div>
    </header>

    <!-- ================= 搜索栏 ================= -->
    <!--
      书名 / 作者模糊搜索。刻意**不做输入即搜** —— 用户明确要「回车 / 点搜索按钮才查」。
      所以这里绑的是 titleInput / authorInput（正在输入的值），
      点搜索 / 回车才同步到 applied* 并重查。
    -->
    <section class="search-band">
      <el-icon class="band-icon"><Search /></el-icon>

      <el-input
        v-model="titleInput"
        class="band-input"
        placeholder="书名关键词，回车搜索"
        clearable
        @keyup.enter="doSearch"
      />
      <el-input
        v-model="authorInput"
        class="band-input"
        placeholder="作者关键词，回车搜索"
        clearable
        @keyup.enter="doSearch"
      />

      <button type="button" class="band-btn is-primary" :disabled="loading" @click="doSearch">
        搜索
      </button>
      <button type="button" class="band-btn" :disabled="loading" @click="resetSearch">重置</button>

      <!-- 生效条件的回显：只在真的有搜索词时出现（输入框里有字但没提交时不算） -->
      <div v-if="hasSearch" class="band-chips">
        <span class="chip-label">正在筛选</span>
        <span v-if="appliedTitle" class="chip">书名含「{{ appliedTitle }}」</span>
        <span v-if="appliedAuthor" class="chip">作者含「{{ appliedAuthor }}」</span>
        <button type="button" class="chip-clear" @click="resetSearch">清空</button>
      </div>
    </section>

    <!-- ================= 分类导航 ================= -->
    <CategoryNav
      :categories="categories"
      :active="active"
      :all-count="totalCount"
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

    <!-- ================= 网格（8 列 × 3 行） ================= -->
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

      <!-- 空状态分两种：书架本来就是空的 vs 搜索没命中。后者要提示「清掉搜索词」 -->
      <el-empty
        v-else-if="!errorMsg"
        :description="hasSearch ? '没有符合条件的书，换个关键词试试' : '这个书架上还没有书'"
      />

      <!-- 滚动哨兵 + 状态行 -->
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

/* ---------- 搜索栏 ---------- */
/*
 * 木质面板：与「分类木牌」(`CategoryNav.vue`) 同一套色（暖金 #e8b96f / 边框
 * rgba(196,156,104,.26)），所以它看起来是这座书架的一部分，而不是一个外来控件。
 */
.search-band {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 18px;
  padding: 12px 14px;
  border: 1px solid rgba(196, 156, 104, 0.22);
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.3), rgba(0, 0, 0, 0.16));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.045), inset 0 -1px 0 rgba(0, 0, 0, 0.35);
}

.band-icon {
  flex-shrink: 0;
  font-size: 16px;
  color: rgba(232, 185, 111, 0.75);
}

.band-input {
  width: 220px;
  flex: 0 1 220px;
}

/*
 * ⚠️ 必须 `:deep()` —— el-input 的内部结构（wrapper / inner）不在本组件作用域内。
 * Element Plus 默认是**白底**输入框，直接放进来在深色页面上会亮得刺眼。
 */
.search-band :deep(.el-input__wrapper) {
  border-radius: 5px;
  background: rgba(18, 10, 5, 0.72);
  box-shadow: inset 0 0 0 1px rgba(196, 156, 104, 0.26);
  transition: box-shadow 0.16s ease;
}
.search-band :deep(.el-input__wrapper:hover) {
  box-shadow: inset 0 0 0 1px rgba(222, 178, 116, 0.5);
}
.search-band :deep(.el-input__wrapper.is-focus) {
  box-shadow: inset 0 0 0 1px rgba(232, 185, 111, 0.85), 0 0 10px -2px rgba(232, 185, 111, 0.45);
}
.search-band :deep(.el-input__inner) {
  color: #f2e7d6;
}
.search-band :deep(.el-input__inner::placeholder) {
  color: rgba(233, 220, 201, 0.34);
}
.search-band :deep(.el-input__suffix),
.search-band :deep(.el-input__clear) {
  color: rgba(233, 220, 201, 0.4);
}
.search-band :deep(.el-input__clear:hover) {
  color: rgba(232, 185, 111, 0.9);
}

/*
 * 两个按钮用原生 <button> 而不是 el-button：el-button 的底色来自 CSS 变量
 * （`--el-button-bg-color`），而我们要的是**渐变**，塞不进变量；硬压还得打特异性官司。
 */
.band-btn {
  flex-shrink: 0;
  padding: 7px 18px;
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
.band-btn:hover:not(:disabled) {
  color: #faecd6;
  border-color: rgba(222, 178, 116, 0.56);
  transform: translateY(-1px);
}
.band-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
/* 「搜索」是主操作，用木牌选中态那套金色 */
.band-btn.is-primary {
  border-color: rgba(238, 202, 142, 0.9);
  background: linear-gradient(180deg, #e0b878 0%, #c79a5b 100%);
  color: #2a1a0c;
  font-weight: 600;
}
.band-btn.is-primary:hover:not(:disabled) {
  color: #2a1a0c;
  box-shadow: 0 4px 12px -4px rgba(224, 184, 120, 0.7);
}

/* ---------- 生效条件回显 ---------- */
.band-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-left: 2px;
  padding-left: 12px;
  border-left: 1px solid rgba(196, 156, 104, 0.22);
}

.chip-label {
  font-size: 12px;
  color: rgba(233, 220, 201, 0.42);
  letter-spacing: 0.5px;
}

.chip {
  padding: 3px 10px;
  border: 1px solid rgba(196, 156, 104, 0.32);
  border-radius: 999px;
  background: rgba(232, 185, 111, 0.1);
  color: #e8c48a;
  font-size: 12px;
}

.chip-clear {
  padding: 0;
  border: none;
  background: none;
  color: rgba(233, 220, 201, 0.5);
  font-size: 12px;
  text-decoration: underline;
  cursor: pointer;
}
.chip-clear:hover {
  color: #e8b96f;
}

/* ---------- 网格 ---------- */
.grid-wrap {
  margin-top: 24px;
}

.grid {
  display: grid;
  /*
   * 8 列 × 3 行 = 一屏 24 本。
   * 2026-10-10 由 10 列改成 8 列 —— 10 列时封面只有 ~95px 宽，用户反馈看不清。
   * 行间距 30px 是给每张卡片底部的「抽屉面」留位置（见 `ShelfBookCard.vue` 的
   * `.card::after`，它向下探出 15px），否则抽屉会压到下一排的书。
   */
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
  .band-btn {
    transition: none;
  }
  .band-btn:hover:not(:disabled) {
    transform: none;
  }
}
</style>
