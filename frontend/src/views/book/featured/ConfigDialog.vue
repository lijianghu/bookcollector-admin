<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { collectCandidates, getPublishJob, previewCandidates } from '@/api/publish'
import { listTaxonomy } from '@/api/taxonomy'
import ProTable from '@/components/ProTable.vue'
import { dash, formatNumber } from '@/utils/format'
import type { CandidateBookVO, CandidateBuildRequest, TaxonomyItem } from '@/types'

/**
 * 精选配置（2026-10-12 新增）—— 从「重置精选」演化来的弹窗。
 *
 * <h3>它解决什么问题</h3>
 * 原来的「重置精选」只能按 `ai_taxonomy_config` 的分类白名单 + 评级档位 + 评价人数下限
 * 全量重建候选池，条件写死在配置里。现在改成：**用户当场挑条件 → 先预览 → 再决定精选哪些**。
 *
 * <h3>数据源是图书库，不是候选清单</h3>
 * 预览查的是 `books`（`POST /api/publish/candidates/preview`），
 * 精选是把命中的书**快照进** `book_interpretations`（`POST /api/publish/candidates/collect`）。
 * 已有的 bookId 一律跳过，所以可以反复执行。
 *
 * <h3>三套筛选条件互不叠加</h3>
 * 「评论标题」「推荐值大于」「书名/作者」各带一个「精选预览」按钮，
 * 点哪个就**只按哪一套**查（用户 2026-10-12 明确「两套独立」）。
 * 三套一起叠上去的话，用户勾着 5 个标签又填了推荐值时会得到一堆莫名其妙的结果。
 *
 * <h3>🔴 精选是「提交即返回」的长任务</h3>
 * 「全部精选」可能命中几千本、要几十秒。`collectCandidates()` 一返回只说明任务已受理，
 * 真正的进度要从 `getPublishJob()` 的 `initRunning / initScanned / initInserted / initSkipped`
 * 读 —— 这几个字段来自后端**内存**（不落库），所以刷新页面之后也还看得到。
 */

const props = defineProps<{ modelValue: boolean }>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** 精选已提交 / 已完成 / 弹窗关闭 —— 父页面据此局部刷新候选清单 */
  (e: 'collected'): void
}>()

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------

/**
 * 平台分类的可选项（**只有一级**），顺序即展示顺序。
 *
 * <p>⚠️ 写死的是<b>名字</b>而不是 code —— code 从 `taxonomy_config` 字典里现查
 * （`books.categories[].categoryId` 是 int，而字典的 `code` 是 String，转换在前端做）。
 * 字典里查不到的名字会<b>禁用并提示</b>，不静默消失。
 *
 * <p>这是用户 2026-10-12 圈定的候选分类；2026-10-16 补回<b>哲学宗教</b>（后端
 * `ai_taxonomy_config` 启用的 10 个分类里就含它，前端缺了会漏筛）。仍刻意排除
 * 童书 / 教育学习 / 生活百科 / 期刊杂志 / 原版书 / 男生小说 / 女生小说。
 */
const CANDIDATE_CATEGORY_NAMES = [
  '文学',
  '精品小说',
  '历史',
  '艺术',
  '人物传记',
  '计算机',
  '心理',
  '社会文化',
  '哲学宗教',
  '个人成长',
  '经济理财',
  '政治军事',
  '科学技术',
  '医学健康',
  '漫画',
]

/**
 * 评论标题（`books.newRatingTitle`）的可选项。默认全勾，也允许全勾。
 *
 * ⚠️ 这 5 个与后端 `application.yml` 的 `bookcollector.publish.rating-titles` 是**同一批**
 * （`PublishProperties.ratingTitles`）。两边写死两处是有意的（用户 2026-10-12 选定不加接口），
 * 但**改了后端配置就要同步这里** —— 否则弹窗里勾不到新档位，
 * 而且**不会报任何错**，只是筛选结果悄悄变少。
 */
const CANDIDATE_RATING_TITLES = ['神作', '好评如潮', '神作潜力', '脍炙人口', '值得一读']

/** 「精选预览」的三个入口，对应三套互不叠加的条件 */
type PreviewSource = 'rating' | 'score' | 'keyword'

// ---------------------------------------------------------------------------
// 筛选条件
// ---------------------------------------------------------------------------

