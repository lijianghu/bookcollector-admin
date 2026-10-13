<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { READING_STATUS_LABEL, type ReadingStatus, type ShelfBook } from '@/types'
import { fallbackCoverStyle } from '@/utils/shelfVisual'
import { dash, formatNumber } from '@/utils/format'

/**
 * 书架的图书详情面板。
 *
 * <h3>为什么不复用 `components/DetailDrawer.vue`</h3>
 * 那个组件是**后台风**的：白底 `el-descriptions` + 60 个字段分 5 组。
 * 放在深色书架里会像「点开一本书结果弹出一张后台表格」，而且它 46 个字段里
 * 大半是 `webBookControl` / `blockSaveImg` 这类书架读者不关心的。
 *
 * <p>所以这里只挑书架语境需要的：封面、书名、作者、分类、评分与人数、简介。
 * 数据仍然复用同一个接口 `GET /api/books/{bookId}`。
 *
 * <h3>2026-10-12：从纯只读变成带操作</h3>
 * 需求要求「我的藏书」页也能做阅读清单相关的动作。原来这个抽屉**一个按钮都没有**，
 * 只能看 —— 现在底部加了操作区：
 * <ul>
 *   <li>阅读状态三选一（想读 / 在读 / 已读）—— <b>不在清单里时点它 = 加入并直接标成该状态</b>；</li>
 *   <li>「加入 / 移出阅读清单」；</li>
 *   <li>「移除藏书」（本页列的都是藏书，所以只有移除）。</li>
 * </ul>
 *
 * <h3>🔴 它不自己持有状态</h3>
 * `onShelf` / `readingStatus` 由页面从 `useBookActions()` 读出来传进来，动作只 emit 出去。
 * 组件内部各自调一次 composable 会得到互不同步的独立实例。
 *
 * <h3>深色样式写在非 scoped 的 `<style>` 里</h3>
 * `el-drawer` 的骨架 DOM（`.el-overlay` / `.el-drawer__body` …）不属于本组件的作用域。
 * 用 `.shelf-drawer` 这个自定义类名收口，避免污染其它抽屉。
 */
const props = defineProps<{
  modelValue: boolean
  book: ShelfBook | null
  /** 是否已在藏书阁。本页列的都是藏书，正常恒为 true —— 留着是为了让组件本身不假设这一点 */
  onShelf?: boolean
  /** 阅读清单里的状态；`null` = 不在阅读清单 */
  readingStatus?: ReadingStatus | null
  /** 有动作在飞时禁用按钮 */
  busy?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'toggle-shelf'): void
  (e: 'toggle-reading'): void
  (e: 'set-status', status: ReadingStatus): void
}>()

/** 状态顺序 = 阅读的自然顺序 */
const STATUSES: ReadingStatus[] = ['WANT_TO_READ', 'READING', 'FINISHED']

const coverFailed = ref(false)

// 换了一本书就重置失败标记 —— 否则上一本封面加载失败会让这一本也走生成式封面
watch(
  () => props.book?.bookId,
  () => {
    coverFailed.value = false
  },
)

const generatedStyle = computed(() => fallbackCoverStyle(props.book?.bookId))

/** 推荐值 0~1000 → 百分比文案。913 → "91.3%" */
const ratingText = computed(() => {
  const r = props.book?.newRating
  if (r === null || r === undefined || r <= 0) {
    return '—'
  }
  return `${(r / 10).toFixed(1)}%`
})

const inReadingList = computed(() => props.readingStatus !== null && props.readingStatus !== undefined)

function handleVisibleChange(value: boolean): void {
  emit('update:modelValue', value)
}
</script>

