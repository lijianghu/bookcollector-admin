<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getRefreshJob, triggerRefresh, updateRefreshConfig } from '@/api/refresh'
import ProTable from '@/components/ProTable.vue'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import {
  REFRESH_STATUS_LABEL,
  REFRESH_STATUS_TAG,
  type RefreshDetail,
  type RefreshJobVO,
  type RefreshStatus,
} from '@/types'

/**
 * 定时刷新（7 个榜单 + 期刊杂志）。
 *
 * <h3>它解决什么问题</h3>
 * 榜单按天滚动、期刊按期上新，而普通分类（如「文学」）的历史数据基本不动。
 * 所以只有这 8 个目标需要**反复**采。手工去任务中心一个个点太麻烦，
 * 这里一键跑完，还能挂个 cron 自动跑。
 *
 * <h3>🔴 它和「采集任务」不是一回事（页面上必须讲清楚）</h3>
 * - **不建任务、不产生运行记录** → 任务中心里看不到它
 * - **完全不碰采集游标** → 手工建的榜单任务该怎么续传还怎么续传
 * - **没有断点续传**：每次从第 0 页跑到底。榜单每期换一批书，
 *   「从上次的位置继续翻」会漏掉排在前面的新上榜书
 *
 * <h3>轮询</h3>
 * 「立即执行」是**提交即返回**（采集要跑 1~2 分钟，HTTP 挂着会撞 30 秒超时），
 * 所以靠轮询 `GET /api/refresh/job` 跟进。是否要继续轮询看后端的 `running` ——
 * 它来自后端**内存**，所以刷新页面之后也能接着轮询，不会卡在「执行中」。
 *
 * <h3>执行中为什么看得到进度</h3>
 * 后端在跑的时候会往内存里发布一份**实时明细快照**（每个目标先占位成 `RUNNING`，
 * 再逐页更新页数/本数），`lastDetails` 拿到的就是它。
 * 所以这里的表格**执行中也在渲染**，「刷新」按钮在跑的时候会变成「刷新进度」，
 * 手动点一下就能立刻拉一次最新状态 —— 不用干等 2 秒的轮询。
 */

const loading = ref(false)
const saving = ref(false)
const triggering = ref(false)
const job = ref<RefreshJobVO | null>(null)

/** 表单态。只在**非轮询**的加载里同步，避免轮询把用户正在编辑的内容冲掉 */
const cronInput = ref('')
const enabledInput = ref(false)

/** cron 常用预设。⚠️ 是 Spring 的 6 段式（秒 分 时 日 月 周），不是 Linux 的 5 段式 */
const CRON_PRESETS = [
  { label: '每天 03:00', value: '0 0 3 * * ?' },
  { label: '每 6 小时', value: '0 0 */6 * * ?' },
  { label: '每周一 03:00', value: '0 0 3 ? * MON' },
]

const running = computed(() => job.value?.running === true)
const targets = computed(() => job.value?.targets ?? [])
const details = computed<RefreshDetail[]>(() => job.value?.lastDetails ?? [])

/**
 * 已经有结果的目标数。
 *
 * ⚠️ 执行中的目标在明细里是 `RUNNING` 占位项，**不算**「已完成」——
 * 否则会显示「已完成 3 / 8」而实际只有 2 个跑完。
 */
const doneCount = computed(
  () => details.value.filter((d) => !!d.status && d.status !== 'RUNNING').length,
)

/** 当前正在采的目标名。没有则为空串 */
const currentName = computed(
  () => details.value.find((d) => d.status === 'RUNNING')?.targetName ?? '',
)

/** 表单是否被改过（用来提示「改完要点保存」） */
const dirty = computed(
  () =>
    job.value !== null &&
    (cronInput.value.trim() !== job.value.cron || enabledInput.value !== job.value.enabled),
)

// ---------------------------------------------------------------------------
// 加载 + 轮询
// ---------------------------------------------------------------------------

const POLL_MS = 2000
let pollTimer: number | null = null

function stopPoll(): void {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
}

/** 只有后端说还在跑，才继续轮询 */
function schedulePoll(): void {
  stopPoll()
  if (!running.value) {
    return
  }
  pollTimer = window.setTimeout(() => {
    void load(true)
  }, POLL_MS)
}

/**
 * @param silent true = 轮询触发的静默刷新：不显示 loading、不回填表单
 */