const categoryIds = ref<number[]>([])
const ratingTitles = ref<string[]>([...CANDIDATE_RATING_TITLES])
const minRating = ref<number | undefined>(undefined)
const title = ref('')
const author = ref('')
/** 隐藏已精选。默认开 —— 精选的目的就是补充新书，把已有的再列一遍没有意义 */
const excludeCollected = ref(true)

/** 字典：只用到分类 */
const categories = ref<TaxonomyItem[]>([])

/**
 * 分类选项：名字 → code 的映射来自字典，顺序来自 {@link CANDIDATE_CATEGORY_NAMES}。
 *
 * <p>`value` 为 `undefined` 表示字典里没有这个名字（下面的 `missingCategoryNames` 会提示）。
 */
const categoryOptions = computed(() => {
  const byName = new Map<string, number>()
  for (const c of categories.value) {
    // 只要一级：二级的 code 是 subCategoryId，当 categoryId 传会恒不匹配、静默返回 0 条
    if (c.parentCode) {
      continue
    }
    byName.set(c.name, Number(c.code))
  }
  return CANDIDATE_CATEGORY_NAMES.map((name) => ({ name, value: byName.get(name) }))
})

/** 字典里查不到的分类名。理论上不该有；出现说明字典与这份写死的清单脱节了 */
const missingCategoryNames = computed(() =>
  categoryOptions.value.filter((o) => o.value === undefined).map((o) => o.name),
)

async function loadDict(): Promise<void> {
  try {
    categories.value = await listTaxonomy('CATEGORY')
  } catch {
    // 字典拉不到不影响弹窗打开，但分类会全空 —— 用户点预览时会看到「请至少选择一个平台分类」
    categories.value = []
  }
}

/** 分类默认全选（只选字典里查得到的） */
function selectAllCategories(): void {
  categoryIds.value = categoryOptions.value
    .map((o) => o.value)
    .filter((v): v is number => v !== undefined)
}

// ---------------------------------------------------------------------------
// 预览
// ---------------------------------------------------------------------------

const previewLoading = ref(false)
/** 当前正在预览的入口（用来只让被点的那颗按钮转圈） */
const previewSource = ref<PreviewSource | null>(null)
/** 是否已经预览过（决定结果区显示「请先预览」还是表格） */
const previewed = ref(false)
const previewRows = ref<CandidateBookVO[]>([])
const previewTotal = ref(0)
const previewPage = ref(1)
const previewSize = ref(20)
const selectedRows = ref<CandidateBookVO[]>([])

/**
 * 上次预览用的请求体。
 *
 * <p>精选时在它基础上加 `all` / `bookIds` —— 这样「预览到什么，精选的就是什么」
 * 不是靠约定，而是靠<b>同一份请求体</b>保证的。
 */
const lastRequest = ref<CandidateBuildRequest | null>(null)

/** 按入口组装请求体。三套条件互不叠加，所以每次只带一套 */
function buildRequest(source: PreviewSource): CandidateBuildRequest {
  const req: CandidateBuildRequest = {
    categoryIds: [...categoryIds.value],
    excludeCollected: excludeCollected.value,
  }
  if (source === 'rating') {
    req.ratingTitles = [...ratingTitles.value]
  } else if (source === 'score') {
    req.minRating = minRating.value
  } else {
    req.title = title.value.trim() || undefined
    req.author = author.value.trim() || undefined
  }
  return req
}

/**
 * 前端先校验一遍（后端也有兜底校验）。
 *
 * <p>在这里拦一道是为了给出<b>指哪打哪</b>的提示：用户点了「推荐值」后面的按钮，
 * 却收到「请至少选择一个平台分类」，会一脸茫然。
 */
function validate(source: PreviewSource): boolean {
  if (categoryIds.value.length === 0) {
    ElMessage.warning('请至少选择一个平台分类')
    return false
  }
  if (source === 'rating' && ratingTitles.value.length === 0) {
    ElMessage.warning('请至少勾选一个评论标题')
    return false
  }
  if (source === 'score' && minRating.value === undefined) {
    ElMessage.warning('请填写推荐值下限')
    return false
  }
  if (source === 'keyword' && !title.value.trim() && !author.value.trim()) {
    ElMessage.warning('请填写书名或作者')
    return false
  }
  return true
}

