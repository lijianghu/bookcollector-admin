<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPaidProgress,
  listPaidCategories,
  pausePaidCollect,
  resumePaidCollect,
  startPaidCollect,
  stopPaidCollect,
} from '@/api/paid'
import { formatNumber } from '@/utils/format'
import type { PaidCategoryVO, PaidCollectVO } from '@/types'

/**
 * 付费精选配置（2026-10-20）。
 *
 * <h3>它做什么</h3>
 * 列表 = `taxonomy_config` 里一级 + enabled=true 的分类，每行一个「启动」。
 * 点启动后按 `filterCategory` 分页请求 `/category/data`，把书采集入库。
 *
 * <h3>🔴 写入口径</h3>
 * 已有书籍只补 `collectSource` + `paidCollect=1`，<b>不覆盖其它字段</b>；
 * 不存在才插入。同一时刻只跑一个分类（后端互斥）。
 */

const loading = ref(false)
const rows = ref<PaidCategoryVO[]>([])
const progress = ref<PaidCollectVO | null>(null)
const startingCode = ref<string | null>(null)
const wasRunning = ref(false)

const POLL_MS = 2000
let pollTimer: number | null = null

const running = computed(() => progress.value?.running === true)

/** 进度百分比：已解析书数 / 接口声明的总数 */
const percent = computed(() => {
  const p = progress.value
  if (!p || !p.totalCount || p.totalCount <= 0) {
    return 0
  }
  return Math.min(100, Math.round((p.booksSaved / p.totalCount) * 100))
})

type StatusTag = { label: string; type: 'success' | 'warning' | 'danger' | 'primary' | 'info' }

/** 行状态：正在跑的行用内存进度（实时），其它行用持久化的 lastStatus */
function statusOf(row: PaidCategoryVO): StatusTag | null {
  const p = progress.value
  if (p && p.filterCategory === row.code) {
    if (p.errorMsg) {
      return { label: '失败', type: 'danger' }
    }
    if (p.paused) {
      return { label: '已暂停', type: 'warning' }
    }
    if (p.running) {
      return { label: '采集中', type: 'primary' }
    }
    return { label: '已完成', type: 'success' }
  }
  switch (row.lastStatus) {
    case 'RUNNING':
      return { label: '采集中', type: 'primary' }
    case 'PAUSED':
      return { label: '已暂停', type: 'warning' }
    case 'INTERRUPTED':
      return { label: '已中断', type: 'warning' }
    case 'COMPLETED':
      return { label: '已完成', type: 'success' }
    case 'STOPPED':
      return { label: '已停止', type: 'info' }
    case 'FAILED':
      return { label: '失败', type: 'danger' }
    default:
      return null
  }
}

const statusMap = computed(() => {
  const map: Record<string, StatusTag> = {}
  for (const row of rows.value) {
    const s = statusOf(row)
    if (s) {
      map[row.code] = s
    }
  }
  return map
})

function stopPoll(): void {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
}

function schedulePoll(): void {
  stopPoll()
  if (!running.value) {
    return
  }
  pollTimer = window.setTimeout(() => {
    void loadProgress()
  }, POLL_MS)
}

async function loadProgress(): Promise<void> {
  try {
    const data = await getPaidProgress()
    progress.value = data
    if (wasRunning.value && !data.running) {
      if (data.errorMsg) {
        ElMessage.error(`采集失败：${data.errorMsg}`)
      } else {
        ElMessage.success('采集已结束')
      }
      // 刷新持久化状态（完成/停止/失败要落到行上）
      void loadList()
    }
    wasRunning.value = data.running
  } catch {
    // 拦截器已提示
  }
  schedulePoll()
}

