<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  clearCandidates,
  getPublishJob,
  initCandidates,
  pageInterpretations,
  resetInterpretation,
  triggerPublish,
  updatePublishConfig,
} from '@/api/ai'
import ProTable from '@/components/ProTable.vue'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import {
  PUBLISH_STATUS_LABEL,
  PUBLISH_STATUS_TAG,
  type InterpretationVO,
  type PublishJobVO,
  type PublishStatus,
} from '@/types'

/**
 * AI 书籍解读。
 *
 * <h3>它做什么</h3>
 * 从候选清单里挑一本高质量图书 → 生成一篇解读文章 → 存成本地 Markdown →
 * 发布到语雀「书籍解读」目录。可以点按钮手动做一本，也可以设个时间让它自动做。
 *
 * <h3>为什么要先「初始化候选」</h3>
 * 图书库有几万条，每次现挑「符合条件且还没解读过」的书代价很大。
 * 「初始化候选」会先把符合条件的书挑出来放进一份清单，
 * 之后挑书只在这份清单里找。已有的会被跳过，所以可以反复点。
 *
 * <h3>🔴 两个长任务都是「提交即返回」</h3>
 * 「初始化候选」和「生成并发布」点完立刻返回，任务在后台跑。
 * 页面靠 2 秒轮询跟进：是否要继续轮询看后端的 `initRunning` / `genRunning` ——
 * 它们来自后端**内存**，所以刷新页面之后也能接着看，不会卡在「执行中」。
 *
 * <h3>状态筛选</h3>
 * 进程被杀会留下「生成中」的残留记录（后端刻意不做自动清理）。
 * 用状态筛选把它们找出来，点行内的「重置为待解读」就能重新排队。
 */

const loading = ref(false)
const saving = ref(false)
const initing = ref(false)
const triggering = ref(false)
const clearing = ref(false)
const job = ref<PublishJobVO | null>(null)

/** 表单态。只在**非轮询**的加载里同步，避免轮询把用户正在编辑的内容冲掉 */
const cronInput = ref('')
const enabledInput = ref(false)

/** cron 常用预设。⚠️ 是 Spring 的 6 段式（秒 分 时 日 月 周），不是 Linux 的 5 段式 */
const CRON_PRESETS = [
  { label: '每天 04:00', value: '0 0 4 * * ?' },
  { label: '每天 12:00', value: '0 0 12 * * ?' },
  { label: '每 6 小时', value: '0 0 */6 * * ?' },
]

// ---- 清单 ----
const rows = ref<InterpretationVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const statusFilter = ref<PublishStatus | ''>('')

const STATUS_OPTIONS: { label: string; value: PublishStatus | '' }[] = [
  { label: '全部', value: '' },
  { label: '待解读', value: 'PENDING' },
  { label: '生成中', value: 'GENERATING' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '失败', value: 'FAILED' },
]

const initRunning = computed(() => job.value?.initRunning === true)
const genRunning = computed(() => job.value?.genRunning === true)
const running = computed(() => initRunning.value || genRunning.value)

/** 表单是否被改过（用来提示「改完要点保存」） */
const dirty = computed(
  () =>
    job.value !== null &&
    (cronInput.value.trim() !== job.value.cron || enabledInput.value !== job.value.enabled),
)

/** 后端配好模型和语雀了吗。任缺其一，生成一定失败，所以提前在页面上讲清楚 */
const ready = computed(() => job.value?.modelConfigured === true && job.value?.yuqueConfigured === true)

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
    void poll()
  }, POLL_MS)
}

/** 轮询一拍：任务状态 + 清单一起刷。清单也刷是因为「待解读」会变成「已发布」 */
async function poll(): Promise<void> {
  await load(true)
  await loadList()
}

/**
 * @param silent true = 轮询触发的静默刷新：不显示 loading、不回填表单
 */
