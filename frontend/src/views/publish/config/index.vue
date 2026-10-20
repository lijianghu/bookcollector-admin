<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  clearCandidates,
  getPublishJob,
  triggerPublish,
  updatePublishConfig,
} from '@/api/publish'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import type { PublishJobVO } from '@/types'

/**
 * 发布配置（原「AI → 书籍解读」，2026-10-10 改名；2026-10-12 迁入「配置」分组）。
 *
 * <h3>它做什么</h3>
 * 从候选清单里挑一本高质量图书 → 生成一篇解读文章 → 存成本地 Markdown →
 * 发布到语雀「书籍解读」目录。可以点按钮手动做一本，也可以设个时间让它自动做。
 *
 * <h3>🔴 2026-10-12：候选清单搬走了</h3>
 * 原来页面下半部分是**完整候选清单**（7 个筛选 + 表格 + 批量删除 + 重置），
 * 现在整块搬到「图书库 → 精选图书」。
 *
 * <p>理由：候选清单有 4800+ 条，是个**重列表页**；而本页是**轻控制台**
 * （改 cron、点两个按钮、看进度）。挤在一起时，配置区会被表格淹掉。
 *
 * <p>本页保留：生成配置（cron / 启用 / 下次触发 / 发布范围 / 当前模型）、
 * <b>候选池统计</b>（待解读 / 生成中 / 已发布 / 失败 / 总数）、
 * 清空未解读、生成并发布，以及**跳转精选图书的入口**。
 *
 * <p>🔴 **2026-10-12 稍后：「初始化候选」按钮也搬走了** —— 改名「重置精选」，
 * 放到精选图书页的**列表上方**（在哪看清单，就在哪重置清单）。
 * 本页因此不再有触发入口，但**保留 `initRunning` 的判断**：别处正在初始化时，
 * 本页的按钮同样要禁用、进度行照常显示 —— 用户可能点了重置再切回本页。
 *
 * <h3>🔴 两个长任务都是「提交即返回」</h3>
 * 「生成并发布」在本页触发，「重置精选」（原「初始化候选」）在精选图书页触发。
 * 两者都是点完立刻返回、任务在后台跑，所以页面靠 2 秒轮询跟进：
 * 是否要继续轮询看后端的 `initRunning` / `genRunning` ——
 * 它们来自后端**内存**，所以刷新页面之后也能接着看，不会卡在「执行中」。
 */
const router = useRouter()

// ---------------------------------------------------------------------------
// 任务状态 + 配置
// ---------------------------------------------------------------------------

const loading = ref(false)
const saving = ref(false)
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

/** 跳到「精选图书」（候选清单已搬到那里） */
function goFeatured(): void {
  void router.push('/books/featured')
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
// 清空未解读
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
    ElMessage.success('已清空未解读候选')
  } catch {
    // 拦截器已提示
  } finally {
    clearing.value = false
  }
}

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

/** 毫秒 → 人话 */
function formatCost(ms?: number | null): string {
  if (ms === null || ms === undefined) {
    return '-'
  }
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
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
        从候选清单里挑一本高质量图书，自动生成一篇解读文章，保存到本地并发布到语雀「书籍解读」目录。
        <b>一次一本书</b>：可以点「生成并发布」手动做，也可以设定时间让它自动做。
        候选清单的明细（筛选 / 批量删除 / 重置）在「精选图书」里。
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
            重置中
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

        <el-form-item label="发布范围">
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
            <span v-else class="hint">发布范围为空（在 pick_taxonomy_config 表里维护）</span>
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
          <el-button :loading="loading" @click="load()">
            <el-icon><Refresh /></el-icon>
            {{ running ? '刷新进度' : '刷新model' }}
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
        <!-- 候选清单明细（含「重置精选」按钮）已搬到「精选图书」，这里给个直达入口 -->
        <el-button @click="goFeatured">
          <el-icon><Medal /></el-icon>
          查看精选图书
        </el-button>
      </div>

      <!-- 重置精选的进度：本页已无触发入口，但别处（精选图书页）触发时这里照常显示 -->
      <div v-if="initRunning" class="progress-line">
        <el-icon class="spin"><Loading /></el-icon>
        正在重置精选：已扫描 <b>{{ formatNumber(job?.initScanned) }}</b> 本，
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

.summary-hint {
  margin-top: 10px;
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