async function loadList(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listPaidCategories()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function onStart(row: PaidCategoryVO, reset = false): Promise<void> {
  const mode = reset ? '从头重新采集' : '启动采集（有断点则从断点继续）'
  try {
    await ElMessageBox.confirm(
      `将按「${row.name}」${mode}付费精选书籍。` +
        '已有书籍只补来源与 paidCollect 标记，不覆盖其它字段；同一时刻只跑一个分类。确定开始？',
      reset ? '从头采集' : '启动付费精选采集',
      { type: 'warning', confirmButtonText: reset ? '从头采集' : '开始采集', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  startingCode.value = row.code
  try {
    const data = await startPaidCollect(row.code, reset)
    progress.value = data
    wasRunning.value = data.running
    ElMessage.success('已提交，正在后台采集')
    schedulePoll()
  } catch {
    // 拦截器已提示（正在跑 / 分类不可采）
  } finally {
    startingCode.value = null
  }
}

async function onStop(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将停止当前付费精选采集（当前页跑完后退出）。确定停止？',
      '停止采集',
      { type: 'warning', confirmButtonText: '停止', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    progress.value = await stopPaidCollect()
    ElMessage.success('已请求停止')
  } catch {
    // 拦截器已提示
  }
}

async function onPause(): Promise<void> {
  try {
    progress.value = await pausePaidCollect()
    ElMessage.success('已请求暂停（当前页跑完后停住）')
  } catch {
    // 拦截器已提示
  }
}

async function onResume(): Promise<void> {
  try {
    progress.value = await resumePaidCollect()
    ElMessage.success('已继续采集')
    schedulePoll()
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  void loadList()
  await loadProgress()
})
onUnmounted(stopPoll)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false">
      <template #title>
        按 taxonomy_config 的一级分类（enabled=true）分页采集付费精选书籍。
        已有书籍只补 collectSource 与 paidCollect 标记，不覆盖其它字段；同一时刻只跑一个分类。
        暂停/重启后再次「启动」会从上次断点继续；「从头」清断点重采。
      </template>
    </el-alert>

    <!-- 进度条：弹窗/页面刷新后仍能看到 -->
    <div v-if="progress && (progress.running || progress.startedAtMs)" class="task-strip">
      <el-icon v-if="progress.running" class="spin"><Loading /></el-icon>
      <span class="strip-text">
        {{ progress.filterCategoryName || progress.filterCategory }}：
        {{ progress.page }} 页 · 解析 {{ formatNumber(progress.booksSaved) }} 本 ·
        新增 {{ formatNumber(progress.booksInserted) }} · 更新 {{ formatNumber(progress.booksUpdated) }}
        <template v-if="progress.totalCount"> · 总数 {{ formatNumber(progress.totalCount) }}</template>
      </span>
      <el-progress
        :percentage="percent"
        :status="progress.errorMsg ? 'exception' : progress.running ? undefined : 'success'"
        :stroke-width="8"
        class="strip-progress"
      />
      <el-button
        v-if="progress.running && !progress.paused"
        size="small"
        type="warning"
        plain
        @click="onPause"
      >
        暂停
      </el-button>
      <el-button
        v-if="progress.running && progress.paused"
        size="small"
        type="success"
        plain
        @click="onResume"
      >
        继续
      </el-button>
      <el-button v-if="progress.running" size="small" type="danger" plain @click="onStop">
        停止
      </el-button>
    </div>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">付费精选分类</span>
          <el-button size="small" :loading="loading" @click="loadList">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="rows" row-key="code" empty-text="没有可采集的分类">
        <el-table-column prop="code" label="分类 code" width="140" />
        <el-table-column prop="name" label="分类名" min-width="160" />
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag
              v-if="statusMap[row.code]"
              :type="statusMap[row.code]?.type"
              size="small"
              effect="plain"
            >
              {{ statusMap[row.code]?.label }}
            </el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              text
              :loading="startingCode === row.code"
              :disabled="running"
              @click="onStart(row)"
            >
              启动
            </el-button>
            <el-button
              size="small"
              type="info"
              text
              :disabled="running"
              @click="onStart(row, true)"
            >
              从头
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-title {
  font-weight: 600;
}

.task-strip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  background: #f4f8ff;
  border: 1px solid #d9ecff;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
}

.strip-text {
  flex-shrink: 0;
}

.strip-progress {
  flex: 1;
  min-width: 120px;
}

.text-muted {
  color: #909399;
}

.spin {
  margin-right: 4px;
  animation: spin 1.4s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