async function load(silent = false): Promise<void> {
  if (!silent) {
    loading.value = true
  }
  try {
    const data = await getPublishJob()
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

async function loadList(): Promise<void> {
  try {
    const data = await pageInterpretations({
      status: statusFilter.value,
      page: page.value,
      size: size.value,
    })
    rows.value = data.list
    total.value = data.total
  } catch {
    // 拦截器已提示
  }
}

/** 首屏：两个都加载 */
async function loadAll(): Promise<void> {
  await load()
  await loadList()
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
    const data = await updatePublishConfig({ cron, enabled: enabledInput.value })
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
// 初始化候选
// ---------------------------------------------------------------------------

async function onInit(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将按当前的分类白名单 + 评级档位 + 评价人数下限，从图书库里挑出符合条件的图书放进候选清单。' +
        '已经在清单里的会跳过，所以可以放心重复执行。',
      '初始化候选',
      { type: 'warning', confirmButtonText: '开始初始化', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  initing.value = true
  try {
    const data = await initCandidates()
    job.value = data
    ElMessage.success('已提交，正在后台执行')
    schedulePoll()
  } catch {
    // 拦截器已提示
  } finally {
    initing.value = false
  }
}

// ---------------------------------------------------------------------------
// 生成并发布
// ---------------------------------------------------------------------------

async function onTrigger(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将挑一本待解读的书，生成解读文章、保存到本地，并发布到语雀「书籍解读」目录。' +
        '一次只处理一本，生成通常需要 1~3 分钟。',
      '生成并发布',
      { type: 'warning', confirmButtonText: '开始生成', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  triggering.value = true
  try {
    const data = await triggerPublish()
    job.value = data
    ElMessage.success('已提交，正在后台执行')
    schedulePoll()
  } catch {
    // 拦截器已提示（「已有一本正在生成中」/「候选清单为空」）
  } finally {
    triggering.value = false
  }
}

// ---------------------------------------------------------------------------
// 清空 / 重置
// ---------------------------------------------------------------------------

async function onClear(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将删除清单里所有「待解读」的条目。已发布、失败、生成中的记录不受影响。' +
        '换过分类白名单之后，旧候选已经不再符合条件，需要先清空再重新初始化。',
      '清空未解读候选',
      { type: 'warning', confirmButtonText: '确认清空', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  clearing.value = true
  try {
    const data = await clearCandidates()
    job.value = data
    page.value = 1
    await loadList()
    ElMessage.success('已清空未解读候选')
  } catch {
    // 拦截器已提示
  } finally {
    clearing.value = false
  }
}

async function onReset(row: InterpretationVO): Promise<void> {
  // 已经发布过的重跑会在语雀里**新增**一篇（旧的那篇不会删）—— 这是边界条件，必须讲出来
  const alreadyPublished = row.status === 'PUBLISHED'
  const extra = alreadyPublished
    ? '⚠️ 这本书已经发布过了。重新生成会在语雀里新增一篇文档，旧的那篇不会被删除。'
    : ''
  try {
    await ElMessageBox.confirm(
      `把《${row.title ?? row.bookId}》重置为「待解读」，下次挑书时它会重新参与。${extra}`,
      '重置为待解读',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await resetInterpretation(row.bookId)
    ElMessage.success('已重置为待解读')
    await load()
    await loadList()
  } catch {
    // 拦截器已提示
  }
}

// ---------------------------------------------------------------------------
// 筛选 / 分页
// ---------------------------------------------------------------------------

function onFilterChange(): void {
  page.value = 1
  void loadList()
}

function onPageChange(next: number): void {
  page.value = next
  void loadList()
}

function onSizeChange(next: number): void {
  size.value = next
}

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

function statusTag(status?: PublishStatus | null): 'success' | 'warning' | 'info' | 'primary' | 'danger' {
  return status ? PUBLISH_STATUS_TAG[status] : 'info'
}

function statusText(status?: PublishStatus | null): string {
  return status ? PUBLISH_STATUS_LABEL[status] : '-'
}

/** 毫秒 → 人话 */
function formatCost(ms?: number | null): string {
  if (ms === null || ms === undefined) {
    return '-'
  }
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

/** 推荐值 913 → 91.3% */
function ratingText(v?: number | null): string {
  return v === null || v === undefined ? '-' : `${(v / 10).toFixed(1)}%`
}

onMounted(() => {
  void loadAll()
})
onUnmounted(stopPoll)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" class="intro">
      <template #title>
        从候选清单里挑一本高质量图书，自动生成一篇解读文章，保存到本地并发布到语雀「书籍解读」目录。
        <b>一次一本书</b>：可以点「生成并发布」手动做，也可以设定时间让它自动做。
      </template>
    </el-alert>

    <!-- ============ 未就绪提醒 ============ -->
    <el-alert v-if="job && !ready" type="warning" :closable="false" show-icon>
      <template #title>
        <template v-if="!job.modelConfigured">还没有配置可用的 AI 模型（ai_model 表里需要有一行 enable = true）。</template>
        <template v-if="!job.modelConfigured && !job.yuqueConfigured">　</template>
        <template v-if="!job.yuqueConfigured">还没有配置语雀 token（ai_yuque_token 表）。</template>
        在配置补齐之前，「生成并发布」一定会失败。
      </template>
    </el-alert>

    <!-- ============ 生成配置 ============ -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">生成配置</span>
          <el-tag v-if="genRunning" type="primary" effect="dark" size="small">
            <el-icon class="spin"><Loading /></el-icon>
            生成中
          </el-tag>
          <el-tag v-else-if="initRunning" type="primary" effect="dark" size="small">
            <el-icon class="spin"><Loading /></el-icon>
            初始化中
          </el-tag>
        </div>
      </template>

      <el-form label-width="110px" class="config-form">
        <el-form-item label="cron 表达式">
          <div class="cron-row">
            <el-input
              v-model="cronInput"
              placeholder="0 0 4 * * ?"
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
            例：<code>0 0 4 * * ?</code> = 每天凌晨 4:00。每次触发只生成一本。
          </div>
        </el-form-item>

        <el-form-item label="启用定时">
          <el-switch v-model="enabledInput" :disabled="running" />
          <span class="hint inline">关掉只影响自动生成，「生成并发布」随时可用。</span>
        </el-form-item>

        <el-form-item label="下次触发">
          <span class="next-fire">{{ formatDateTime(job?.nextFireTime) }}</span>
          <span v-if="job && !job.enabled" class="hint inline">（定时未启用）</span>
        </el-form-item>

        <el-form-item label="候选范围">
          <div class="target-list">
            <template v-if="job && job.categoryNames.length">
              <el-tag
                v-for="name in job.categoryNames"
                :key="name"
                type="primary"
                effect="plain"
                class="target-tag"
              >
                {{ name }}
              </el-tag>
            </template>
            <span v-else class="hint">分类白名单为空（在 ai_taxonomy_config 表里维护）</span>
          </div>
          <div class="hint">
            候选 = 上面这些分类的书，且评级属于「神作 / 好评如潮 / 神作潜力 / 脍炙人口 / 值得一读」，且评价人数超过 500。
            白名单只取<b>一级分类</b>（二级分类的书本来就会带上所属一级分类，所以选一级不会漏）。
          </div>
        </el-form-item>

        <el-form-item label="当前模型">
          <template v-if="job && job.modelConfigured">
            <el-tag type="success" effect="plain">{{ job.modelName || '已配置' }}</el-tag>
            <span class="hint inline">{{ dash(job.modelId) }}</span>
          </template>
          <span v-else class="hint">未配置</span>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" :disabled="running" @click="onSave">
            保存配置
          </el-button>
          <el-button :loading="loading" @click="loadAll()">
            <el-icon><Refresh /></el-icon>
            {{ running ? '刷新进度' : '刷新' }}
          </el-button>
          <span v-if="dirty" class="hint inline dirty">配置有改动，别忘了点「保存配置」</span>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ============ 执行 ============ -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">执行</span>
          <span class="hint">{{ dash(job?.lastMessage) }}</span>
        </div>
      </template>

      <div class="actions">
        <el-button
          type="primary"
          plain
          :loading="initing"
          :disabled="running"
          @click="onInit"
        >
          <el-icon><FolderAdd /></el-icon>
          初始化候选
        </el-button>
        <el-button
          type="success"
          :loading="triggering"
          :disabled="running"
          @click="onTrigger"
        >
          <el-icon><VideoPlay /></el-icon>
          生成并发布
        </el-button>
        <el-button
          type="danger"
          plain
          :loading="clearing"
          :disabled="running"
          @click="onClear"
        >
          清空未解读
        </el-button>
      </div>

      <!-- 初始化进度 -->
      <div v-if="initRunning" class="progress-line">
        <el-icon class="spin"><Loading /></el-icon>
        正在初始化候选：已扫描 <b>{{ formatNumber(job?.initScanned) }}</b> 本，
        新增 <b>{{ formatNumber(job?.initInserted) }}</b> 条，
        跳过 <b>{{ formatNumber(job?.initSkipped) }}</b> 条
      </div>
      <!-- 生成进度 -->
      <div v-else-if="genRunning" class="progress-line">
        <el-icon class="spin"><Loading /></el-icon>
        <template v-if="job?.currentBookTitle">正在处理《{{ job.currentBookTitle }}》 · </template>
        {{ dash(job?.currentPhase) }}
        <span class="hint inline">已耗时 {{ formatCost(job?.currentElapsedMs) }}</span>
      </div>
      <div v-else-if="job && job.initScanned !== null && job.initScanned !== undefined" class="progress-line done">
        上次初始化：扫描 {{ formatNumber(job.initScanned) }} 本，
        新增 {{ formatNumber(job.initInserted) }} 条，
        跳过 {{ formatNumber(job.initSkipped) }} 条
      </div>

      <el-descriptions v-if="job" :column="5" size="small" border class="summary">
        <el-descriptions-item label="待解读">
          <span class="num">{{ formatNumber(job.pendingCount) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="生成中">
          <span class="num">{{ formatNumber(job.generatingCount) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="已发布">
          <span class="num highlight">{{ formatNumber(job.publishedCount) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="失败">
          <span class="num danger">{{ formatNumber(job.failedCount) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="清单总数">
          <span class="num">{{ formatNumber(job.totalCount) }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- ============ 候选清单 ============ -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">候选清单</span>
          <el-select
            v-model="statusFilter"
            size="small"
            class="status-filter"
            @change="onFilterChange"
          >
            <el-option
              v-for="o in STATUS_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
          <span class="hint">共 {{ total }} 条</span>
        </div>
      </template>

      <ProTable
        :data="rows"
        :loading="loading"
        :total="total"
        :page="page"
        :size="size"
        row-key="bookId"
        empty-text="清单还是空的。点上面的「初始化候选」把符合条件的图书挑进来。"
        @update:page="onPageChange"
        @update:size="onSizeChange"
      >
        <el-table-column label="书名" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="book-title">{{ row.title ?? dash(row.bookId) }}</div>
            <div class="book-author">{{ dash(row.author) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ dash(row.category) }}</template>
        </el-table-column>

        <el-table-column label="推荐值" width="84" align="right">
          <template #default="{ row }">{{ ratingText(row.newRating) }}</template>
        </el-table-column>

        <el-table-column label="评价人数" width="96" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.newRatingCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="88" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)" effect="dark">
              {{ row.statusLabel ?? statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="语雀" width="76" align="center">
          <template #default="{ row }">
            <el-link
              v-if="row.yuqueUrl"
              type="primary"
              :href="row.yuqueUrl"
              target="_blank"
              :underline="false"
            >
              打开
            </el-link>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="合规替换" width="92" align="center">
          <template #default="{ row }">
            <span v-if="row.sensitiveHitCount" class="num">{{ row.sensitiveHitCount }} 词</span>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="失败原因" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorMsg" class="error-msg">{{ row.errorMsg }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="完成时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.finishedAt) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="110" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              :disabled="running"
              @click="onReset(row)"
            >
              重置为待解读
            </el-button>
          </template>
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
  max-width: 240px;
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
  gap: 6px;
}

.target-tag {
  height: auto;
  padding: 4px 9px;
  line-height: 1.5;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.progress-line {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 12px;
  font-size: 13px;
  color: #409eff;
}

.progress-line.done {
  color: #909399;
}

.summary {
  margin-top: 4px;
}

.status-filter {
  width: 110px;
}

.book-title {
  font-weight: 600;
}

.book-author {
  font-size: 12px;
  color: #909399;
}

.num {
  font-variant-numeric: tabular-nums;
}
.highlight {
  font-weight: 600;
  color: #67c23a;
}
.danger {
  color: #f56c6c;
}

.error-msg {
  color: #f56c6c;
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