<template>
  <el-drawer
    class="shelf-drawer"
    :model-value="modelValue"
    :size="440"
    direction="rtl"
    :with-header="false"
    @update:model-value="handleVisibleChange"
  >
    <div v-if="book" class="panel">
      <button class="close" type="button" aria-label="关闭" @click="handleVisibleChange(false)">
        ×
      </button>

      <div class="hero">
        <div class="hero-cover">
          <img
            v-if="book.cover && !coverFailed"
            :src="book.cover"
            :alt="book.title || ''"
            @error="coverFailed = true"
          />
          <div v-else class="hero-generated" :style="generatedStyle">
            <span>{{ book.title || '未命名' }}</span>
          </div>
        </div>

        <div class="hero-meta">
          <h2 class="title">{{ book.title || '未命名' }}</h2>
          <p class="author">{{ dash(book.author) }}</p>
          <span v-if="book.category" class="category">{{ book.category }}</span>

          <div class="rating">
            <span class="rating-value">{{ ratingText }}</span>
            <span class="rating-label">推荐值</span>
          </div>
          <dl class="counts">
            <div>
              <dt>评价人数</dt>
              <dd>{{ formatNumber(book.newRatingCount) }}</dd>
            </div>
            <div>
              <dt>阅读人数</dt>
              <dd>{{ formatNumber(book.readingCount) }}</dd>
            </div>
          </dl>
        </div>
      </div>

      <section class="intro">
        <h3>简介</h3>
        <p>{{ book.intro || '这本书没有提供简介。' }}</p>
      </section>

      <!-- ============ 操作区（2026-10-12 新增） ============ -->
      <section class="ops">
        <h3>阅读计划</h3>

        <!--
          三个状态按钮。⚠️ 不在阅读清单时点任意一个 = 「加入并直接标成该状态」
          （后端 POST /books 支持带初始状态），不会先加成「想读」再改。
        -->
        <div class="status-row">
          <button
            v-for="s in STATUSES"
            :key="s"
            type="button"
            class="status-btn"
            :class="{ active: readingStatus === s }"
            :disabled="busy"
            @click="emit('set-status', s)"
          >
            {{ READING_STATUS_LABEL[s] }}
          </button>
        </div>
        <p class="ops-hint">
          {{ inReadingList ? '点击可切换阅读状态。' : '点击任一状态即加入阅读清单。' }}
        </p>

        <div class="op-row">
          <button type="button" class="op-btn" :disabled="busy" @click="emit('toggle-reading')">
            {{ inReadingList ? '移出阅读清单' : '加入阅读清单' }}
          </button>
          <button
            type="button"
            class="op-btn"
            :class="{ 'is-danger': onShelf }"
            :disabled="busy"
            @click="emit('toggle-shelf')"
          >
            {{ onShelf ? '移除藏书' : '加入藏书' }}
          </button>
        </div>
      </section>
    </div>
  </el-drawer>
</template>

<style>
/* 非 scoped：`el-drawer` 的骨架不在本组件作用域内，靠 .shelf-drawer 收口 */
.shelf-drawer .el-drawer__body {
  padding: 0;
  background: linear-gradient(168deg, #221710 0%, #180f09 100%);
}

.shelf-drawer .el-drawer {
  box-shadow: -14px 0 40px -10px rgba(0, 0, 0, 0.75);
}
</style>

<style scoped>
.panel {
  position: relative;
  height: 100%;
  overflow-y: auto;
  padding: 26px 24px 32px;
  color: #e9dcc9;
}

.close {
  position: absolute;
  top: 14px;
  right: 16px;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(233, 220, 201, 0.72);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}

.close:hover {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
}

/* ---------- 头部：封面 + 关键信息 ---------- */
.hero {
  display: flex;
  gap: 18px;
}

.hero-cover {
  flex: 0 0 132px;
  width: 132px;
  height: 184px;
  border-radius: 4px;
  overflow: hidden;
  background: #2a1e14;
  box-shadow: 0 14px 26px -10px rgba(0, 0, 0, 0.85);
}

.hero-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.hero-generated {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  border: 1px solid;
  border-radius: 4px;
  padding: 14px 11px;
  display: flex;
  align-items: center;
  font-size: 14px;
  line-height: 1.4;
  font-weight: 600;
  color: rgba(255, 248, 238, 0.95);
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.65);
  overflow: hidden;
}

.hero-meta {
  min-width: 0;
  flex: 1;
}