async function load(silent = false): Promise<void> {
  if (!silent) {
    loading.value = true
  }
  try {
    const data = await getRefreshJob()
    job.value = data
    if (!silent) {
      cronInput.value = data.cron
      enabledInput.value = data.enabled
    }
  } catch {
    // 拦截器已提示
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
  schedulePoll()
}

// ---------------------------------------------------------------------------
// 改配置
// ---------------------------------------------------------------------------

async function onSave(): Promise<void> {
  const cron = cronInput.value.trim()
  if (!cron) {
    ElMessage.warning('cron 表达式不能为空')
    return
  }
  saving.value = true
  try {
    const data = await updateRefreshConfig({ cron, enabled: enabledInput.value })
    job.value = data
    cronInput.value = data.cron
    enabledInput.value = data.enabled
    ElMessage.success('配置已保存，立即生效')
  } catch {
    // 拦截器已提示（例如 cron 格式不合法）
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------------------
// 立即执行
// ---------------------------------------------------------------------------

async function onTrigger(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将对 8 个目标（7 个榜单 + 期刊杂志）做一次全量刷新，每个目标从第一页采到采完为止。' +
        '如果某个目标正在被手动采集，整批会立即中止（已经跑完的目标不会回滚）。',
      '立即执行',
      { type: 'warning', confirmButtonText: '开始刷新', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  triggering.value = true
  try {
    const data = await triggerRefresh()
    job.value = data
    ElMessage.success('已提交，正在后台执行')
    schedulePoll()
  } catch {
    // 拦截器已提示（「正在执行中」/「被手动采集占用」）
  } finally {
    triggering.value = false
  }
}

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

function statusTag(status?: RefreshStatus | null): 'success' | 'warning' | 'info' | 'primary' | 'danger' {
  return status ? REFRESH_STATUS_TAG[status] : 'info'
}

function statusText(status?: RefreshStatus | null): string {
  return status ? REFRESH_STATUS_LABEL[status] : '-'
}

/** 毫秒 → 人话。不足 1 秒显示 ms，否则保留 1 位小数的秒 */
function formatCost(ms?: number | null): string {
  if (ms === null || ms === undefined) {
    return '-'
  }
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

/** 触发方式 → 人话 */
function triggerText(trigger?: string | null): string {
  if (trigger === 'MANUAL') {
    return '手动执行'
  }
  if (trigger === 'SCHEDULED') {
    return '定时触发'
  }
  return '-'
}

onMounted(() => {
  void load()
})
onUnmounted(stopPoll)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" class="intro">
      <template #title>
        定时刷新针对 <b>7 个榜单 + 期刊杂志</b>：一次全量采集，每个目标从第一页采到采完为止。
        可以点「立即执行」手动跑，也可以设定时间让它自动跑。
      </template>
    </el-alert>

    <!-- ============ 配置 ============ -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">刷新配置</span>
          <el-tag v-if="running" type="primary" effect="dark" size="small">
            <el-icon class="spin"><Loading /></el-icon>
            执行中
          </el-tag>
        </div>
      </template>

      <el-form label-width="110px" class="config-form">
        <el-form-item label="cron 表达式">
          <div class="cron-row">
            <el-input
              v-model="cronInput"
              placeholder="0 0 3 * * ?"
              :disabled="running"
              class="cron-input"
            />
            <el-button-group>
              <el-button
                v-for="p in CRON_PRESETS"
                :key="p.value"
                :disabled="running"
                @click="cronInput = p.value"
              >
                {{ p.label }}
              </el-button>
            </el-button-group>
          </div>
          <div class="hint">
            6 段式：<b>秒 分 时 日 月 周</b>。
            例：<code>0 0 3 * * ?</code> = 每天凌晨 3:00。
          </div>
        </el-form-item>

        <el-form-item label="启用定时">
          <el-switch v-model="enabledInput" :disabled="running" />
          <span class="hint inline">
            关掉只影响自动触发，「立即执行」随时可用。
          </span>
        </el-form-item>

        <el-form-item label="下次触发">
          <span class="next-fire">{{ formatDateTime(job?.nextFireTime) }}</span>
          <span v-if="job && !job.enabled" class="hint inline">（定时未启用）</span>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" :disabled="running" @click="onSave">
            保存配置
          </el-button>
          <el-button
            type="success"
            :loading="triggering"
            :disabled="running"
            @click="onTrigger"
          >
            <el-icon><VideoPlay /></el-icon>
            立即执行
          </el-button>
          <el-button :loading="loading" @click="load()">
            <el-icon><Refresh /></el-icon>
            {{ running ? '刷新进度' : '刷新' }}
          </el-button>
          <span v-if="dirty" class="hint inline dirty">配置有改动，别忘了点「保存配置」</span>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ============ 目标清单 ============ -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">刷新目标（{{ targets.length }} 个）</span>
        </div>
      </template>
      <div class="target-list">
        <el-tag
          v-for="t in targets"
          :key="`${t.type}:${t.targetId}`"
          :type="t.type === 'RANKING' ? 'warning' : 'primary'"
          effect="plain"
          class="target-tag"
        >
          {{ t.name }}
        </el-tag>
      </div>
    </el-card>

    <!-- ============ 最近一次结果 ============ -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ running ? '本次执行进度' : '最近一次执行' }}</span>
          <el-tag
            v-if="!running && job"
            :type="statusTag(job.lastRunStatus)"
            effect="dark"
            size="small"
          >
            {{ job.lastRunStatusLabel }}
          </el-tag>
          <span v-if="running" class="hint">
            已完成 {{ doneCount }} / {{ targets.length }} 个目标<template v-if="currentName">，正在采「{{ currentName }}」</template>
          </span>
        </div>
      </template>

      <el-descriptions v-if="job" :column="4" size="small" border class="summary">
        <el-descriptions-item label="开始时间">
          {{ formatDateTime(job.lastRunAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="结束时间">
          {{ formatDateTime(job.lastRunFinishedAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="触发方式">
          {{ triggerText(job.lastRunTrigger) }}
        </el-descriptions-item>
        <el-descriptions-item label="结果统计">
          成功 {{ dash(job.lastRunSuccess) }} · 未成功 {{ dash(job.lastRunFailed) }}
        </el-descriptions-item>
        <el-descriptions-item label="说明" :span="4">
          {{ dash(job.lastRunMessage) }}
        </el-descriptions-item>
      </el-descriptions>

      <ProTable
        :data="details"
        :loading="loading"
        :show-pagination="false"
        row-key="targetId"
        empty-text="还没有执行过。点上面的「立即执行」，或等 cron 到点自动跑。"
      >
        <el-table-column label="目标" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag size="small" effect="plain" class="type-tag">
              {{ row.targetType === 'RANKING' ? '榜单' : '分类' }}
            </el-tag>
            {{ row.targetName ?? dash(row.targetId) }}
          </template>
        </el-table-column>

        <el-table-column label="结果" width="92" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)" effect="dark">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="页数" width="72" align="right">
          <template #default="{ row }">{{ dash(row.pagesDone) }}</template>
        </el-table-column>

        <el-table-column label="写入/更新" width="100" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.booksSaved) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="新增" width="86" align="right">
          <template #default="{ row }">
            <span class="num highlight">{{ formatNumber(row.booksInserted) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="目标总数" width="92" align="right">
          <template #default="{ row }">{{ formatNumber(row.totalCount) }}</template>
        </el-table-column>

        <el-table-column label="耗时" width="86" align="right">
          <template #default="{ row }">{{ formatCost(row.costMs) }}</template>
        </el-table-column>

        <el-table-column label="说明" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ dash(row.message) }}</template>
        </el-table-column>
      </ProTable>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.intro :deep(.el-alert__title) {
  line-height: 1.7;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-title {
  font-weight: 600;
}

.config-form {
  max-width: 100%;
}

.cron-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.cron-input {
  max-width: 260px;
}

.hint {
  font-size: 12px;
  line-height: 1.7;
  color: #909399;
}

.hint.inline {
  margin-left: 10px;
}

.hint.dirty {
  color: #e6a23c;
}

.hint code {
  padding: 1px 4px;
  background: #f5f7fa;
  border-radius: 3px;
}

.next-fire {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.target-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.target-tag {
  height: auto;
  padding: 5px 10px;
  line-height: 1.5;
}

.summary {
  margin-bottom: 14px;
}

.type-tag {
  margin-right: 4px;
}

.num {
  font-variant-numeric: tabular-nums;
}
.highlight {
  font-weight: 600;
  color: #409eff;
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
