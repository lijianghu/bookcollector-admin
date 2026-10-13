<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  batchDeleteInterpretations,
  getPublishJob,
  initCandidates,
  pageInterpretations,
  resetInterpretation,
} from '@/api/publish'
import { listTaxonomy } from '@/api/taxonomy'
import ProTable from '@/components/ProTable.vue'
import SearchForm from '@/components/SearchForm.vue'
import BookActionMenu from '@/components/BookActionMenu.vue'
import { useBookActions } from '@/composables/useBookActions'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import {
  PUBLISH_STATUS_LABEL,
  PUBLISH_STATUS_TAG,
  type InterpretationVO,
  type PublishStatus,
  type TaxonomyItem,
} from '@/types'

/**
 * 精选图书 —— AI 解读的候选清单（2026-10-12 从「发布配置」拆出来）。
 *
 * <h3>为什么单独一页</h3>
 * 候选清单有几千条（当前 4824），还有 7 个筛选条件 + 分页 + 批量删除。
 * 它和「发布配置」（改 cron、点两个按钮）是**完全不同的使用节奏**：
 * 一个是重列表页，一个是轻控制台。挤在一页里，配置区会被表格淹掉。
 *
 * <p>拆出来之后挂到「图书库 → 精选图书」，因为它本质上是「从图书库里挑出来的
 * 高质量子集」，而不是发布流水线上的一个环节。
 *
 * <h3>数据源与接口完全没变</h3>
 * 仍是 `book_interpretations`，仍用 `GET /api/publish/interpretations` 等三个端点 ——
 * 后端 `publish` 模块**一行没改**，只是换了个页面来调。
 *
 * <h3>2026-10-12：操作列加了「藏书 / 阅读清单」</h3>
 * 需求要求四个列表页（图书库 / 精选图书 / 我的藏书 / 阅读清单）都能做同样五个动作。
 * 这里用 {@link BookActionMenu} 收成一个「更多」下拉（平铺会撑爆表格宽度），
 * 动作实现走公共的 `useBookActions()`。
 */

// ---------------------------------------------------------------------------
// 筛选 + 分页 + 勾选
// ---------------------------------------------------------------------------

const listLoading = ref(false)
const rows = ref<InterpretationVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const selectedRows = ref<InterpretationVO[]>([])
const deleting = ref(false)

const STATUS_OPTIONS: { label: string; value: PublishStatus | '' }[] = [
  { label: '全部', value: '' },
  { label: '待解读', value: 'PENDING' },
  { label: '生成中', value: 'GENERATING' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '失败', value: 'FAILED' },
]

/**
 * 筛选条件，对应后端 `InterpretationQuery`。
 *
 * <p>默认「全部」状态 —— 这一页是「看清单」的，不是「只看待办」的。
 *
 * <p>⚠️ `categoryId` 传的是**一级分类**的 code。二级分类的 code 是 `subCategoryId`
 * （另一个字段），传进来恒不匹配、静默返回 0 条 —— 所以下面的 `categoryOptions` 只列一级。
 */
const query = reactive<{
  status: PublishStatus | ''
  title?: string
  author?: string
  categoryId?: number
  minRating?: number
  maxRating?: number
  minReadingCount?: number
  minPublishTime?: string
  maxPublishTime?: string
}>({
  status: '',
})

/** 字典：只用到分类（榜单在这里没有筛选价值，所以不拉） */
const categories = ref<TaxonomyItem[]>([])

const categoryOptions = computed(() =>
  categories.value
    .filter((c) => !c.parentCode)
    .map((c) => ({ value: Number(c.code), label: `${c.name}（${c.code}）` })),
)

// ---------------------------------------------------------------------------
// 藏书 / 阅读清单（四处统一的五个动作）
// ---------------------------------------------------------------------------

/**
 * 本页列的是**全部候选图书**，与「在不在藏书阁 / 阅读清单」无关，
 * 所以移除藏书后**不需要**把行从列表里去掉（不传回调）。
 */
const {
  busy,
  isOnShelf,
  readingStatusOf,
  loadStatus,
  toggleShelf,
  toggleReadingList,
  setReadingStatus,
} = useBookActions()

// ---------------------------------------------------------------------------
// 加载
// ---------------------------------------------------------------------------

/** 防止「页码越界纠正」反复触发 */
let correcting = false

/**
 * @param silent true = 静默刷新：不显示表格 loading
 */
