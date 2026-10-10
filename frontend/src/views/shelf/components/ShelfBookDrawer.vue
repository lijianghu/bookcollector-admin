<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ShelfBook } from '@/types'
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
 * 数据仍然复用同一个接口 `GET /api/books/{bookId}`（本版先用列表里已有的字段，
 * 不发额外请求）—— 「不要为了书架再复制一套详情数据接口」这条是遵守的。
 *
 * <h3>深色样式写在非 scoped 的 `<style>` 里</h3>
 * `el-drawer` 的骨架 DOM（`.el-overlay` / `.el-drawer__body` …）不属于本组件的作用域，
 * scoped 的 `data-v` 属性加不到它们身上。用 `.shelf-drawer` 这个自定义类名收口，
 * 避免污染其它抽屉。
 */
const props = defineProps<{
  modelValue: boolean
  book: ShelfBook | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

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
</style>
