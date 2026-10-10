<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getRequestSummary, pageRequests } from '@/api/audit'
import { listTaxonomy } from '@/api/taxonomy'
import ProTable from '@/components/ProTable.vue'
import SearchForm from '@/components/SearchForm.vue'
import { useColumnPrefs, type ColumnPref } from '@/composables/useColumnPrefs'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import type { ApiRequest, RequestSummary } from '@/types'

/**
 * 请求审计（S6.6）。
 *
 * <h3>这一页解决什么问题</h3>
 * 「为什么这批数据不对？」是采集类系统最难查的问题。这一层记录**每一次对
 * 微信读书接口的 HTTP 请求**（`ApiRequestRepository` 在采集过程中写入），
 * 所以能看到每一页的 `statusCode` / `responseTimeMs` / `resultCount` / `hasMore` ——
 * 一眼就能分辨「接口返了 0 条」和「接口返了 20 条但我们解析错了」。
 *
 * <h3>为什么表格上方要有一条摘要</h3>
 * 表格回答的是「某一条请求怎么样」，但用户真正想知道的是「这批请求整体健不健康」。
 * 逐页翻两百条记录去数失败次数，是把原始日志当报表用。所以顶部固定一条
 * **与表格同一套筛选条件、但不分页**的聚合：请求次数 / 失败 / 失败率 / 平均 / P95 / 最慢。
 *
 * ⚠️ 摘要与表格**必须是同一批记录** —— 「列表说 259 条、摘要说 261 次」这种对不上的
 * 数字会直接毁掉这一页的可信度。后端为此把筛选条件收敛成一个共用实现。
 *
 * <h3>为什么 P95 比平均值更值得看</h3>
 * 上游变慢时，平均值往往只是从 400ms 爬到 600ms，P95 会从 800ms 跳到 5s。
 * 「偶尔卡一下」才是采集变慢的真实形态，平均值会把它抹平。
 *
 * <h3>⚠️ 它不能当账本用</h3>
 * 写入侧的约束是「只插不改、失败不抛异常」—— 审计写失败绝不能影响采集。
 * 所以数据可能有缺口（某次请求没记上）。查询侧相反，条件写错就该报错。
 *
 * <h3>⚠️ 只读</h3>
 * 后端不提供删除接口，前端也刻意不做「清空日志」按钮 ——
 * 一个能被随手清空的审计日志等于没有审计。
 *
 * <h3>失败为什么要整行高亮</h3>
 * 排查时用户是「扫一眼找红色」，不是逐行读 `statusCode`。
 * 所以失败行（`statusCode >= 400` 或有 `errorMsg`）整行染红底，
 * 并且把「只看失败」做成筛选栏里的一个开关。
 */

// ---------------------------------------------------------------------------
// 列配置
// ---------------------------------------------------------------------------

const COLUMNS: ColumnPref[] = [
  { key: 'createdAt', label: '请求时间', fixed: true },
  { key: 'target', label: '采集目标', fixed: true },
  { key: 'pageIndex', label: '第几页' },
  { key: 'statusCode', label: '状态码' },
  { key: 'responseTimeMs', label: '耗时(ms)' },
  { key: 'resultCount', label: '本页条数' },
  { key: 'totalCount', label: '目标总数' },
  { key: 'hasMore', label: '还有下一页' },
  // 请求 URL 是排查时的第一现场，错误信息只在出问题时才有值 —— 两列都默认显示。
  // 顺序有讲究：url 在前、errorMsg 在后。窄屏下 el-table 会把**最后一列**挤出视口，
  // 排在最后的错误信息就是那个被牺牲的，这样它挡不住 URL。
  { key: 'url', label: '请求 URL' },
  { key: 'errorMsg', label: '错误信息' },
]

const cols = useColumnPrefs('requests', COLUMNS)
const columnPanelVisible = ref(false)

// ---------------------------------------------------------------------------
// 查询
// ---------------------------------------------------------------------------