.title {
  margin: 0 0 6px;
  font-size: 19px;
  line-height: 1.35;
  font-weight: 600;
  color: #f6ecdc;
}

.author {
  margin: 0 0 10px;
  font-size: 13px;
  color: rgba(233, 220, 201, 0.66);
}

.category {
  display: inline-block;
  padding: 2px 9px;
  border: 1px solid rgba(206, 166, 108, 0.38);
  border-radius: 10px;
  font-size: 11px;
  color: rgba(226, 194, 148, 0.92);
}

.rating {
  margin-top: 14px;
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.rating-value {
  font-size: 27px;
  font-weight: 700;
  color: #e8b96f;
  font-variant-numeric: tabular-nums;
}

.rating-label {
  font-size: 11px;
  color: rgba(233, 220, 201, 0.55);
}

.counts {
  display: flex;
  gap: 22px;
  margin: 12px 0 0;
}

.counts dt {
  font-size: 11px;
  color: rgba(233, 220, 201, 0.5);
}

.counts dd {
  margin: 2px 0 0;
  font-size: 15px;
  font-variant-numeric: tabular-nums;
  color: rgba(246, 236, 220, 0.92);
}

/* ---------- 简介 ---------- */
.intro {
  margin-top: 26px;
  padding-top: 18px;
  border-top: 1px solid rgba(198, 158, 106, 0.16);
}

.intro h3 {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 1px;
  color: rgba(226, 194, 148, 0.88);
}

.intro p {
  margin: 0;
  font-size: 13px;
  line-height: 1.9;
  color: rgba(233, 220, 201, 0.78);
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 操作区 ---------- */
.ops {
  margin-top: 26px;
  padding-top: 18px;
  border-top: 1px solid rgba(198, 158, 106, 0.16);
}

.ops h3 {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 1px;
  color: rgba(226, 194, 148, 0.88);
}

.status-row {
  display: flex;
  gap: 8px;
}

/*
 * 状态按钮用原生 <button>：要的是「选中态是金色实心、未选是描边」这套渐变，
 * 而 el-button 的底色来自 CSS 变量，塞不进渐变（同 shelf 搜索栏那两个按钮的处理）。
 */
.status-btn {
  flex: 1;
  padding: 7px 0;
  border: 1px solid rgba(196, 156, 104, 0.3);
  border-radius: 5px;
  background: linear-gradient(180deg, rgba(74, 49, 25, 0.9), rgba(58, 37, 19, 0.9));
  color: rgba(236, 216, 186, 0.82);
  font-size: 13px;
  letter-spacing: 1px;
  cursor: pointer;
  transition: border-color 0.14s ease, color 0.14s ease, box-shadow 0.14s ease;
}

.status-btn:hover:not(:disabled) {
  color: #faecd6;
  border-color: rgba(222, 178, 116, 0.56);
}

.status-btn.active {
  border-color: rgba(238, 202, 142, 0.9);
  background: linear-gradient(180deg, #e0b878 0%, #c79a5b 100%);
  color: #2a1a0c;
  font-weight: 600;
}

.status-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ops-hint {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: rgba(233, 220, 201, 0.42);
}

.op-row {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}

.op-btn {
  flex: 1;
  padding: 8px 0;
  border: 1px solid rgba(196, 156, 104, 0.3);
  border-radius: 5px;
  background: transparent;
  color: rgba(236, 216, 186, 0.82);
  font-size: 13px;
  letter-spacing: 1px;
  cursor: pointer;
  transition: border-color 0.14s ease, color 0.14s ease;
}

.op-btn:hover:not(:disabled) {
  color: #faecd6;
  border-color: rgba(222, 178, 116, 0.56);
}

.op-btn.is-danger {
  border-color: rgba(196, 90, 80, 0.45);
  color: rgba(228, 150, 142, 0.9);
}

.op-btn.is-danger:hover:not(:disabled) {
  color: #ffd9d4;
  border-color: rgba(214, 110, 100, 0.75);
}

.op-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
