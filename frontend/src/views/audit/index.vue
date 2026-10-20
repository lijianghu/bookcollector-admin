<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getRequestDetail, getRequestSummary, pageRequests, replayRequest } from '@/api/audit'
import { getWereadCredential } from '@/api/weread'
import { listTaxonomy } from '@/api/taxonomy'
import ProTable from '@/components/ProTable.vue'
import SearchForm from '@/components/SearchForm.vue'
import { useColumnPrefs, type ColumnPref } from '@/composables/useColumnPrefs'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import {
  targetTypeLabel,
  type ApiRequest,
  type ReplayResult,
  type RequestDetail,
  type RequestSummary,
  type TargetType,
} from '@/types'

/**
 * 请求审计（S6.6）。
 *
 * <h3>这一页解决什么问题</h3>
 * 「为什么这批数据不对？」是采集类系统最难查的问题。这一层记录**每一次对
 * 平台接口的 HTTP 请求**（`ApiRequestRepository` 在采集过程中写入），
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
  targetType: TargetType | ''
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
  return targetTypeLabel(row.targetType)
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

// ---------------------------------------------------------------------------
// 详情（2026-10-15 新增）
// ---------------------------------------------------------------------------

/**
 * 请求详情弹窗。
 *
 * <h3>解决什么问题</h3>
 * 列表给的是「一次请求的摘要」；但一次采集采到 0 条时，光看摘要分不清：
 * <ul>
 *   <li><b>数据访问问题</b> —— 接口压根没给数据：看 {@code rawResponse}</li>
 *   <li><b>解析问题</b> —— 给了数据但没解析出来：看 rawResponse 里有没有书 +
 *       步骤日志里解析阶段的记录</li>
 * </ul>
 * 所以详情把「URL 参数 / 请求头 / 原始响应 / 步骤日志」一次端平，用户点完
 * 「重试」直接翻这里就能自己定位，不用再来问。
 *
 * <h3>🔴 skey 是掩码值</h3>
 * 后端不存明文登录态，详情里的 {@code skey} 头形如 {@code tu****}。这是刻意的，
 * 弹窗顶部有提示，别当bug「修」。
 */
const detailVisible = ref(false)
const detail = ref<RequestDetail | null>(null)
const detailLoading = ref(false)