async function loadList(silent = false): Promise<void> {
  if (!silent) {
    listLoading.value = true
  }
  try {
    const data = await pageInterpretations({
      ...query,
      page: page.value,
      size: size.value,
    })
    rows.value = data.list
    total.value = data.total
    // 两个维度的状态跟着这一页一起刷（失败各自兜底，不影响列表）
    await loadStatus(data.list)

    // 页码越界纠正：在第 3 页删光之后 page=3 会返回空列表，用户以为数据没了。
    // 退回最后一页重来一次（只做一次，避免死循环）。同图书库的处理。
    const lastPage = Math.max(1, data.totalPages || 1)
    if (!correcting && data.list.length === 0 && page.value > lastPage) {
      correcting = true
      page.value = lastPage
      await loadList(true)
      correcting = false
    }
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    if (!silent) {
      listLoading.value = false
    }
  }
}

async function loadDict(): Promise<void> {
  try {
    categories.value = await listTaxonomy('CATEGORY')
  } catch {
    // 字典拉不到不影响列表本身，下拉框空着即可
    categories.value = []
  }
}

// ---------------------------------------------------------------------------
// 批量删除 / 重置
// ---------------------------------------------------------------------------

/**
 * 批量删除勾选的条目。
 *
 * 🔴 **不限状态**：已发布到语雀的也能删。
 * 确认框里必须把「删的是什么」讲清楚 —— 用户很容易以为这会连带删掉语雀上的文章。
 */