const loading = ref(false)
const rows = ref<ApiRequest[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

/** 4 个查询条件，与后端 `ApiRequestQuery` 一一对应 */
const query = reactive<{
  runId?: string
  targetType: '' | 'CATEGORY' | 'RANKING'
  targetId?: string
  onlyFailed?: boolean
}>({
  targetType: '',
})

const onlyFailed = computed({
  get: () => query.onlyFailed === true,
  set: (value: boolean) => {
    query.onlyFailed = value ? true : undefined
  },
})

let correcting = false

/**
 * 摘要与列表**同时**加载，但摘要自己吞掉异常。
 *
 * 这样即使聚合挂了（比如集合为空时某个字段缺失），列表照样能用 ——
 * 宁可少一行统计，也不要整页报错。
 */
async function loadSummary(): Promise<void> {
  try {
    summary.value = await getRequestSummary({
      runId: query.runId,
      targetType: query.targetType,
      targetId: query.targetId,
      onlyFailed: query.onlyFailed,
    })
  } catch {
    summary.value = null
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    // loadSummary 从不 reject，所以这里不用 allSettled
    const [result] = await Promise.all([
      pageRequests({
        runId: query.runId,
        targetType: query.targetType,
        targetId: query.targetId,
        onlyFailed: query.onlyFailed,
        page: page.value,
        size: size.value,
      }),
      loadSummary(),
    ])
    rows.value = result.list
    total.value = result.total

    // 页码越界纠正（和第 S6.2 同样的处理）：换筛选条件后页码可能超出范围
    const lastPage = Math.max(1, result.totalPages || 1)
    if (!correcting && result.list.length === 0 && page.value > lastPage) {
      correcting = true
      page.value = lastPage
      await load()
      correcting = false
    }
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

let reloadScheduled = false
function scheduleReload(): void {
  if (reloadScheduled) {
    return
  }
  reloadScheduled = true
  void Promise.resolve().then(() => {
    reloadScheduled = false
    void load()
  })
}

function handleSearch(): void {
  page.value = 1
  scheduleReload()
}

function handleReset(): void {
  query.runId = undefined
  query.targetType = ''
  query.targetId = undefined
  query.onlyFailed = undefined
  page.value = 1
  scheduleReload()
}

function handlePageChange(next: number): void {
  page.value = next
  scheduleReload()
}

function handleSizeChange(next: number): void {
  size.value = next
  page.value = 1
  scheduleReload()
}

/** 刷新：字典一起重拉 —— 名称可能刚在「分类配置」页改过 */
function refreshAll(): void {
  void loadDict()
  void load()
}

// ---------------------------------------------------------------------------
// 展示
// ---------------------------------------------------------------------------

/** 失败行：HTTP 状态码 >= 400，或者有 errorMsg（超时、连接失败这类没有状态码） */
function isFailed(row: ApiRequest): boolean {
  if (typeof row.statusCode === 'number' && row.statusCode >= 400) {
    return true
  }
  return Boolean(row.errorMsg)
}

/** 失败行整行染色 —— 排查时是「扫一眼找红色」，不是逐行读状态码 */
function rowClassName({ row }: { row: ApiRequest }): string {
  return isFailed(row) ? 'row-failed' : ''
}

function statusTagType(row: ApiRequest): 'success' | 'danger' | 'info' {
  if (isFailed(row)) {
    return 'danger'
  }
  return typeof row.statusCode === 'number' ? 'success' : 'info'
}

function statusText(row: ApiRequest): string {
  if (typeof row.statusCode === 'number') {
    return String(row.statusCode)
  }
  return row.errorMsg ? '无响应' : '-'
}

/** 耗时染色：超过 3 秒标橙，超过 10 秒标红 */
function timeClass(ms?: number | null): string {
  if (typeof ms !== 'number') {
    return ''
  }
  if (ms >= 10000) {
    return 'time-slow'
  }
  if (ms >= 3000) {
    return 'time-warn'
  }
  return ''
}

// ---------------------------------------------------------------------------
// 字典（把目标编号翻成名称）
// ---------------------------------------------------------------------------

/**
 * `"CATEGORY|200003"` → `"历史-历史读物"`。
 *
 * 键的拼法（类型段大写 + `|` + code）与后端 `StatsServiceImpl.taxonomyNameMap()`
 * 一致 —— 两处对得上，排查时才不用在脑子里做一次转换。
 */
const nameByKey = ref<Map<string, string>>(new Map())

/** 字典是静态数据，进页面拉一次；在「分类配置」页改过名之后点「刷新」会重拉 */
async function loadDict(): Promise<void> {
  try {
    const [categories, rankings] = await Promise.all([
      listTaxonomy('CATEGORY'),
      listTaxonomy('RANKING'),
    ])
    const map = new Map<string, string>()
    for (const item of categories) {
      map.set(`${item.type}|${item.code}`, item.name)
    }
    for (const item of rankings) {
      map.set(`${item.type}|${item.code}`, item.name)
    }
    nameByKey.value = map
  } catch {
    // 字典拉不到不影响请求记录本身 —— 目标列退回显示编号，不要整页报错
    nameByKey.value = new Map()
  }
}

/**
 * 目标名称。字典里查不到就退回「分类」/「榜单」，后面照样跟着编号 ——
 * 不编造名称，也不把字典里没有的目标藏起来。
 */
function targetName(row: ApiRequest): string {
  const key = row.targetType && row.targetId ? `${row.targetType}|${row.targetId}` : ''
  const name = key ? nameByKey.value.get(key) : undefined
  if (name) {
    return name
  }
  return row.targetType === 'RANKING' ? '榜单' : '分类'
}

// ---------------------------------------------------------------------------
// 摘要
// ---------------------------------------------------------------------------

const summary = ref<RequestSummary | null>(null)

/** 失败率（%）。总数为 0 时返回 null —— 0/0 不是 0%，是「没有数据」 */
const failRate = computed<number | null>(() => {
  const s = summary.value
  if (!s || s.total <= 0) {
    return null
  }
  return (s.failed / s.total) * 100
})

const failRateText = computed<string>(() => {
  const rate = failRate.value
  return rate === null ? '-' : `${rate.toFixed(1)}%`
})

/** 耗时展示。null 显示 "-" 而不是 "0 ms" —— 0ms 是「很快」，不是「没有数据」 */
function msText(ms?: number | null): string {
  return typeof ms === 'number' ? `${formatNumber(ms)} ms` : '-'
}

onMounted(() => {
  void loadDict()
  void load()
})
</script>

<template>
  <div class="page">
    <SearchForm
      :model="query"
      :loading="loading"
      :columns="4"
      label-width="92px"
      @search="handleSearch"
      @reset="handleReset"
    >
      <el-form-item label="运行 ID">
        <el-input v-model="query.runId" clearable placeholder="某次运行的 runId" />
      </el-form-item>

      <el-form-item label="目标类型">
        <el-select v-model="query.targetType" clearable placeholder="全部">
          <el-option label="分类" value="CATEGORY" />
          <el-option label="榜单" value="RANKING" />
        </el-select>
      </el-form-item>

      <el-form-item label="目标 ID">
        <el-input v-model="query.targetId" clearable placeholder="如 300000 / rising" />
      </el-form-item>

      <el-form-item label="只看失败">
        <el-switch v-model="onlyFailed" />
      </el-form-item>

      <div class="query-hint">
        这一层记录每一次对微信读书接口的 HTTP 请求，是排查「为什么这批数据不对」的唯一抓手。
        注意：审计写入是「只插不改、失败不抛异常」，所以数据可能有缺口，不能当账本用。
        这一页只读，不提供清空 —— 能被随手清空的审计日志等于没有审计。
      </div>
    </SearchForm>

    <el-card shadow="never" class="table-card">
      <!-- 健康度摘要：与下方表格同一套筛选条件，但不分页 -->
      <div v-if="summary" class="stat-strip">
        <div class="stat">
          <span class="stat-label">请求次数</span>
          <span class="stat-value">{{ formatNumber(summary.total) }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">失败</span>
          <span class="stat-value" :class="{ 'stat-bad': summary.failed > 0 }">
            {{ formatNumber(summary.failed) }}
          </span>
        </div>
        <div class="stat">
          <span class="stat-label">失败率</span>
          <span class="stat-value" :class="{ 'stat-bad': (failRate ?? 0) > 0 }">
            {{ failRateText }}
          </span>
        </div>
        <div class="stat">
          <span class="stat-label">平均耗时</span>
          <span class="stat-value">{{ msText(summary.avgMs) }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">P95 耗时</span>
          <span class="stat-value" :class="timeClass(summary.p95Ms)">
            {{ msText(summary.p95Ms) }}
          </span>
        </div>
        <div class="stat">
          <span class="stat-label">最慢</span>
          <span class="stat-value">{{ msText(summary.maxMs) }}</span>
        </div>
        <span class="stat-note">以上为当前筛选条件下的全部请求（不受分页影响）</span>
      </div>

      <!-- ⚠️ max-height 里的 540 = 原来的 470 + 新增摘要条的约 70px。
           摘要条一旦换行变高，这个数要跟着调，否则底部分页条会被挤出视口 -->
      <ProTable
        :data="rows"
        :loading="loading"
        :total="total"
        :page="page"
        :size="size"
        :page-sizes="[20, 50, 100]"
        row-key="id"
        :row-class-name="rowClassName"
        max-height="calc(100vh - 540px)"
        empty-text="没有符合条件的请求记录"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #toolbar>
          <el-button type="primary" :loading="loading" @click="refreshAll">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>

          <el-popover v-model:visible="columnPanelVisible" placement="bottom-start" :width="230" trigger="click">
            <template #reference>
              <el-button>
                <el-icon><Setting /></el-icon>
                列配置
              </el-button>
            </template>
            <div class="col-panel">
              <el-checkbox-group v-model="cols.visible.value">
                <el-checkbox
                  v-for="col in cols.columns"
                  :key="col.key"
                  :value="col.key"
                  :disabled="col.fixed"
                  class="col-item"
                >
                  {{ col.label }}
                  <span v-if="col.fixed" class="col-fixed">必显</span>
                </el-checkbox>
              </el-checkbox-group>
              <el-button link type="primary" class="col-reset" @click="cols.reset()">
                恢复默认
              </el-button>
            </div>
          </el-popover>
        </template>

        <!-- 列宽预算（1440 屏下表格容器约 1146px，见 docs/01 §S6）：
             固定列 请求时间 136 + 采集目标 200 + 第几页 60 + 状态码 64 + 耗时 72 +
             本页条数 72 + 目标总数 72 + 还有下一页 80 + 错误信息 110 = 866px，
             剩下的一律归「请求 URL」（它写了 min-width，是可伸缩列）。
             → 1440 屏：url = 1146 − 866 = 280px；屏越宽 url 越宽。

             🔴 「可伸缩」这个角色只能给**真正需要宽度的那一列**。
             el-table 会把容器的全部剩余宽度都给可伸缩列（多列才按比例分摊），
             所以给错列 = 那一列被撑爆、该宽的列却卡死（上一版就是这么翻车的：
             错误信息写成了 min-width，结果它把余量全吃了，URL 只有 280px）。
             ⚠️ 加列 / 加宽前先把固定列总和算一遍 —— 超了不会横向滚动，
             Element Plus 直接把多出来的列藏掉（横向滚动条默认隐藏）。 -->
        <el-table-column
          v-if="cols.isVisible('createdAt')"
          label="请求时间"
          width="136"
        >
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>

        <!-- ⚠️ 宽度必须**固定**（width 而不是 min-width）：min-width 是可伸缩列，
             一旦它是这张表里**唯一**一个可伸缩列，就会把表格剩余宽度全部吃掉，
             表现成「采集目标这一列莫名很宽」。
             200 是量出来的：最长名称「哲学宗教-马克思哲学」+ 编号 ≈ 186px（含内边距） -->
        <el-table-column
          v-if="cols.isVisible('target')"
          label="采集目标"
          width="200"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span>{{ targetName(row) }}</span>
            <span class="target-id">{{ row.targetId ?? '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('pageIndex')"
          label="第几页"
          width="60"
          align="right"
        >
          <template #default="{ row }">
            <span class="num">{{ dash(row.pageIndex) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('statusCode')"
          label="状态码"
          width="64"
          align="center"
        >
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row)" effect="plain">
              {{ statusText(row) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('responseTimeMs')"
          label="耗时(ms)"
          width="72"
          align="right"
        >
          <template #default="{ row }">
            <span class="num" :class="timeClass(row.responseTimeMs)">
              {{ formatNumber(row.responseTimeMs) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('resultCount')"
          label="本页条数"
          width="72"
          align="right"
        >
          <template #default="{ row }">
            <span class="num">{{ dash(row.resultCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('totalCount')"
          label="目标总数"
          width="72"
          align="right"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.totalCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('hasMore')"
          label="还有下一页"
          width="80"
          align="center"
        >
          <template #default="{ row }">
            <span v-if="row.hasMore === null || row.hasMore === undefined" class="text-muted">-</span>
            <el-tag v-else size="small" :type="row.hasMore ? 'primary' : 'info'" effect="plain">
              {{ row.hasMore ? '有' : '没有' }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 🔴 URL 是这张表里**唯一**的可伸缩列（min-width），这是刻意的：
             它是最需要宽度的一列，容器的剩余宽度全部归它 —— 屏越宽，URL 露得越多。
             250 只是下限（窄屏兜底），正常 1440 屏拿到的是 280px，更宽的屏更多。
             完整内容另有 hover 提示兜底 -->
        <el-table-column
          v-if="cols.isVisible('url')"
          label="请求 URL"
          min-width="250"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span class="text-mono">{{ dash(row.url) }}</span>
          </template>
        </el-table-column>

        <!-- ⚠️ 必须是**固定**小宽度，不能写 min-width。
             它排在最后一列，一旦变成可伸缩列，容器的剩余宽度就全归它了 ——
             而这一列在现有数据里 261 条**全是空的**（没有失败请求），
             等于用一整片空白把 URL 挤窄。完整报错内容交给 hover 提示 -->
        <el-table-column
          v-if="cols.isVisible('errorMsg')"
          label="错误信息"
          width="110"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span v-if="row.errorMsg" class="error-text">{{ row.errorMsg }}</span>
            <span v-else class="text-muted">-</span>
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

.table-card :deep(.el-card__body) {
  padding: 16px;
}

.query-hint {
  grid-column: 1 / -1;
  margin: -8px 0 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

/* ---- 健康度摘要 ---- */
.stat-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 28px;
  margin: 0 0 14px;
  padding: 0 2px 14px;
  border-bottom: 1px solid #ebeef5;
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-label {
  font-size: 12px;
  color: #909399;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.2;
  color: #303133;
  font-variant-numeric: tabular-nums;
}

.stat-bad {
  color: #f56c6c;
}

.stat-note {
  margin-left: auto;
  font-size: 12px;
  color: #c0c4cc;
}

/* 目标列里的编号：跟在名称后面，灰色小字 —— 名称是给人看的，编号是给筛选栏用的 */
.target-id {
  margin-left: 6px;
  font-size: 12px;
  color: #909399;
}

.num {
  font-variant-numeric: tabular-nums;
}
.time-warn {
  color: #e6a23c;
}
.time-slow {
  font-weight: 600;
  color: #f56c6c;
}
.error-text {
  color: #f56c6c;
}

.col-panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.col-item {
  margin-right: 0;
  height: 26px;
}
.col-fixed {
  margin-left: 6px;
  font-size: 11px;
  color: #c0c4cc;
}
.col-reset {
  align-self: flex-start;
  margin-top: 4px;
}
</style>

<style>
/* 失败行整行染色。⚠️ 必须是非 scoped 的：el-table 的行元素不在组件作用域内，
   scoped 的 data-v 属性加不到它们身上（用 :deep 也要能找到选择器宿主） */
.el-table .row-failed td.el-table__cell {
  background-color: #fef0f0;
}
.el-table .row-failed:hover > td.el-table__cell {
  background-color: #fde2e2 !important;
}
</style>
