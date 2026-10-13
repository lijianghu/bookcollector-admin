<script setup lang="ts">
import type { ShelfCategory } from '@/types'

/**
 * 分类导航 —— 做成「木牌」的样子。
 *
 * <h3>类目不是写死的</h3>
 * 这份清单来自 `GET /api/shelf/categories`，即**数据里真实存在的类目**。
 * 需求明确要求「不在前端硬编码分类列表」—— 硬编码的后果是数据变了页面不跟着变，
 * 而且新类目永远出不来。
 *
 * <p>每块木牌上的数字是这一类目下的图书数量，用来回答「这个架子值不值得点进去」。
 * 数量很少的类目（个位数）不是 bug，是上游本来就只有那么多书。
 *
 * <p>2026-10-10：最前面多了一块**前端自建的「全部」木牌**（值 = 空串，默认选中）。
 */
defineProps<{
  categories: ShelfCategory[]
  /** 当前选中的一级类目名。**空串 = 全部** */
  active: string
  /** 「全部」木牌上的数量 = 各类目之和（父组件算好传进来，这里不重复算一遍） */
  allCount: number
  loading?: boolean
}>()

const emit = defineEmits<{
  (e: 'change', name: string): void
}>()
</script>

<template>
  <nav class="nav" aria-label="书架分类">
    <el-skeleton v-if="loading && categories.length === 0" :rows="1" animated class="skeleton" />

    <template v-else>
      <!--
        「全部」是**前端自建**的一块木牌，不是后端返回的类目。
        🔴 它的值必须是**空串**，不能写字面量 "全部" —— 空串透到后端
        （`ShelfServiceImpl.topCategoryOf("")`）会被归一成 null = 不筛；
        而 "全部" 会变成一个真实的类目正则前缀，恒返回 0 条，且**不报错**。
      -->
      <button
        type="button"
        class="plaque"
        :class="{ 'is-active': active === '' }"
        :aria-pressed="active === ''"
        @click="emit('change', '')"
      >
        <span class="plaque-name">全部</span>
        <span class="plaque-count">{{ allCount }}</span>
      </button>

      <button
        v-for="item in categories"
        :key="item.name"
        type="button"
        class="plaque"
        :class="{ 'is-active': item.name === active }"
        :aria-pressed="item.name === active"
        @click="emit('change', item.name)"
      >
        <span class="plaque-name">{{ item.name }}</span>
        <span class="plaque-count">{{ item.count }}</span>
      </button>
    </template>
  </nav>
</template>

<style scoped>
.nav {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 34px;
}

.skeleton {
  width: 100%;
}

/* ---------- 木牌 ---------- */
.plaque {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 6px 13px;
  border: 1px solid rgba(196, 156, 104, 0.26);
  border-radius: 5px;
  /* 木牌的质感：竖纹 + 上下渐深 */
  background:
    repeating-linear-gradient(
      88deg,
      rgba(0, 0, 0, 0.09) 0 2px,
      rgba(255, 255, 255, 0.025) 2px 5px
    ),
    linear-gradient(180deg, #4a3119 0%, #3a2513 100%);
  color: rgba(236, 216, 186, 0.78);
  font-size: 13px;
  cursor: pointer;
  transition: transform 0.14s ease, border-color 0.14s ease, color 0.14s ease,
    box-shadow 0.14s ease;
}

.plaque:hover {
  color: rgba(250, 236, 214, 0.98);
  border-color: rgba(222, 178, 116, 0.56);
  transform: translateY(-1px);
}

.plaque.is-active {
  color: #2a1a0c;
  border-color: rgba(238, 202, 142, 0.9);
  background: linear-gradient(180deg, #e0b878 0%, #c79a5b 100%);
  box-shadow: 0 4px 12px -4px rgba(224, 184, 120, 0.7);
}

.plaque-name {
  font-weight: 600;
  letter-spacing: 0.5px;
}

.plaque-count {
  font-size: 11px;
  opacity: 0.7;
  font-variant-numeric: tabular-nums;
}

.plaque.is-active .plaque-count {
  opacity: 0.62;
}

@media (prefers-reduced-motion: reduce) {
  .plaque {
    transition: none;
  }
  .plaque:hover {
    transform: none;
  }
}
</style>