async function confirmBatchDelete(): Promise<void> {
  const count = selectedRows.value.length
  if (count === 0) {
    return
  }
  const published = selectedRows.value.filter((r) => r.status === 'PUBLISHED').length
  const titles = selectedRows.value
    .slice(0, 5)
    .map((r) => `《${r.title ?? r.bookId}》`)
    .join('、')
  const more = count > 5 ? ` 等 ${count} 条` : ''
  const extra =
    published > 0
      ? `其中 ${published} 条已经发布到语雀 —— 删除只会去掉清单里的这一行，` +
        `语雀上的文章和「发布记录」里的历史流水都不受影响。`
      : ''
  try {
    await ElMessageBox.confirm(
      `确定把 ${titles}${more} 从候选清单里删除吗？共 ${count} 条。${extra}`,
      '批量删除候选',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  deleting.value = true
  try {
    const result = await batchDeleteInterpretations(selectedRows.value.map((r) => r.bookId))
    // 返回的是**实际**删除数，可能小于请求数（有的 bookId 已经不在了）
    const missed = result.requested - result.deleted
    ElMessage.success(
      missed > 0
        ? `请求删除 ${result.requested} 条，实际删除 ${result.deleted} 条（${missed} 条已不存在）`
        : `已删除 ${result.deleted} 条`,
    )
    selectedRows.value = []
    await loadList()
  } catch {
    // 拦截器已提示
  } finally {
    deleting.value = false
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
    await loadList()
  } catch {
    // 拦截器已提示
  }
}

// ---------------------------------------------------------------------------
// 重置精选（整批重建候选池）
// ---------------------------------------------------------------------------

/**
 * 「重置精选」= 原「发布配置 → 初始化候选」，2026-10-12 搬来本页并改名。
 *
 * <p>它做的事没变：按当前的分类白名单 + 评级档位 + 评价人数下限，从图书库里
 * 重新挑一批书进候选清单（已在清单里的跳过，所以可以重复执行）。
 *
 * <p>🔴 **它是「提交即返回」的后台长任务**：`initCandidates()` 一返回就说明任务已受理，
 * 真正的进度要从 `getPublishJob()` 的 `initRunning / initScanned / initInserted / initSkipped`
 * 读 —— 这几个字段来自后端**内存**，所以刷新页面之后也接着看得到。
 * 本页因此要自己轮询：跑完再刷新列表，否则用户看到的还是旧的清单。
 */

/** 从点按钮到提交返回之间 */
const resetting = ref(false)
/** 后台真的在跑（与按钮的 loading 不是一回事：提交返回后它还可能是 true） */
const resetRunning = ref(false)
/** 进度快照，只在 `resetRunning` 为 true 时有意义 */
const resetProgress = ref<{ scanned: number; inserted: number; skipped: number } | null>(null)

const RESET_POLL_MS = 2000
let resetTimer: number | null = null

function stopResetPoll(): void {
  if (resetTimer !== null) {
    window.clearTimeout(resetTimer)
    resetTimer = null
  }
}

/** 只有后端说还在跑，才继续轮询 */
function scheduleResetPoll(): void {
  stopResetPoll()
  resetTimer = window.setTimeout(() => {
    void pollReset()
  }, RESET_POLL_MS)
}

function applyResetState(data: Awaited<ReturnType<typeof getPublishJob>>): void {
  resetRunning.value = data.initRunning === true
  resetProgress.value = {
    scanned: data.initScanned ?? 0,
    inserted: data.initInserted ?? 0,
    skipped: data.initSkipped ?? 0,
  }
}

/**
 * 轮询进度；后端说停了就刷新列表并收尾。
 *
 * <p>请求失败时**主动停掉轮询** —— 网络断了还每 2 秒打一次接口没有意义，
 * 拦截器已经提示过用户了。
 */
async function pollReset(): Promise<void> {
  try {
    const data = await getPublishJob()
    applyResetState(data)
    if (resetRunning.value) {
      scheduleResetPoll()
      return
    }
    const p = resetProgress.value
    ElMessage.success(
      `重置完成：扫描 ${formatNumber(p?.scanned)} 本，新增 ${formatNumber(p?.inserted)} 条，` +
        `跳过 ${formatNumber(p?.skipped)} 条`,
    )
    // 清单已经变了，从第一页重新拉
    page.value = 1
    await loadList()
  } catch {
    // 拦截器已提示
    resetRunning.value = false
  }
}

/**
 * 进页面时同步一次状态。
 *
 * <p>初始化可能是在**别的时刻**触发的（用户点了重置之后刷新了页面），
 * 不查一次的话按钮会显示成可点，用户会以为任务没在跑。
 */
async function syncResetState(): Promise<void> {
  try {
    const data = await getPublishJob()
    applyResetState(data)
    if (resetRunning.value) {
      scheduleResetPoll()
    }
  } catch {
    // 拦截器已提示
  }
}

async function onResetAll(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将按当前的分类白名单 + 评级档位 + 评价人数下限，从图书库里重新挑出符合条件的图书放进候选清单。' +
        '已经在清单里的会跳过，所以可以放心重复执行。任务在后台跑，可以留在本页看进度。',
      '重置精选',
      { type: 'warning', confirmButtonText: '开始重置', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  resetting.value = true
  try {
    const data = await initCandidates()
    applyResetState(data)
    ElMessage.success('已提交，正在后台执行')
    scheduleResetPoll()
  } catch {
    // 拦截器已提示
  } finally {
    resetting.value = false
  }
}

// ---------------------------------------------------------------------------
// 筛选 / 分页
// ---------------------------------------------------------------------------

function handleSearch(): void {
  page.value = 1
  void loadList()
}

function handleReset(): void {
  query.status = ''
  query.title = undefined
  query.author = undefined
  query.categoryId = undefined
  query.minRating = undefined
  query.maxRating = undefined
  query.minReadingCount = undefined
  query.minPublishTime = undefined
  query.maxPublishTime = undefined
  page.value = 1
  void loadList()
}

function onPageChange(next: number): void {
  page.value = next
  void loadList()
}

function onSizeChange(next: number): void {
  size.value = next
  // ProTable 也会 emit update:page(1)，但事件顺序不保证 —— 这里自己也算一次，
  // 免得出现「page 还是 5、size 变成 100」这种越界请求
  page.value = 1
  void loadList()
}

function handleSelectionChange(list: unknown[]): void {
  selectedRows.value = list as InterpretationVO[]
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

/** 推荐值 913 → 91.3% */
function ratingText(v?: number | null): string {
  return v === null || v === undefined ? '-' : `${(v / 10).toFixed(1)}%`
}

onMounted(() => {
  void loadDict()
  void loadList()
  // 后端可能正在跑重置（用户点完重置又刷新了页面）—— 查一次把状态接上
  void syncResetState()
})

// 离开页面必须停掉轮询，否则定时器会在后台一直打接口
onUnmounted(stopResetPoll)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" class="intro">
      <template #title>
        AI 解读的候选清单：按「分类白名单 + 评级档位 + 评价人数下限」从图书库里挑出来的高质量图书。
        本页负责重置清单（「重置精选」）、看清单、清理清单；生成与发布在「配置 → 发布配置」里执行。
      </template>
    </el-alert>

    <!-- ============ 筛选 ============ -->
    <SearchForm
      :model="query"
      :loading="listLoading"
      :columns="4"
      label-width="100px"
      @search="handleSearch"
      @reset="handleReset"
    >
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部">
          <el-option
            v-for="o in STATUS_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="书名">
        <el-input v-model="query.title" clearable placeholder="模糊搜索，含即可" />
      </el-form-item>

      <el-form-item label="作者">
        <el-input v-model="query.author" clearable placeholder="模糊搜索，含即可" />
      </el-form-item>

      <el-form-item label="平台分类">
        <el-select v-model="query.categoryId" clearable filterable placeholder="全部">
          <el-option
            v-for="opt in categoryOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="推荐值区间">
        <div class="range">
          <el-input-number
            v-model="query.minRating"
            :min="0"
            :max="1000"
            :controls="false"
            placeholder="下限"
          />
          <span class="range-sep">~</span>
          <el-input-number
            v-model="query.maxRating"
            :min="0"
            :max="1000"
            :controls="false"
            placeholder="上限"
          />
        </div>
      </el-form-item>

      <el-form-item label="阅读人数">
        <el-input-number
          v-model="query.minReadingCount"
          :min="0"
          :controls="false"
          placeholder="下限，不限则空"
        />
      </el-form-item>

      <el-form-item label="出版时间从">
        <el-input v-model="query.minPublishTime" clearable placeholder="2015 / 2015-06" />
      </el-form-item>

      <el-form-item label="出版时间到">
        <el-input v-model="query.maxPublishTime" clearable placeholder="2023 / 2023-12" />
      </el-form-item>

      <div class="query-hint">
        筛选条件与「图书库」一致（状态 / 书名 / 作者 / 平台分类 / 推荐值区间 /
        阅读人数下限 / 出版时间区间），条件之间是「与」的关系。书名与作者是<b>模糊搜索</b>。
        勾选后可批量删除，<b>不限状态</b> —— 已发布到语雀的也能删。
      </div>
    </SearchForm>

    <!-- ============ 清单 ============ -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">候选清单</span>
          <span class="hint">共 {{ total }} 条</span>
        </div>
      </template>

      <ProTable
        :data="rows"
        :loading="listLoading"
        :total="total"
        :page="page"
        :size="size"
        :page-sizes="[20, 50, 100]"
        row-key="bookId"
        selectable
        empty-text="清单还是空的（或没有符合筛选条件的条目）。点上面的「重置精选」把符合条件的图书挑进来。"
        @update:page="onPageChange"
        @update:size="onSizeChange"
        @selection-change="handleSelectionChange"
      >
        <template #toolbar>
          <el-button
            type="primary"
            plain
            :loading="resetting"
            :disabled="resetRunning"
            @click="onResetAll"
          >
            <el-icon><Refresh /></el-icon>
            重置精选
          </el-button>
          <el-button
            v-if="selectedRows.length > 0"
            type="danger"
            :loading="deleting"
            @click="confirmBatchDelete"
          >
            <el-icon><Delete /></el-icon>
            批量删除（{{ selectedRows.length }}）
          </el-button>
          <span v-if="resetRunning" class="reset-progress">
            正在重置精选：已扫描 {{ formatNumber(resetProgress?.scanned) }} 本，
            新增 {{ formatNumber(resetProgress?.inserted) }} 条，
            跳过 {{ formatNumber(resetProgress?.skipped) }} 条
          </span>
          <span class="toolbar-info">
            共 {{ formatNumber(total) }} 条 · 本页 {{ rows.length }} 条
          </span>
        </template>

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

        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="onReset(row)">重置</el-button>
            <BookActionMenu
              :book-id="row.bookId"
              :on-shelf="isOnShelf(row.bookId)"
              :reading-status="readingStatusOf(row.bookId)"
              :disabled="busy"
              @toggle-shelf="toggleShelf(row.bookId)"
              @toggle-reading="toggleReadingList(row.bookId)"
              @set-status="(s) => setReadingStatus(row.bookId, s)"
            />
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

.hint {
  font-size: 12px;
  line-height: 1.7;
  color: #909399;
}

/* 区间输入：两个数字框 + 中间的波浪号 */
.range {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}
.range :deep(.el-input-number) {
  flex: 1;
  min-width: 0;
}
.range-sep {
  color: #909399;
  flex-shrink: 0;
}

.query-hint {
  grid-column: 1 / -1;
  margin: -8px 0 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.toolbar-info {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

/* 重置精选的实时进度：夹在操作按钮与右侧统计之间 */
.reset-progress {
  font-size: 12px;
  color: #409eff;
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

.error-msg {
  color: #f56c6c;
}
</style>
