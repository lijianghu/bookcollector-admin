<script setup lang="ts">
import type { ReadingStatus } from '@/types'
import { READING_STATUS_LABEL } from '@/types'

/**
 * 表格操作列里的「更多」下拉 —— 图书库 / 精选图书两个列表页共用。
 *
 * <h3>为什么是下拉而不是平铺按钮</h3>
 * 五个动作（加/移藏书、加/移阅读清单、改阅读状态）平铺要 250px 以上，
 * 而图书库表格容器在 1440 屏上只有约 1146px，默认列宽总和已经贴着上限 ——
 * 再撑宽就会出现横向滚动，而 Element Plus 的表格横向滚动条**默认隐藏**，
 * 用户只会以为「右边少了几列」。收进下拉后操作列反而更窄。
 *
 * <h3>🔴 它不自己持有状态</h3>
 * `onShelf` / `readingStatus` 由**页面**从 `useBookActions()` 里读出来传进来，
 * 动作也只 emit 出去。原因是：`useBookActions()` 的状态是**页面级**的，
 * 若组件内部各自调一次，会得到互不同步的独立实例 —— 点了一行，别的行不更新。
 */
defineProps<{
  /** 图书 ID。为空时整个下拉禁用（理论上不会发生） */
  bookId?: string | null
  /** 是否已在藏书阁 */
  onShelf: boolean
  /** 阅读清单里的状态；`null` = 不在阅读清单 */
  readingStatus: ReadingStatus | null
  /** 有动作在飞时禁用（避免连点） */
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'toggle-shelf'): void
  (e: 'toggle-reading'): void
  (e: 'set-status', status: ReadingStatus): void
}>()

/** 状态顺序 = 阅读的自然顺序（想读 → 在读 → 已读） */
const STATUSES: ReadingStatus[] = ['WANT_TO_READ', 'READING', 'FINISHED']

/** 下拉项统一用字符串 command 传值（el-dropdown 的 command 支持 string | number | object） */
function onCommand(command: string): void {
  if (command === 'shelf') {
    emit('toggle-shelf')
    return
  }
  if (command === 'reading') {
    emit('toggle-reading')
    return
  }
  emit('set-status', command as ReadingStatus)
}
</script>

<template>
  <el-dropdown trigger="click" :disabled="disabled || !bookId" @command="onCommand">
    <el-button link type="primary" :disabled="disabled || !bookId">
      更多
      <el-icon class="el-icon--right"><ArrowDown /></el-icon>
    </el-button>

    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="shelf">
          {{ onShelf ? '移除藏书' : '加入藏书' }}
        </el-dropdown-item>

        <el-dropdown-item command="reading" divided>
          {{ readingStatus ? '移出阅读清单' : '加入阅读清单' }}
        </el-dropdown-item>

        <!--
          三个状态项：当前状态那一项禁用（不能重复设成同一个值）。
          不在阅读清单时点任意一项 = 「加入并直接标成该状态」（后端 POST /books 支持初始状态），
          不会先加成「想读」再改 —— 见 useBookActions.setReadingStatus 的注释。
        -->
        <el-dropdown-item
          v-for="s in STATUSES"
          :key="s"
          :command="s"
          :disabled="readingStatus === s"
        >
          {{ readingStatus === s ? `当前：${READING_STATUS_LABEL[s]}` : `标记为${READING_STATUS_LABEL[s]}` }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