async function showDetail(row: ApiRequest): Promise<void> {
  if (!row.id) {
    return
  }
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getRequestDetail(row.id)
  } catch {
    // 拦截器已弹错误提示；关掉弹窗，别留一个空架子
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

/** 日志级别 → tag 样式。后端只有 INFO / WARN / ERROR 三种 */
function logLevelType(level?: string | null): 'info' | 'warning' | 'danger' {
  if (level === 'ERROR') {
    return 'danger'
  }
  if (level === 'WARN') {
    return 'warning'
  }
  return 'info'
}

/** Record → [k, v][]。null 安全（没有该字段给空数组，不是 null） */
function entriesOf(map?: Record<string, string> | null): [string, string][] {
  return map ? (Object.entries(map) as [string, string][]) : []
}

/** 请求头 key→value 列表。有 skey 时提示已掩码 */
const headerEntries = computed<[string, string][]>(() => entriesOf(detail.value?.record.requestHeaders))

/** URL 参数 key→value 列表 */
const paramEntries = computed<[string, string][]>(() => entriesOf(detail.value?.record.queryParams))

/** 原始响应 textarea 用的纯文本；没有就空串 */
const rawResponseText = computed<string>(() => detail.value?.record.rawResponse ?? '')

// ---------------------------------------------------------------------------
// 回放（2026-10-15 晚新增，当晚 redesign：URL / 参数 / 请求头均可编辑）
// ---------------------------------------------------------------------------

/** 回放弹窗里可编辑的一行（参数与请求头共用） */
interface ReplayKV {
  key: string
  value: string
}

/**
 * 回放：用最原始的 HTTP（后端 JDK HttpURLConnection，不走 RestTemplate / 拦截器）
 * 把弹窗里这份<b>可编辑</b>的请求发出去，结果只在本弹窗展示。
 *
 * <h3>解决什么问题</h3>
 * 详情弹窗展示的是「当时」的现场。有时想问的是「把这个请求<b>改一改</b>再发会怎样」——
 * 比如怀疑某个参数、想换 skey 之外的某个头、或者当时是偶发的服务端错误。
 * 所以 URL、参数、请求头全部可改，按改后的最新值发起。
 *
 * <h3>🔴 参数行与 URL 是同一份数据的两个视图</h3>
 * URL 是唯一真相（后端只收 URL）：改参数行 → 重建 query 写回 URL；
 * 改 URL（失焦后）→ 重新解析出参数行。所以不存在「两份不同步」的状态。
 *
 * <p>⚠️ skey 行的值<b>禁改</b>：那是掩码值，实际发送时后端一律替换为当前保存的凭证。
 *
 * <p>⚠️ 不写审计（后端与前端约定一致）：这是诊断动作，记进去会污染
 * 「这个目标被采了多少次」的口径。
 */
const replayVisible = ref(false)
const replayLoading = ref(false)
const replayResult = ref<ReplayResult | null>(null)

/** 可编辑表单。destroy-on-close 会销毁弹窗内容，但这个 state 跨次打开保留 */
const replayForm = reactive<{ url: string; params: ReplayKV[]; headers: ReplayKV[] }>({
  url: '',
  params: [],
  headers: [],
})

/**
 * 打开回放弹窗：用详情里那条记录预填（url / queryParams / requestHeaders）。
 *
 * <h3>🔴 skey 行预填「当前凭证的明文」而不是记录里的掩码</h3>
 * 审计快照里的 skey 是掩码（{@code Qp0****}），照着发没意义；后端会把掩码
 * 换成库里当前凭证的 skey。但这样用户就<b>看不见实际会发哪把 skey</b>，
 * 也没法复刻 Postman（用户实测「同样请求回放没有 bookInfo、Postman 有」，
 * 追问「你额外做了什么」—— skey 就是那处额外动作）。
 * 所以这里从凭证接口（本来就地明文回显）取真值填进去：看得见、可对照、可改。
 * 取不到就保留快照里的掩码值，交给后端的替换逻辑。
 */
async function openReplay(): Promise<void> {
  const rec = detail.value?.record
  if (!rec || !rec.url) {
    return
  }
  replayForm.url = rec.url
  replayForm.params = toKV(rec.queryParams)
  replayForm.headers = toKV(rec.requestHeaders)
  replayResult.value = null
  replayVisible.value = true
  await prefillSkey()
}

/** 当前凭证的 skey（明文）。只拉一次，失败就保持掩码值 */
const credentialSkey = ref<string | null>(null)
let credentialFetched = false

async function prefillSkey(): Promise<void> {
  if (!credentialFetched) {
    credentialFetched = true
    try {
      const cred = await getWereadCredential()
      credentialSkey.value = cred.skey ?? null
    } catch {
      // 拉不到凭证不影响回放：skey 行保留掩码，由后端替换成库里的值
      credentialSkey.value = null
    }
  }
  const skey = credentialSkey.value
  if (!skey) {
    return
  }
  const row = replayForm.headers.find(h => isSkeyRow(h.key))
  if (row) {
    row.value = skey
  } else {
    replayForm.headers.push({ key: 'skey', value: skey })
  }
}

/** Record → KV 行（null 安全，字段缺失给空数组） */
function toKV(map?: Record<string, string> | null): ReplayKV[] {
  return map ? Object.entries(map).map(([key, value]) => ({ key, value })) : []
}

/** KV 行 → Record（跳过空键——空键拼进 URL 只会造出 `=x` 这种脏参数） */
function fromKV(rows: ReplayKV[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const row of rows) {
    if (row.key) {
      out[row.key] = row.value
    }
  }
  return out
}

/** URL 去掉 query 的部分（第一个 `?` 之前） */
function urlBase(url: string): string {
  const q = url.indexOf('?')
  return q < 0 ? url : url.substring(0, q)
}

/** 按参数行重建 query 拼回 URL。值原样拼接（记录里就是编码态，用户改的也是编码态） */
function rebuildUrl(url: string, params: ReplayKV[]): string {
  const qs = params.filter(p => p.key).map(p => `${p.key}=${p.value}`).join('&')
  return qs ? `${urlBase(url)}?${qs}` : urlBase(url)
}

/** 解析 URL query 成 Record（编码态原样，与后端 `WereadHeaders.parseQuery` 同口径） */
function parseQueryOf(url: string): Record<string, string> {
  const out: Record<string, string> = {}
  const q = url.indexOf('?')
  if (q < 0 || q === url.length - 1) {
    return out
  }
  for (const pair of url.substring(q + 1).split('&')) {
    if (!pair) {
      continue
    }
    const eq = pair.indexOf('=')
    if (eq < 0) {
      out[pair] = ''
      continue
    }
    out[pair.substring(0, eq)] = pair.substring(eq + 1)
  }
  return out
}

/** 改了参数行 → 写回 URL（每敲一个键就重建，URL 输入框实时跟着变） */
function onParamEdit(): void {
  replayForm.url = rebuildUrl(replayForm.url, replayForm.params)
}

/**
 * 改了 URL 输入框 → 重新解析出参数行。
 *
 * <p>刻意挂 change（失焦 / 回车）而不是 input：敲 URL 的过程中 query 是半成品，
 * 每键重解析会让参数行疯狂闪烁，还把用户正在编辑的值冲掉。
 */
function onUrlEdit(): void {
  replayForm.params = toKV(parseQueryOf(replayForm.url))
}

/** skey 行的值禁改（掩码值，后端会替换成当前凭证） */
function isSkeyRow(key: string): boolean {
  return key.toLowerCase() === 'skey'
}

/** 发起请求：提交 URL（唯一真相）+ 请求头（skey 由后端替换） */
async function sendReplay(): Promise<void> {
  if (!replayForm.url) {
    ElMessage.warning('请求 URL 不能为空')
    return
  }
  replayLoading.value = true
  try {
    replayResult.value = await replayRequest({
      url: replayForm.url,
      requestHeaders: fromKV(replayForm.headers),
    })
  } catch {
    // 拦截器已弹错误提示（如 URL 为空、书单配置信息不完整）
  } finally {
    replayLoading.value = false
  }
}

/** 回放状态文案。0 = 请求没发出去（网络异常等） */
function replayStatusText(r: ReplayResult): string {
  return r.statusCode > 0 ? String(r.statusCode) : '无响应'
}

/** 实际发送的请求头列表（来自结果而非表单）—— 「回放到底发了什么」的唯一可信来源 */
const actualHeaderEntries = computed<[string, string][]>(
  () => entriesOf(replayResult.value?.requestHeaders),
)

/**
 * 形状指纹的配色：**两种形态都正常**（`BookParser` 两种都收：带 bookInfo 的包装形态、
 * 元素即书籍对象的拍平形态），所以只有「错误信封 / 不是合法 JSON / 认不出形状 / 没有 books 数组」
 * 这类才是问题，标黄提醒。
 */
function shapeHintType(hint: string): 'success' | 'warning' {
  const bad = hint.includes('错误信封')
    || hint.includes('不是合法 JSON')
    || hint.includes('不是 JSON 对象')
    || hint.includes('认不出形状')
    || hint.includes('没有 books 数组')
  return bad ? 'warning' : 'success'
}

/** 回放状态配色。错误优先于状态码 —— 有 errorMsg 时状态码恒为 0 */
function replayStatusType(r: ReplayResult): 'success' | 'danger' | 'info' {
  if (r.errorMsg) {
    return 'danger'
  }
  return r.statusCode >= 200 && r.statusCode < 400 ? 'success' : 'danger'
}

/** 复制到剪贴板。navigator.clipboard 在非安全上下文（http）下不可用，走兜底提示 */
async function copyText(text: string, label: string): Promise<void> {
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`已复制${label}`)
  } catch {
    ElMessage.warning('浏览器拒绝了复制，请手动选择文本复制')
  }
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
          <el-option label="书单" value="BOOKLIST" />
        </el-select>
      </el-form-item>

      <el-form-item label="目标 ID">
        <el-input v-model="query.targetId" clearable placeholder="如 300000 / rising" />
      </el-form-item>

      <el-form-item label="只看失败">
        <el-switch v-model="onlyFailed" />
      </el-form-item>

      <div class="query-hint">
        记录每一次对远程 HTTP 请求的完整记录。
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

        <!-- 详情（2026-10-15 新增）：一次看全「URL 参数 / 请求头 / 原始响应 / 步骤日志」，
             用来区分「数据访问问题」与「解析问题」。
             ⚠️ 固定列 + 80px：它进不了列宽预算的可伸缩角色（那个只能给 URL）。
             固定列总和 866 + 80 = 946，1440 屏（表格容器约 1146px）下 URL 仍有 200px。 -->
        <el-table-column label="操作" width="80" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </ProTable>
    </el-card>

    <!-- ==================================================================
         请求详情弹窗（2026-10-15 新增）
         —— 「一次请求为什么采到 0 条」的最终定位现场。
         ================================================================== -->
    <el-dialog
      v-model="detailVisible"
      title="请求详情"
      width="860px"
      top="6vh"
      destroy-on-close
    >
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <!-- 🔴 skey 掩码是刻意的（后端不存明文登录态），不是显示 bug -->

          <!-- ---- 基本信息 ---- -->
          <el-descriptions :column="3" border size="small" class="detail-block">
            <el-descriptions-item label="请求时间">
              {{ formatDateTime(detail.record.createdAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="采集目标">
              {{ targetName(detail.record) }}
              <span class="target-id">{{ detail.record.targetId ?? '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="第几页 / maxIndex">
              {{ dash(detail.record.pageIndex) }} / {{ dash(detail.record.maxIndex) }}
            </el-descriptions-item>
            <el-descriptions-item label="HTTP 状态">
              <el-tag size="small" :type="statusTagType(detail.record)" effect="plain">
                {{ statusText(detail.record) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="耗时">
              {{ msText(detail.record.responseTimeMs) }}
            </el-descriptions-item>
            <el-descriptions-item label="本页条数 / 目标总数">
              {{ dash(detail.record.resultCount) }} / {{ formatNumber(detail.record.totalCount) }}
            </el-descriptions-item>
            <el-descriptions-item label="还有下一页">
              <span v-if="detail.record.hasMore === null || detail.record.hasMore === undefined" class="text-muted">-</span>
              <span v-else>{{ detail.record.hasMore ? '有' : '没有' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="synckey">
              {{ detail.record.synckey ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="runId">
              <span class="text-mono">{{ detail.record.runId ?? '-（无关联运行）' }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.record.errorMsg" label="错误信息" :span="3">
              <span class="error-text">{{ detail.record.errorMsg }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <!-- ---- 请求 URL + 参数 ---- -->
          <div class="detail-block">
            <div class="detail-heading">
              <span>请求 URL</span>
              <!-- 🔴 回放（2026-10-15 晚新增，可编辑请求）：打开回放弹窗（用本记录预填），
                   URL / 参数 / 请求头都能改，按最新值发起；不写审计 -->
              <el-button
                v-if="detail.record.url"
                link
                type="primary"
                @click="openReplay()"
              >
                回放
              </el-button>
              <el-button
                v-if="detail.record.url"
                link
                type="primary"
                @click="copyText(detail.record.url ?? '', 'URL')"
              >
                复制
              </el-button>
            </div>
            <div class="kv-mono raw-url">{{ detail.record.url ?? '-' }}</div>
          </div>

          <div class="detail-block">
            <div class="detail-heading">
              <span>请求参数</span>
            </div>
            <div v-if="paramEntries.length === 0" class="text-muted">（URL 上没有参数）</div>
            <div v-else class="kv-grid">
              <div v-for="[k, v] in paramEntries" :key="k" class="kv-row">
                <span class="kv-key">{{ k }}</span>
                <span class="kv-value kv-mono">{{ v }}</span>
              </div>
            </div>
          </div>

          <!-- ---- 请求头快照 ---- -->
          <div class="detail-block">
            <div class="detail-heading">
              <span>请求头</span>
            </div>
            <div v-if="headerEntries.length === 0" class="text-muted">
              （没有记录到请求头 —— 这条记录多半是「URL 都没拼出来」时落的）
            </div>
            <div v-else class="kv-grid">
              <div v-for="[k, v] in headerEntries" :key="k" class="kv-row">
                <span class="kv-key">{{ k }}</span>
                <span class="kv-value kv-mono" :class="{ 'skey-masked': k === 'skey' }">{{ v }}</span>
              </div>
            </div>
          </div>

          <!-- ---- 返回原始响应 ---- -->
          <div class="detail-block">
            <div class="detail-heading">
              <span>返回原始响应</span>
              <!-- 字符数直接回答「有没有被截断」：书单应等于完整长度（约 90 万），
                   列表/榜单超过 8000 就是被截了 -->
              <span v-if="rawResponseText" class="raw-len">
                {{ formatNumber(rawResponseText.length) }} 字符
              </span>
              <el-button
                v-if="rawResponseText"
                link
                type="primary"
                @click="copyText(rawResponseText, '原始响应')"
              >
                复制
              </el-button>
            </div>
            <!-- 用 <pre> 而不是 textarea：只读展示，且保留原始缩进。
                 max-height + 滚动：书单响应可达约 90 万字符，不能把弹窗撑爆 -->
            <pre v-if="rawResponseText" class="raw-response">{{ rawResponseText }}</pre>
            <div v-else class="text-muted">
              （没有响应体 —— 请求没发出去或没有返回内容，看上面的错误信息）
            </div>
            <div class="detail-note">
              提示：这里有书而「本页条数」是 0 → 解析问题；这里就没有书 → 数据访问问题。
            </div>
          </div>

          <!-- ---- 核心步骤日志 ---- -->
          <div class="detail-block">
            <div class="detail-heading">
              <span>核心步骤日志</span>
              <span class="detail-heading-note">按运行记录，时间正序</span>
            </div>
            <div v-if="!detail.logs || detail.logs.length === 0" class="text-muted">
              <template v-if="detail.record.runId">该次运行没有落库日志</template>
              <template v-else>该请求没有关联运行（如手工触发），无步骤日志</template>
            </div>
            <div v-else class="log-list">
              <!-- key 用下标兜底：同一毫秒可能落多条日志，createdAt 不唯一 -->
              <div v-for="(logItem, logIndex) in detail.logs" :key="logItem.id ?? `log-${logIndex}`" class="log-item">
                <span class="log-time">{{ formatDateTime(logItem.createdAt) }}</span>
                <el-tag size="small" :type="logLevelType(logItem.level)" effect="plain" class="log-level">
                  {{ logItem.level ?? '-' }}
                </el-tag>
                <span class="log-message">{{ logItem.message }}</span>
              </div>
            </div>
          </div>
        </template>
      </div>
    </el-dialog>

    <!-- ==================================================================
         回放弹窗（2026-10-15 晚新增，当晚 redesign：可编辑请求）
         —— 用最原始的 HTTP（不走 RestTemplate / 拦截器）把弹窗里这份
            URL / 参数 / 请求头发出去。信息栅格混合「原记录字段」与
            「本次回放结果」；只展示，不落库。
         ================================================================== -->
    <el-dialog
      v-model="replayVisible"
      title="回放请求"
      width="860px"
      top="6vh"
      destroy-on-close
    >
      <div class="detail-body">
        <!-- ---- 信息栅格：前三列是原记录的口径，HTTP 状态 / 耗时 / synckey 是本次回放的结果 ---- -->
        <el-descriptions :column="3" border size="small" class="detail-block">
          <el-descriptions-item label="请求时间">
            {{ detail ? formatDateTime(detail.record.createdAt) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="采集目标">
            <template v-if="detail">
              {{ targetName(detail.record) }}
              <span class="target-id">{{ detail.record.targetId ?? '-' }}</span>
            </template>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="第几页 / maxIndex">
            <template v-if="detail">{{ dash(detail.record.pageIndex) }} / {{ dash(detail.record.maxIndex) }}</template>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="HTTP 状态">
            <el-tag v-if="replayResult" size="small" :type="replayStatusType(replayResult)" effect="plain">
              {{ replayStatusText(replayResult) }}
            </el-tag>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="耗时">
            <span v-if="replayResult">{{ msText(replayResult.costMs) }}</span>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="synckey">
            <span v-if="replayResult">{{ dash(replayResult.responseSynckey) }}</span>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="还有下一页">
            <template v-if="detail">
              <span v-if="detail.record.hasMore === null || detail.record.hasMore === undefined" class="text-muted">-</span>
              <span v-else>{{ detail.record.hasMore ? '有' : '没有' }}</span>
            </template>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="本页条数 / 目标总数">
            <template v-if="detail">
              {{ dash(detail.record.resultCount) }} / {{ formatNumber(detail.record.totalCount) }}
            </template>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="runId">
            <span v-if="detail" class="text-mono">{{ detail.record.runId ?? '-（无关联运行）' }}</span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="replayResult?.errorMsg" label="错误信息" :span="3">
            <span class="error-text">{{ replayResult.errorMsg }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="detail-note">
          请求时间 / 采集目标 / 页数 / 总数 / runId 来自原审计记录；HTTP 状态、耗时、synckey 是<b>本次回放</b>的结果（发起前为「-」）。
        </div>

        <!-- ---- 请求 URL（可修改）---- -->
        <div class="detail-block">
          <div class="detail-heading">
            <span>请求 URL（可修改）</span>
            <el-button link type="primary" @click="copyText(replayForm.url, 'URL')">
              复制
            </el-button>
          </div>
          <el-input v-model="replayForm.url" class="url-input" @change="onUrlEdit()" />
        </div>

        <!-- ---- 请求参数（可修改，改动即写回 URL）---- -->
        <div class="detail-block">
          <div class="detail-heading">
            <span>请求参数（可修改，改动即写回 URL）</span>
          </div>
          <div v-if="replayForm.params.length === 0" class="text-muted">（URL 上没有参数）</div>
          <div v-else class="kv-grid">
            <div v-for="(row, i) in replayForm.params" :key="`p-${i}`" class="kv-row kv-row-edit">
              <el-input v-model="row.key" size="small" class="kv-input" @input="onParamEdit()" />
              <el-input v-model="row.value" size="small" class="kv-input" @input="onParamEdit()" />
            </div>
          </div>
        </div>

        <!-- ---- 请求头（可修改）---- -->
        <div class="detail-block">
          <div class="detail-heading">
            <span>请求头（可修改）</span>
            <span class="detail-heading-note">
              skey 已填入当前凭证的明文，可直接改成 Postman 那把；留空则用当前凭证
            </span>
          </div>
          <div v-if="replayForm.headers.length === 0" class="text-muted">（没有请求头）</div>
          <div v-else class="kv-grid">
            <div v-for="(row, i) in replayForm.headers" :key="`h-${i}`" class="kv-row kv-row-edit">
              <el-input v-model="row.key" size="small" class="kv-input" :class="{ 'skey-masked': isSkeyRow(row.key) }" />
              <el-input
                v-model="row.value"
                size="small"
                class="kv-input"
                :class="{ 'skey-masked': isSkeyRow(row.key) }"
                :placeholder="isSkeyRow(row.key) ? '留空 = 用当前保存的凭证' : ''"
              />
            </div>
          </div>
        </div>

        <!-- ---- 实际发送（与 Postman 逐项对照用）---- -->
        <div v-if="replayResult" class="detail-block">
          <div class="detail-heading">
            <span>实际发送（用于与 Postman 对照）</span>
            <span class="detail-heading-note">真正发出去的那份（skey 掩码；User-Agent 已被客户端摘掉；Host 由协议层自动加）</span>
          </div>
          <div class="kv-mono raw-url">{{ replayResult.url }}</div>
          <div v-if="actualHeaderEntries.length" class="kv-grid actual-headers">
            <div v-for="[k, v] in actualHeaderEntries" :key="k" class="kv-row">
              <span class="kv-key">{{ k }}</span>
              <span class="kv-value kv-mono" :class="{ 'skey-masked': k.toLowerCase() === 'skey' }">{{ v }}</span>
            </div>
          </div>
          <!-- 🔴 skey 是唯一一处可能不照抄提交值的地方，必须自证「用了谁的」 -->
          <div class="skey-source">
            <template v-if="replayResult.skeyFrom === 'SUBMITTED'">
              skey：使用你提交的值
            </template>
            <template v-else-if="replayResult.skeyFrom === 'CREDENTIAL'">
              skey：提交的是掩码或留空，<b>已替换为当前保存的凭证</b>（想完全复刻 Postman，
              请在上方 skey 行粘贴 Postman 那把）
            </template>
            <template v-else>skey：本次未发送</template>
          </div>
        </div>

        <!-- ---- 形状指纹：一行说清「为什么没有 bookInfo」---- -->
        <el-alert
          v-if="replayResult?.shapeHint"
          :type="shapeHintType(replayResult.shapeHint)"
          :closable="false"
          show-icon
          class="shape-hint"
        >
          {{ replayResult.shapeHint }}
        </el-alert>

        <!-- ---- 返回原始响应（发起后才有）---- -->
        <div class="detail-block">
          <div class="detail-heading">
            <span>返回原始响应</span>
            <span v-if="replayResult?.body" class="raw-len">
              {{ formatNumber(replayResult.body.length) }} 字符
            </span>
            <el-button
              v-if="replayResult?.body"
              link
              type="primary"
              @click="copyText(replayResult.body, '原始响应')"
            >
              复制
            </el-button>
          </div>
          <!-- body 是服务端返回的原文（后端不过 fastjson、不重序列化）。
               max-height + 滚动：书单响应可达约 90 万字符，不能把弹窗撑爆 -->
          <pre v-if="replayResult?.body" class="raw-response">{{ replayResult.body }}</pre>
          <div v-else class="text-muted">
            （还没有发起请求 —— 改完上面的 URL / 参数 / 请求头，点右下角「发起请求」）
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="replayVisible = false">关闭</el-button>
        <el-button type="primary" :loading="replayLoading" @click="sendReplay()">
          发起请求
        </el-button>
      </template>
    </el-dialog>
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

/* ---- 请求详情弹窗（2026-10-15 新增）---- */

.detail-body {
  min-height: 120px;
}

.detail-tip {
  margin-bottom: 14px;
}

.detail-block {
  margin-bottom: 18px;
}

.detail-heading {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.detail-heading-note {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

.detail-note {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

/* key→value 两列网格：键固定宽、值占满 */
.kv-grid {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  overflow: hidden;
}

/* 回放弹窗的可编辑行：键列与 .kv-key 同宽（180px），两个输入框之间留缝 */
.kv-row-edit {
  align-items: center;
  gap: 12px;
  padding: 4px 12px;
}

.kv-row-edit .kv-input {
  flex: 1;
  min-width: 0;
}

.kv-row-edit .kv-input:first-child {
  flex: 0 0 180px;
}

/* URL / 参数 / 请求头的可编辑输入框：等宽字体，和只读展示时一致 */
.kv-input :deep(.el-input__inner),
.url-input :deep(.el-input__inner) {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
}

/* 回放弹窗的「实际发送」区：URL 与头清单之间留一点距离 */
.actual-headers {
  margin-top: 8px;
}

/* 「skey 用了谁的」一行：弱化处理，但必须在「实际发送」区里看得见 */
.skey-source {
  margin-top: 8px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
  background: #f5f7fa;
  border-radius: 4px;
}

/* 形状指纹：一行结论，压在「返回原始响应」上方 */
.shape-hint {
  margin-bottom: 12px;
}

.kv-row {
  display: flex;
  gap: 12px;
  padding: 6px 12px;
  font-size: 12px;
  line-height: 1.6;
}

.kv-row + .kv-row {
  border-top: 1px solid #ebeef5;
}

.kv-key {
  flex: 0 0 180px;
  color: #909399;
  word-break: break-all;
}

.kv-value {
  flex: 1;
  min-width: 0;
  color: #303133;
  word-break: break-all;
  white-space: pre-wrap;
}

/* skey 掩码值：看得出的灰，不引起「是不是坏了」的怀疑 */
.skey-masked {
  color: #e6a23c;
}

.kv-mono,
.raw-url {
  font-family: Consolas, Monaco, 'Courier New', monospace;
}

.raw-url {
  padding: 8px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: #303133;
  background: #f5f7fa;
  border-radius: 4px;
  word-break: break-all;
}

/* 原始响应长度（字符数）——回答「有没有被截断」，弱化显示不抢标题 */
.raw-len {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

/* 原始响应：等宽 + 保留换行 + 限高滚动（书单响应整份存，可达约 90 万字符） */
.raw-response {
  max-height: 320px;
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #303133;
  background: #f5f7fa;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 步骤日志：时间 + 级别 + 消息 一行一条 */
.log-list {
  max-height: 320px;
  overflow: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.log-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 6px 12px;
  font-size: 12px;
  line-height: 1.6;
}

.log-item + .log-item {
  border-top: 1px solid #ebeef5;
}

.log-time {
  flex: 0 0 150px;
  color: #909399;
  font-variant-numeric: tabular-nums;
}

.log-level {
  flex: 0 0 auto;
}

.log-message {
  flex: 1;
  min-width: 0;
  color: #303133;
  word-break: break-all;
  white-space: pre-wrap;
}

/* ⚠️ .text-muted / .text-mono 是全局类（styles/index.css），
   本文件只补详情弹窗用到的结构性样式，不重复定义文字类 */
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