async function runPreview(source: PreviewSource): Promise<void> {
  if (previewLoading.value) {
    return
  }
  if (collectRunning.value) {
    ElMessage.warning('精选任务正在执行中，请等它跑完再预览')
    return
  }
  if (!validate(source)) {
    return
  }
  lastRequest.value = buildRequest(source)
  previewSource.value = source
  previewed.value = true
  previewPage.value = 1
  selectedRows.value = []
  await loadPreview()
}

async function loadPreview(): Promise<void> {
  if (!lastRequest.value) {
    return
  }
  previewLoading.value = true
  try {
    const data = await previewCandidates(lastRequest.value, {
      page: previewPage.value,
      size: previewSize.value,
    })
    previewRows.value = data.list
    previewTotal.value = data.total
    // 翻页 / 重新预览后，上一页的勾选必须作废 —— 否则「批量精选（3）」会精选
    // 三本当前页看不见的书（el-table 的 selection 也会重置，但不能指望它的时机）
    selectedRows.value = []
  } catch {
    // 拦截器已提示（条件不完整时后端返回 code=400）
    previewRows.value = []
    previewTotal.value = 0
  } finally {
    previewLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 精选（批量 / 全部）—— 提交即返回的长任务
// ---------------------------------------------------------------------------

/** 从点按钮到提交返回之间 */
const collecting = ref(false)
/** 后台真的在跑（提交返回后它还可能是 true） */
const collectRunning = ref(false)
/** 进度快照，只在 `collectRunning` 为 true 时有意义 */
const collectProgress = ref<{ scanned: number; inserted: number; skipped: number } | null>(null)

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
  pollTimer = window.setTimeout(() => {
    void pollCollect()
  }, POLL_MS)
}

function applyCollectState(data: Awaited<ReturnType<typeof getPublishJob>>): void {
  collectRunning.value = data.initRunning === true
  collectProgress.value = {
    scanned: data.initScanned ?? 0,
    inserted: data.initInserted ?? 0,
    skipped: data.initSkipped ?? 0,
  }
}

async function pollCollect(): Promise<void> {
  try {
    const data = await getPublishJob()
    applyCollectState(data)
    if (collectRunning.value) {
      schedulePoll()
      return
    }
    const p = collectProgress.value
    ElMessage.success(
      `精选完成：扫描 ${formatNumber(p?.scanned)} 本，新增 ${formatNumber(p?.inserted)} 条，` +
        `跳过 ${formatNumber(p?.skipped)} 条`,
    )
    // 候选清单已经变了，让父页面局部刷新
    emit('collected')
    // 「隐藏已精选」开着时，刚精选进去的书应该从预览列表里消失 —— 重拉当前页
    if (previewed.value) {
      await loadPreview()
    }
  } catch {
    // 拦截器已提示。网络断了还每 2 秒打一次接口没有意义
    collectRunning.value = false
  }
}

/**
 * 进弹窗时同步一次状态。
 *
 * <p>精选可能是**上一次**打开的弹窗触发的（用户点完就关了），
 * 不查一次的话进度会显示成没在跑，用户会以为任务丢了。
 */
async function syncCollectState(): Promise<void> {
  try {
    const data = await getPublishJob()
    applyCollectState(data)
    if (collectRunning.value) {
      schedulePoll()
    }
  } catch {
    // 拦截器已提示
  }
}

async function onCollect(all: boolean): Promise<void> {
  if (!lastRequest.value) {
    ElMessage.warning('请先点「精选预览」确定范围')
    return
  }
  const req: CandidateBuildRequest = { ...lastRequest.value, all }
  if (all) {
    try {
      await ElMessageBox.confirm(
        `将把当前条件下的全部 ${formatNumber(previewTotal.value)} 本书写进候选清单` +
          '（已经在清单里的会跳过）。任务在后台执行，可以留在本页看进度。',
        '全部精选',
        { type: 'warning', confirmButtonText: '开始精选', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  } else {
    const ids = selectedRows.value.map((r) => r.bookId)
    if (ids.length === 0) {
      ElMessage.warning('请先勾选要精选的图书')
      return
    }
    req.bookIds = ids
  }
  collecting.value = true
  try {
    const data = await collectCandidates(req)
    applyCollectState(data)
    ElMessage.success('已提交，正在后台执行')
    schedulePoll()
  } catch {
    // 拦截器已提示
  } finally {
    collecting.value = false
  }
}

// ---------------------------------------------------------------------------
// 打开 / 关闭
// ---------------------------------------------------------------------------

function resetPreview(): void {
  previewed.value = false
  previewSource.value = null
  previewRows.value = []
  previewTotal.value = 0
  previewPage.value = 1
  selectedRows.value = []
  lastRequest.value = null
}

function onVisibleChange(v: boolean): void {
  emit('update:modelValue', v)
  if (!v) {
    stopPoll()
    // 关闭时通知父页面：精选可能刚改动了候选清单（也可能还在跑，父页面自己判断）
    emit('collected')
  }
}

watch(
  () => props.modelValue,
  async (v) => {
    if (!v) {
      return
    }
    // 每次打开都回到默认态：分类全选、标签全勾、其余清空、结果区空着
    if (categories.value.length === 0) {
      await loadDict()
    }
    ratingTitles.value = [...CANDIDATE_RATING_TITLES]
    minRating.value = undefined
    title.value = ''
    author.value = ''
    excludeCollected.value = true
    selectAllCategories()
    resetPreview()
    void syncCollectState()
  },
  { immediate: true },
)

// 关闭弹窗（或组件被销毁）都要停掉轮询，否则定时器会在后台一直打接口
onUnmounted(stopPoll)

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

/** 推荐值 913 → 91.3% */
function ratingText(v?: number | null): string {
  return v === null || v === undefined ? '-' : `${(v / 10).toFixed(1)}%`
}

function onPreviewPageChange(next: number): void {
  previewPage.value = next
  void loadPreview()
}

function onPreviewSizeChange(next: number): void {
  previewSize.value = next
  // ProTable 也会 emit update:page(1)，但事件顺序不保证 —— 这里自己也算一次
  previewPage.value = 1
  void loadPreview()
}

function onSelectionChange(list: unknown[]): void {
  selectedRows.value = list as CandidateBookVO[]
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="精选配置"
    width="1180px"
    top="6vh"
    :close-on-click-modal="false"
    class="config-dialog"
    @update:model-value="onVisibleChange"
  >
    <!-- ============ 筛选区（平铺） ============ -->
    <div class="filters">
      <!-- 1. 平台分类（一级，必选，默认全选） -->
      <div class="filter-row">
        <div class="filter-label">
          平台分类
          <span class="required">*</span>
        </div>
        <div class="filter-body">
          <el-checkbox-group v-model="categoryIds">
            <el-checkbox
              v-for="opt in categoryOptions"
              :key="opt.name"
              :value="opt.value"
              :disabled="opt.value === undefined"
            >
              {{ opt.name }}
            </el-checkbox>
          </el-checkbox-group>
          <div v-if="missingCategoryNames.length" class="warn">
            字典里找不到这些分类，已禁用：{{ missingCategoryNames.join('、') }}
          </div>
        </div>
      </div>

      <!-- 2. 评论标题（默认全勾） -->
      <div class="filter-row">
        <div class="filter-label">评论标题</div>
        <div class="filter-body">
          <el-checkbox-group v-model="ratingTitles">
            <el-checkbox v-for="t in CANDIDATE_RATING_TITLES" :key="t" :value="t">
              {{ t }}
            </el-checkbox>
          </el-checkbox-group>
          <el-button
            v-if="ratingTitles.length > 0"
            type="primary"
            :loading="previewLoading && previewSource === 'rating'"
            @click="runPreview('rating')"
          >
            精选预览
          </el-button>
        </div>
      </div>

      <!-- 3. 推荐值下限（百分制） -->
      <div class="filter-row">
        <div class="filter-label">推荐值大于</div>
        <div class="filter-body">
          <el-input-number
            v-model="minRating"
            :min="0"
            :max="100"
            :controls="false"
            placeholder="0 ~ 100"
            class="score-input"
          />
          <span class="unit">%</span>
          <el-button
            v-if="minRating !== undefined && minRating !== null"
            type="primary"
            :loading="previewLoading && previewSource === 'score'"
            @click="runPreview('score')"
          >
            精选预览
          </el-button>
        </div>
      </div>

      <!-- 4. 书名 / 作者（模糊搜索） -->
      <div class="filter-row">
        <div class="filter-label">书名 / 作者</div>
        <div class="filter-body">
          <el-input v-model="title" clearable placeholder="书名，包含即可" class="kw-input" />
          <el-input v-model="author" clearable placeholder="作者，包含即可" class="kw-input" />
          <el-button
            type="primary"
            :disabled="!title.trim() && !author.trim()"
            :loading="previewLoading && previewSource === 'keyword'"
            @click="runPreview('keyword')"
          >
            精选预览
          </el-button>
        </div>
      </div>

      <!-- 5. 隐藏已精选 -->
      <div class="filter-row">
        <div class="filter-label"></div>
        <div class="filter-body">
          <el-checkbox v-model="excludeCollected">
            隐藏已精选（已经在候选清单里的不再出现）
          </el-checkbox>
        </div>
      </div>
    </div>

    <!-- ============ 预览结果 ============ -->
    <div class="preview">
      <div v-if="!previewed" class="preview-empty">
        调好上面的条件后，点任意一处「精选预览」查看命中的图书。
      </div>

      <ProTable
        v-else
        :data="previewRows"
        :loading="previewLoading"
        :total="previewTotal"
        :page="previewPage"
        :size="previewSize"
        :page-sizes="[20, 50, 100]"
        row-key="bookId"
        selectable
        max-height="420px"
        empty-text="没有符合条件、且未被精选过的图书。可以放宽条件或取消「隐藏已精选」再试。"
        @update:page="onPreviewPageChange"
        @update:size="onPreviewSizeChange"
        @selection-change="onSelectionChange"
      >
        <template #toolbar>
          <el-button
            type="primary"
            :loading="collecting"
            :disabled="collectRunning"
            @click="onCollect(true)"
          >
            全部精选（{{ formatNumber(previewTotal) }}）
          </el-button>
          <el-button
            type="success"
            :loading="collecting"
            :disabled="collectRunning || selectedRows.length === 0"
            @click="onCollect(false)"
          >
            批量精选（{{ selectedRows.length }}）
          </el-button>
          <span v-if="collectRunning" class="collect-progress">
            正在精选：已扫描 {{ formatNumber(collectProgress?.scanned) }} 本，
            新增 {{ formatNumber(collectProgress?.inserted) }} 条，
            跳过 {{ formatNumber(collectProgress?.skipped) }} 条
          </span>
          <span v-else class="toolbar-info">
            共 {{ formatNumber(previewTotal) }} 条 · 本页 {{ previewRows.length }} 条
          </span>
        </template>

        <el-table-column label="书名" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="book-title">{{ row.title ?? dash(row.bookId) }}</div>
            <div class="book-author">{{ dash(row.author) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ dash(row.category) }}</template>
        </el-table-column>

        <el-table-column label="评价标签" width="100" align="center">
          <template #default="{ row }">{{ dash(row.newRatingTitle) }}</template>
        </el-table-column>

        <el-table-column label="推荐值" width="88" align="right">
          <template #default="{ row }">{{ ratingText(row.newRating) }}</template>
        </el-table-column>

        <el-table-column label="评价人数" width="100" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.newRatingCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="阅读人数" width="100" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.readingCount) }}</span>
          </template>
        </el-table-column>
      </ProTable>
    </div>
  </el-dialog>
</template>

<style scoped>
.filters {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
}

.filter-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.filter-label {
  flex: 0 0 84px;
  padding-top: 4px;
  font-size: 13px;
  font-weight: 600;
  text-align: right;
  color: #606266;
}

.required {
  color: #f56c6c;
}

.filter-body {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

/* 复选框成组时不要每项之间再留大间距（默认就有 margin，这里只收紧右边距） */
.filter-body :deep(.el-checkbox) {
  margin-right: 14px;
}

.score-input {
  width: 130px;
}

.unit {
  color: #909399;
}

.kw-input {
  width: 220px;
}

.warn {
  flex-basis: 100%;
  font-size: 12px;
  line-height: 1.6;
  color: #e6a23c;
}

.preview {
  padding-top: 12px;
}

.preview-empty {
  padding: 40px 0;
  font-size: 13px;
  text-align: center;
  color: #a8abb2;
}

.collect-progress {
  font-size: 12px;
  color: #409eff;
}

.toolbar-info {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
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
</style>
