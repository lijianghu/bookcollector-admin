<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  backfillBooklists,
  collectBooklist,
  createBooklist,
  getBooklistBackfillProgress,
  getBooklistCollectProgress,
  getBooklistSyncProgress,
  listBooklistCategories,
  pageBooklists,
  syncBooklists,
} from '@/api/booklist'
import { getWereadCredential, saveWereadCredential } from '@/api/weread'
import BooklistBooksDialog from './BooklistBooksDialog.vue'
import ProTable from '@/components/ProTable.vue'
import SearchForm from '@/components/SearchForm.vue'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import {
  BOOKLIST_SORT_OPTIONS,
  booklistDisplayName,
  isBooklistUnnamed,
  type Booklist,
  type BooklistBackfillVO,
  type BooklistCollectVO,
  type BooklistPlatformCategory,
  type BooklistQuery,
  type BooklistSyncVO,
  type WereadCredentialVO,
} from '@/types'

/**
 * 书单配置。
 *
 * <h3>这一页干两件事</h3>
 * <ol>
 *   <li><b>发现书单</b>（链路 A）：把 16 个平台分类平铺出来，勾选后点「开始同步」，
 *       按「最多收藏」排序分页拉取，写进 `booklists` 表；</li>
 *   <li><b>看书单池</b>：一张可筛选、可分页的列表，是「任务配置」里选采集目标的数据源。</li>
 * </ol>
 *
 * <h3>🔴 闸门（用户 2026-10-14 定的口径）</h3>
 * - 书单里的书数 **≥ 50** 才入库（不达标只跳过这一张）；
 * - 收藏人数 **≥ 10000**，一旦不达标就**停止该分类的翻页**（不是只跳过这一张）。
 *
 * <p>第二条成立的<b>前提</b>是接口带 `tag=最多收藏` 时按收藏人数降序返回 ——
 * 所以「某条不达标 → 后面都不达标」。这条推理依赖「有序」，`tag` 一旦漏掉
 * 就会漏采中间达标的大书单且不报错（用户已实测确认加 tag 就会排序）。
 *
 * <h3>为什么顶部要平铺 16 个分类，而不是一个「一键同步」按钮</h3>
 * 用户明确要求：「将 16 个分类平铺到页面，做成功能，点击确认后触发」。
 * 分类清单来自后端（`GET /api/booklists/categories`）—— 那是代码里的业务常量，
 * **不要**在前端再抄一份，两份迟早不一致。
 *
 * <h3>轮询</h3>
 * 「开始同步」是**提交即返回**（16 个分类几百次请求，HTTP 挂着会撞超时），
 * 靠轮询 `GET /api/booklists/sync/progress` 跟进。是否继续轮询看后端的 `running`
 * —— 它来自后端**内存**，所以刷新页面也能接着轮询，不会卡在「执行中」。
 */

// ---------------------------------------------------------------------------
// 书单接口凭证（2026-10-15 从「任务配置」页移来，放本页最上面）
//
// 🔴 为什么放这一页：八项凭证（sUrl / skey / channelid / vid /
//    priority / acceptLanguage / basever / v）只服务于书单链路 ——
//    同步书单（链路 A）与书单采集（链路 B）都走它们。原来挂在任务配置的
//    工具栏里，和「任务」没有直接关系，且塞不下八个字段。
//
// 🔴 synckey 不在表单里（用户 2026-10-15 拍板「自动生成吧，这个不用配置了」）：
//    当晚又定了口径「不是从 root.synckey 取，而是后端 new Date() 的秒值」——
//    后端在每次书单请求发出前按本地时间生成，每个请求都带。
//    表单下方那行只读展示的是「最近一次请求用的值」。
// ---------------------------------------------------------------------------

const cred = ref<WereadCredentialVO>({
  booklistReady: false,
  skey: '',
  synckey: '',
  channelid: '',
  vid: '',
  priority: '',
  acceptLanguage: '',
  basever: '',
  v: '',
  booklistUrl: '',
  listUrl: '',
})

/** 表单八项。CRED_FIELDS 同时驱动渲染与 VO→表单 的回填，别在两处各写一份字段表 */
const credForm = reactive<Record<string, string>>({
  booklistUrl: '',
  skey: '',
  channelid: '',
  vid: '',
  priority: '',
  acceptLanguage: '',
  basever: '',
  v: '',
})
const credSaving = ref(false)

/**
 * synckey 的只读展示文案。它由后端在每次书单请求发出前按本地时间生成 ——
 * 这里显示的是「最近一次请求用的值」。还没有书单请求发生过时为空，
 * 不是错误：跑一次书单采集就有了。
 */
const synckeyHint = computed(() => {
  const v = cred.value.synckey
  if (!v) {
    return '尚未使用（跑一次书单采集后显示最近一次的值）'
  }
  return `${v}（最近一次书单请求使用）`
})

/** 表单字段的展示元数据。tip 挂 el-tooltip，原文字段名不再需要用户去翻文档 */
const CRED_FIELDS: Array<{ key: string; label: string; placeholder: string; tip: string }> = [
  {
    key: 'booklistUrl',
    label: 'sUrl',
    placeholder: '书单接口基础地址',
    tip: '书单接口的基础地址（App 端域名，页面上的 sUrl）',
  },
  {
    key: 'skey',
    label: 'skey',
    placeholder: '登录态',
    tip: '阅读平台 App 的登录态（请求样本中得到）。失效时接口会回「登录超时」',
  },
  {
    key: 'channelid',
    label: 'channelid',
    placeholder: '渠道 ID',
    tip: 'App 端请求头。请求头里的原值',
  },
  {
    key: 'vid',
    label: 'vid',
    placeholder: '用户 vid',
    tip: 'App 端请求头。必须与 skey 属于同一账号',
  },
  {
    key: 'priority',
    label: 'priority',
    placeholder: '优先级',
    tip: 'App 端请求头。请求头里的原值',
  },
  {
    key: 'acceptLanguage',
    label: 'accept-language',
    placeholder: '语言',
    tip: 'App 端请求头。请求头里的原值',
  },
  {
    key: 'basever',
    label: 'basever',
    placeholder: '客户端版本号',
    tip: 'App 端请求头。客户端版本号',
  },
  {
    key: 'v',
    label: 'v',
    placeholder: '客户端版本号',
    tip: 'App 端请求头。客户端版本号（与 basever 同值）',
  },
]

async function loadCred(): Promise<void> {
  try {
    cred.value = await getWereadCredential()
    syncCredForm()
  } catch {
    // 拦截器已提示
  }
}

/** VO（后端可能缺字段 / null）→ 表单九项（统一成非空串，避免出现 "null" 字样） */
function syncCredForm(): void {
  for (const f of CRED_FIELDS) {
    const val = cred.value[f.key as keyof WereadCredentialVO]
    credForm[f.key] = typeof val === 'string' ? val : ''
  }
}

async function saveCred(): Promise<void> {
  credSaving.value = true
  try {
    // 九项全量提交（后端缺一项即拒），保存结果回填表单
    cred.value = await saveWereadCredential({ ...credForm })
    syncCredForm()
    ElMessage.success('凭证已保存，立即生效（不用重启）')
  } catch {
    // 拦截器已弹后端 message（书单配置信息不完整：缺 X）
  } finally {
    credSaving.value = false
  }
}

// ---------------------------------------------------------------------------
// 同步（链路 A）
// ---------------------------------------------------------------------------

/** 16 个平台分类（后端常量） */
const categories = ref<BooklistPlatformCategory[]>([])
/** 勾选要同步的分类。默认全选 —— 「全部同步一次」是最常用的动作 */
const selectedCategoryIds = ref<string[]>([])

/** 进度快照。没跑过时为 null */
const progress = ref<BooklistSyncVO | null>(null)
/** 「开始同步」这次请求本身在飞（不是同步任务在跑） */
const submitting = ref(false)
/** 手动点「刷新进度」时的 loading */
const progressLoading = ref(false)

const running = computed(() => progress.value?.running === true)

/** 进度条百分比。分母是**本次**要跑的分类数（用户可能只勾了几个） */
const percent = computed(() => {
  const p = progress.value
  if (!p || p.totalCategories <= 0) {
    return 0
  }
  return Math.min(100, Math.round((p.categoriesDone / p.totalCategories) * 100))
})

const allSelected = computed(
  () => categories.value.length > 0 && selectedCategoryIds.value.length === categories.value.length,
)

/** 勾选的全是数字 ID 时才可能是「全部」；这里只用来决定确认弹窗的措辞 */
const selectionHint = computed(() =>
  allSelected.value
    ? `全部 ${categories.value.length} 个平台分类`
    : `选中的 ${selectedCategoryIds.value.length} 个平台分类`,
)

async function loadCategories(): Promise<void> {
  try {
    const list = await listBooklistCategories()
    categories.value = list
    // 默认全选。⚠️ 只在这里赋值 —— 别在轮询/刷新里再赋一次，
    //    否则用户刚取消的勾选会被悄悄恢复
    selectedCategoryIds.value = list.map((c) => c.categoryId)
  } catch {
    categories.value = []
    selectedCategoryIds.value = []
  }
}

function selectAll(): void {
  selectedCategoryIds.value = categories.value.map((c) => c.categoryId)
}

function clearAll(): void {
  selectedCategoryIds.value = []
}

async function onSync(): Promise<void> {
  if (selectedCategoryIds.value.length === 0) {
    ElMessage.warning('至少勾选一个平台分类')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将对${selectionHint.value}做一次书单同步：按「最多收藏」排序分页拉取，` +
        '写进书单表（按书单 ID 幂等，可反复执行，不会重复插入）。' +
        '闸门：书数 ≥ 50 且 收藏人数 ≥ 10000；收藏人数一旦不达标即停止该分类的翻页。',
      '同步书单',
      { type: 'warning', confirmButtonText: '开始同步', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    const data = await syncBooklists(selectedCategoryIds.value)
    progress.value = data
    wasRunning.value = data.running
    ElMessage.success('已提交，正在后台同步')
    schedulePoll()
  } catch {
    // 拦截器已提示（「正在进行中」/「未知的分类 ID」）
  } finally {
    submitting.value = false
  }
}

// ---------------------------------------------------------------------------
// 进度轮询
// ---------------------------------------------------------------------------

const POLL_MS = 2000
let pollTimer: number | null = null
/** 上一次看到的 running。用来识别「刚刚跑完」这一次跃迁 */
const wasRunning = ref(false)

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
    void loadProgress(true)
  }, POLL_MS)
}

/**
 * @param silent true = 轮询触发的静默刷新（不显示 loading）
 */
async function loadProgress(silent = false): Promise<void> {
  if (!silent) {
    progressLoading.value = true
  }
  try {
    const data = await getBooklistSyncProgress()
    progress.value = data
    if (wasRunning.value && !data.running) {
      // 刚跑完（或刚失败）—— 列表要重拉，否则新发现的那些书单要等用户手动刷新才看得见
      if (data.errorMsg) {
        ElMessage.error(`书单同步失败：${data.errorMsg}`)
      } else {
        ElMessage.success('书单同步已结束')
      }
      void loadList()
    }
    wasRunning.value = data.running
  } catch {
    // 拦截器已提示
  } finally {
    if (!silent) {
      progressLoading.value = false
    }
  }
  schedulePoll()
}

// ---------------------------------------------------------------------------
// 书单列表
// ---------------------------------------------------------------------------

const loading = ref(false)
const rows = ref<Booklist[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const query = ref<BooklistQuery>({
  sortField: 'collectCount',
  asc: false,
  nameMissing: false,
})

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const data = await pageBooklists({
      ...query.value,
      page: page.value,
      size: size.value,
    })
    rows.value = data.list
    total.value = data.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/**
 * 合并同一轮事件里的多次加载请求。
 *
 * 改每页条数时 ProTable 会**同步**先 emit `update:size` 再 emit `update:page(1)`，
 * 两个事件各自都会请求一次 —— 不合并就是两次一模一样的请求，而且**返回顺序不保证**。
 * 用微任务攒一下（同 `views/book/index.vue` 的 `scheduleReload`）。
 */
let reloadScheduled = false
function scheduleReload(): void {
  if (reloadScheduled) {
    return
  }
  reloadScheduled = true
  void Promise.resolve().then(() => {
    reloadScheduled = false
    void loadList()
  })
}

function handleSearch(): void {
  page.value = 1
  scheduleReload()
}

function handleReset(): void {
  query.value = {
    sortField: 'collectCount',
    asc: false,
    nameMissing: false,
  }
  page.value = 1
  scheduleReload()
}

function handlePageChange(next: number): void {
  page.value = next
  scheduleReload()
}

function handleSizeChange(next: number): void {
  size.value = next
  // ProTable 也会 emit update:page(1)，但事件顺序不保证 —— 这里自己也算一次，
  // 免得出现「page 还是 5、size 变成 100」这种越界请求
  page.value = 1
  scheduleReload()
}

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

/** 平台分类标签只显示前 2 个，多出来的折进 `+N` 的 tooltip */
const CAT_TAG_LIMIT = 2

function categoryTooltip(row: Booklist): string {
  return (row.platformCategories ?? []).map((c) => c.title ?? c.categoryId).join('、')
}

/** 收藏人数是否达标（闸门 2 的口径）。不达标标灰，一眼能看出哪些是「边缘数据」 */
function collectCountReached(row: Booklist): boolean {
  return (row.collectCount ?? 0) >= 10000
}

// ---------------------------------------------------------------------------
// 书单详情弹窗（2026-10-16 新增）
// ---------------------------------------------------------------------------

/** 详情弹窗的开关与当前书单。弹窗自己管分页与拉数，这里只负责「打开哪一张」 */
const detailVisible = ref(false)
const detailBooklist = ref<Booklist | null>(null)

function openDetail(row: Booklist): void {
  detailBooklist.value = row
  detailVisible.value = true
}

// ---------------------------------------------------------------------------
// 新增书单（2026-10-20）
// ---------------------------------------------------------------------------

const createVisible = ref(false)
const createSaving = ref(false)
const createForm = reactive({ booklistId: '', name: '' })

function openCreate(): void {
  createForm.booklistId = ''
  createForm.name = ''
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  const id = createForm.booklistId.trim()
  if (!id) {
    ElMessage.warning('书单 ID 不能为空')
    return
  }
  createSaving.value = true
  try {
    await createBooklist(id, createForm.name.trim() || undefined)
    ElMessage.success('书单已新增')
    createVisible.value = false
    page.value = 1
    void loadList()
  } catch {
    // 拦截器已提示（重复 / 参数错）
  } finally {
    createSaving.value = false
  }
}

// ---------------------------------------------------------------------------
// 同步书单书籍（链路 B，2026-10-20）
// ---------------------------------------------------------------------------

const collectVisible = ref(false)
const collectRow = ref<Booklist | null>(null)
const collectProgress = ref<BooklistCollectVO | null>(null)
let collectTimer: number | null = null

/** 进度百分比：已入库 / 声明的总书数。totalCount 未知时停在 0 */
const collectPercent = computed(() => {
  const p = collectProgress.value
  if (!p || !p.totalCount || p.totalCount <= 0) {
    return 0
  }
  return Math.min(100, Math.round(((p.booksSaved ?? 0) / p.totalCount) * 100))
})

function stopCollectPoll(): void {
  if (collectTimer !== null) {
    window.clearTimeout(collectTimer)
    collectTimer = null
  }
}

function scheduleCollectPoll(booklistId: string): void {
  stopCollectPoll()
  if (!collectProgress.value?.running) {
    return
  }
  collectTimer = window.setTimeout(() => {
    void pollCollect(booklistId)
  }, POLL_MS)
}

async function pollCollect(booklistId: string): Promise<void> {
  try {
    const data = await getBooklistCollectProgress(booklistId)
    collectProgress.value = data
    if (!data.running) {
      if (data.errorMsg) {
        ElMessage.error(`采集失败：${data.errorMsg}`)
      } else {
        ElMessage.success('采集已结束')
      }
      void loadList()
    }
  } catch {
    // 拦截器已提示
  }
  scheduleCollectPoll(booklistId)
}

async function onCollect(row: Booklist): Promise<void> {
  // 二次确认：采集是后台长任务，避免误触
  try {
    await ElMessageBox.confirm(
      `将采集「${booklistDisplayName(row)}」里的全部书籍并写入图书库。` +
        '采集在后台进行，进度会在弹窗里实时显示。确定开始？',
      '同步书单',
      { type: 'warning', confirmButtonText: '开始同步', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  collectRow.value = row
  collectProgress.value = null
  collectVisible.value = true
  try {
    const data = await collectBooklist(row.booklistId)
    collectProgress.value = data
    // 元数据（名称/简介/作者/计数）在 collect 时已同步回填，立即刷新列表
    void loadList()
    scheduleCollectPoll(row.booklistId)
  } catch {
    // 拦截器已提示（已有任务在跑 / 目标限制等）
    collectVisible.value = false
  }
}

// ---------------------------------------------------------------------------
// 一键完善书单信息（2026-10-20）
// ---------------------------------------------------------------------------

const backfillVisible = ref(false)
const backfillProgress = ref<BooklistBackfillVO | null>(null)
let backfillTimer: number | null = null

const backfillPercent = computed(() => {
  const p = backfillProgress.value
  if (!p || p.total <= 0) {
    return 0
  }
  return Math.min(100, Math.round((p.processed / p.total) * 100))
})

function stopBackfillPoll(): void {
  if (backfillTimer !== null) {
    window.clearTimeout(backfillTimer)
    backfillTimer = null
  }
}

function scheduleBackfillPoll(): void {
  stopBackfillPoll()
  if (!backfillProgress.value?.running) {
    return
  }
  backfillTimer = window.setTimeout(() => {
    void pollBackfill()
  }, POLL_MS)
}

async function pollBackfill(): Promise<void> {
  try {
    const data = await getBooklistBackfillProgress()
    backfillProgress.value = data
    if (!data.running) {
      if (data.errorMsg) {
        ElMessage.error(`完善失败：${data.errorMsg}`)
      } else {
        ElMessage.success(`完善完成：成功 ${data.updated}，失败 ${data.failed}`)
      }
      void loadList()
    }
  } catch {
    // 拦截器已提示
  }
  scheduleBackfillPoll()
}

/** 进页面时恢复「完善书单信息」的进度（弹窗关掉 / 刷新后仍能看到） */
async function loadBackfillProgress(): Promise<void> {
  try {
    const data = await getBooklistBackfillProgress()
    if (data.startedAtMs) {
      backfillProgress.value = data
      scheduleBackfillPoll()
    }
  } catch {
    // 拿不到进度不影响页面
  }
}

async function onBackfill(): Promise<void> {
  // 二次确认：一键完善会发大量请求，避免误触
  try {
    await ElMessageBox.confirm(
      '将逐张刷新所有书单的元数据（名称/简介/作者/书籍数/收藏人数），已有数据会被覆盖更新。' +
        '按接口限速约 1 秒/张，数量多时需要几分钟，期间进度会实时显示。确定开始？',
      '完善书单信息',
      { type: 'warning', confirmButtonText: '开始完善', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  backfillVisible.value = true
  try {
    const data = await backfillBooklists()
    backfillProgress.value = data
    scheduleBackfillPoll()
  } catch {
    // 拦截器已提示（正在同步 / 正在完善）
    backfillVisible.value = false
  }
}

onMounted(async () => {
  await loadCategories()
  void loadCred() // 凭证表单放在本页最上面，进页面就要看到已存的值
  void loadList()
  void loadProgress()
  void loadBackfillProgress() // 弹窗关掉 / 刷新后仍能恢复「完善书单信息」的进度
})
onUnmounted(() => {
  stopPoll()
  stopCollectPoll()
  stopBackfillPoll()
})
</script>

<template>
  <div class="page">
    <!-- ================= 书单接口凭证（2026-10-15 从任务配置页移来，置顶） ================= -->
    <el-card shadow="never" class="cred-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">书单接口凭证</span>
          <el-tag v-if="!cred.booklistReady" type="warning" size="small" effect="plain">
            未配置完整
          </el-tag>
        </div>
      </template>

      <!--
        九项凭证，两列排布。字段元数据只在 script 里的 CRED_FIELDS 一份，
        模板按它渲染 —— 加字段改那一处就够。
      -->
      <el-form :model="credForm" label-width="0" class="cred-form" @submit.prevent>
        <div v-for="f in CRED_FIELDS" :key="f.key" class="cred-cell">
          <el-tooltip placement="top" :content="f.tip">
            <span class="cred-label">{{ f.label }}</span>
          </el-tooltip>
          <el-input
            v-model="credForm[f.key]"
            clearable
            :placeholder="f.placeholder"
            class="cred-input"
          />
        </div>
      </el-form>

      <div class="actions">
        <el-button type="warning" :loading="credSaving" @click="saveCred">
          <el-icon><Finished /></el-icon>
          保存
        </el-button>
        <span class="hint">
          保存后立即生效（不用重启）。八项缺任一不可用 —— 书单同步、书单采集都走这套凭证。
        </span>
      </div>

      <!--
        synckey 只读展示（2026-10-15 用户拍板：它不用配置）。
        后端在每次书单请求发出前用本地时间生成（new Date() 的秒值）——
        这行显示的是「最近一次请求用的值」。还没跑过书单采集时为空，
        不是错误。
      -->
      <div class="cred-auto">
        <span class="cred-auto-label">synckey（自动生成）</span>
        <span class="cred-auto-value text-mono">{{ synckeyHint }}</span>
      </div>
    </el-card>

    <!-- ================= 同步书单 ================= -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">同步书单（按平台分类发现）</span>
          <el-tag v-if="running" type="primary" effect="dark" size="small">
            <el-icon class="spin"><Loading /></el-icon>
            同步中
          </el-tag>
        </div>
      </template>

      <div class="cat-head">
        <span class="cat-label">平台分类（{{ categories.length }} 个）</span>
        <el-button-group>
          <el-button size="small" :disabled="running || allSelected" @click="selectAll">
            全选
          </el-button>
          <el-button
            size="small"
            :disabled="running || selectedCategoryIds.length === 0"
            @click="clearAll"
          >
            清空
          </el-button>
        </el-button-group>
      </div>

      <el-checkbox-group
        v-model="selectedCategoryIds"
        :disabled="running"
        class="cat-group"
      >
        <el-checkbox-button
          v-for="c in categories"
          :key="c.categoryId"
          :value="c.categoryId"
        >
          {{ c.title ?? c.categoryId }}
        </el-checkbox-button>
      </el-checkbox-group>

      <div class="actions">
        <el-button
          type="success"
          :loading="submitting"
          :disabled="running || selectedCategoryIds.length === 0"
          @click="onSync"
        >
          <el-icon><VideoPlay /></el-icon>
          开始同步
        </el-button>
        <el-button :loading="progressLoading" @click="loadProgress()">
          <el-icon><Refresh /></el-icon>
          {{ running ? '刷新进度' : '刷新' }}
        </el-button>
        <span class="hint">
          已勾选 {{ selectedCategoryIds.length }} / {{ categories.length }} 个分类
          <template v-if="running">· 同步进行中，可以离开这一页，回来看进度还在</template>
        </span>
      </div>

      <!--
        进度 / 上次结果。
        ⚠️ 条件是「在跑」**或**「跑过」—— 服务刚起来时后端也会返回一份「全零、未运行」的快照，
        无条件渲染会显示一个 0% 的进度条 + 空消息 + 绿色的「已完成」标签，
        看起来像「刚刚同步完了、一张都没采到」。
      -->
      <template v-if="progress && (running || progress.startedAtMs)">
        <el-progress
          :percentage="percent"
          :status="progress.errorMsg ? 'exception' : running ? undefined : 'success'"
          :stroke-width="14"
          class="progress-bar"
        />

        <div class="progress-msg">
          <el-tag
            :type="progress.errorMsg ? 'danger' : running ? 'primary' : 'success'"
            size="small"
            effect="plain"
          >
            {{ progress.errorMsg ? '失败' : running ? '进行中' : '已完成' }}
          </el-tag>
          <span>{{ dash(progress.message) }}</span>
        </div>

        <el-descriptions :column="4" size="small" border class="summary">
          <el-descriptions-item label="分类进度">
            {{ progress.categoriesDone }} / {{ progress.totalCategories }}
          </el-descriptions-item>
          <el-descriptions-item label="当前分类">
            {{ dash(progress.currentCategoryTitle) }}
          </el-descriptions-item>
          <el-descriptions-item label="请求页数">
            {{ formatNumber(progress.pagesFetched) }}
          </el-descriptions-item>
          <el-descriptions-item label="扫到书单">
            {{ formatNumber(progress.booklistsSeen) }}
          </el-descriptions-item>
          <el-descriptions-item label="入库（更新+新增）">
            {{ formatNumber(progress.booklistsSaved) }}
          </el-descriptions-item>
          <el-descriptions-item label="其中新增">
            <span class="num highlight">{{ formatNumber(progress.booklistsInserted) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="被闸门挡掉">
            {{ formatNumber(progress.booklistsSkipped) }}
          </el-descriptions-item>
          <!--
            正常应为 0。非 0 = 有分类一个书单都没返回 —— 这是「服务端返回空」
            与「该分类真的没书单」唯一的区分点，所以非 0 时标红。
          -->
          <el-descriptions-item label="第 1 页为空">
            <span
              class="num"
              :class="{ 'num-danger': (progress.emptyFirstPageCategories ?? 0) > 0 }"
            >
              {{ formatNumber(progress.emptyFirstPageCategories ?? 0) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="耗时区间">
            {{ formatDateTime(progress.startedAtMs) }}
            <template v-if="progress.finishedAtMs">
              ~ {{ formatDateTime(progress.finishedAtMs) }}
            </template>
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>

    <!-- ================= 书单列表 ================= -->
    <SearchForm
      :model="query"
      :loading="loading"
      :columns="4"
      label-width="100px"
      @search="handleSearch"
      @reset="handleReset"
    >
      <el-form-item label="关键词">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="书单名 / 书单 ID，模糊搜索"
        />
      </el-form-item>

      <el-form-item label="平台分类">
        <el-select v-model="query.categoryId" clearable placeholder="全部">
          <el-option
            v-for="c in categories"
            :key="c.categoryId"
            :label="`${c.title ?? c.categoryId}（${c.categoryId}）`"
            :value="c.categoryId"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="排序">
        <div class="range">
          <el-select v-model="query.sortField">
            <el-option
              v-for="opt in BOOKLIST_SORT_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-select v-model="query.asc" class="order-select">
            <el-option label="降序" :value="false" />
            <el-option label="升序" :value="true" />
          </el-select>
        </div>
      </el-form-item>

      <el-form-item label="收藏人数" class="span-2">
        <div class="range">
          <el-input-number
            v-model="query.minCollectCount"
            :min="0"
            :controls="false"
            placeholder="下限"
          />
          <span class="range-sep">~</span>
          <el-input-number
            v-model="query.maxCollectCount"
            :min="0"
            :controls="false"
            placeholder="上限"
          />
        </div>
      </el-form-item>

      <el-form-item label="书单书籍数" class="span-2">
        <div class="range">
          <el-input-number
            v-model="query.minBookCount"
            :min="0"
            :controls="false"
            placeholder="下限，不限则空"
          />
          <span class="range-sep">~</span>
          <el-input-number
            v-model="query.maxBookCount"
            :min="0"
            :controls="false"
            placeholder="上限"
          />
        </div>
      </el-form-item>

      <el-form-item label="名称状态">
        <el-checkbox v-model="query.nameMissing">只看待回填名称</el-checkbox>
      </el-form-item>
    </SearchForm>

    <el-card shadow="never" class="table-card">
      <!-- 完善书单信息：弹窗关闭后仍可在这里看进度 / 重新打开 -->
      <div
        v-if="backfillProgress && (backfillProgress.running || backfillProgress.startedAtMs)"
        class="task-strip"
      >
        <el-icon v-if="backfillProgress.running" class="spin"><Loading /></el-icon>
        <span class="strip-text">
          完善书单信息：{{ backfillProgress.processed }}/{{ backfillProgress.total }}
          （成功 {{ backfillProgress.updated }}，失败 {{ backfillProgress.failed }}）
        </span>
        <el-progress
          :percentage="backfillPercent"
          :status="backfillProgress.errorMsg ? 'exception' : backfillProgress.running ? undefined : 'success'"
          :stroke-width="8"
          class="strip-progress"
        />
        <el-button size="small" text type="primary" @click="backfillVisible = true">
          查看进度
        </el-button>
      </div>

      <!-- 同步书单书籍：弹窗关闭后仍可在这里看进度 / 重新打开 -->
      <div
        v-if="collectProgress && collectRow && (collectProgress.running || collectProgress.startedAt)"
        class="task-strip"
      >
        <el-icon v-if="collectProgress.running" class="spin"><Loading /></el-icon>
        <span class="strip-text">
          同步《{{ booklistDisplayName(collectRow) }}》：{{ collectProgress.statusLabel }}，
          已入库 {{ formatNumber(collectProgress.booksSaved) }}
        </span>
        <el-button size="small" text type="primary" @click="collectVisible = true">
          查看进度
        </el-button>
      </div>

      <ProTable
        :data="rows"
        :loading="loading"
        :total="total"
        :page="page"
        :size="size"
        :page-sizes="[20, 50, 100]"
        row-key="booklistId"
        empty-text="还没有书单。先在上面勾选平台分类并点「开始同步」。"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #toolbar>
          <el-button type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>
            新增书单
          </el-button>
          <el-button type="warning" plain @click="onBackfill">
            <el-icon><MagicStick /></el-icon>
            完善书单信息
          </el-button>
          <el-button :loading="loading" @click="loadList">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <span class="toolbar-info">
            共 {{ formatNumber(total) }} 张 · 当前按「{{
              BOOKLIST_SORT_OPTIONS.find((o) => o.value === query.sortField)?.label ?? '收藏人数'
            }}」{{ query.asc ? '升序' : '降序' }}
          </span>
        </template>

        <el-table-column label="书单名" min-width="230">
          <template #default="{ row }">
            <div class="name-line">
              <span class="name-text">{{ booklistDisplayName(row) }}</span>
              <el-tag
                v-if="isBooklistUnnamed(row)"
                size="small"
                type="warning"
                effect="plain"
              >
                待回填
              </el-tag>
            </div>
            <!-- 有名称时把 ID 放副行；没名称时上面那行显示的就是 ID，不用重复 -->
            <div v-if="!isBooklistUnnamed(row)" class="id-line text-mono">
              {{ row.booklistId }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="书籍数" width="82" align="right">
          <template #default="{ row }">
            <span class="num">{{ dash(row.bookCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="收藏人数" width="96" align="right">
          <template #default="{ row }">
            <span class="num" :class="{ 'num-weak': !collectCountReached(row) }">
              {{ dash(row.collectCount) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="平台分类" min-width="150">
          <template #default="{ row }">
            <template v-if="row.platformCategories && row.platformCategories.length">
              <el-tag
                v-for="c in row.platformCategories.slice(0, CAT_TAG_LIMIT)"
                :key="c.categoryId"
                size="small"
                effect="plain"
                class="cat-tag"
              >
                {{ c.title ?? c.categoryId }}
              </el-tag>
              <el-tooltip
                v-if="row.platformCategories.length > CAT_TAG_LIMIT"
                :content="categoryTooltip(row)"
                placement="top"
              >
                <el-tag size="small" type="info" effect="plain">
                  +{{ row.platformCategories.length - CAT_TAG_LIMIT }}
                </el-tag>
              </el-tooltip>
            </template>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column label="最近采集" width="150">
          <template #default="{ row }">{{ formatDateTime(row.lastCollectedAt) }}</template>
        </el-table-column>

        <el-table-column label="创建者" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ dash(row.authorName) }}</template>
        </el-table-column>

        <!-- 详情：看这张书单采到了哪些书；同步：采集这张书单里的书（带进度） -->
        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openDetail(row)">详情</el-button>
            <el-button size="small" text type="success" @click="onCollect(row)">同步</el-button>
          </template>
        </el-table-column>
      </ProTable>
    </el-card>

    <!-- 一键完善书单信息：进度实时刷新 -->
    <el-dialog v-model="backfillVisible" title="完善书单信息" width="480px">
      <template v-if="backfillProgress">
        <el-progress
          :percentage="backfillPercent"
          :status="backfillProgress.errorMsg ? 'exception' : backfillProgress.running ? undefined : 'success'"
          :stroke-width="14"
          class="progress-bar"
        />
        <div class="progress-msg">
          <el-tag
            :type="backfillProgress.errorMsg ? 'danger' : backfillProgress.running ? 'primary' : 'success'"
            size="small"
            effect="plain"
          >
            {{ backfillProgress.running ? '进行中' : '已完成' }}
          </el-tag>
          <span>{{ dash(backfillProgress.message) }}</span>
        </div>
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="总数">
            {{ formatNumber(backfillProgress.total) }}
          </el-descriptions-item>
          <el-descriptions-item label="已处理">
            {{ formatNumber(backfillProgress.processed) }}
          </el-descriptions-item>
          <el-descriptions-item label="成功">
            {{ formatNumber(backfillProgress.updated) }}
          </el-descriptions-item>
          <el-descriptions-item label="失败">
            {{ formatNumber(backfillProgress.failed) }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button @click="backfillVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新增书单 -->
    <el-dialog v-model="createVisible" title="新增书单" width="440px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="书单 ID" required>
          <el-input
            v-model="createForm.booklistId"
            clearable
            placeholder="形如 352003193_8cKur0WC3"
          />
        </el-form-item>
        <el-form-item label="书单名称">
          <el-input v-model="createForm.name" clearable placeholder="可空，后续采集时回填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSaving" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>

    <!-- 同步书单书籍：进度实时刷新 -->
    <el-dialog v-model="collectVisible" title="同步书单书籍" width="500px">
      <div v-if="collectRow" style="margin-bottom: 12px; font-weight: 600">
        {{ booklistDisplayName(collectRow) }}
      </div>
      <template v-if="collectProgress">
        <el-progress
          :percentage="collectPercent"
          :status="collectProgress.errorMsg ? 'exception' : collectProgress.running ? undefined : 'success'"
          :stroke-width="14"
          class="progress-bar"
        />
        <div class="progress-msg">
          <el-tag
            :type="collectProgress.errorMsg ? 'danger' : collectProgress.running ? 'primary' : 'success'"
            size="small"
            effect="plain"
          >
            {{ collectProgress.statusLabel }}
          </el-tag>
          <span>{{ dash(collectProgress.errorMsg) }}</span>
        </div>
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="已采页数">
            {{ formatNumber(collectProgress.pagesDone) }}
          </el-descriptions-item>
          <el-descriptions-item label="入库（更新+新增）">
            {{ formatNumber(collectProgress.booksSaved) }}
          </el-descriptions-item>
          <el-descriptions-item label="其中新增">
            {{ formatNumber(collectProgress.booksInserted) }}
          </el-descriptions-item>
          <el-descriptions-item label="被过滤">
            {{ formatNumber(collectProgress.booksExcluded) }}
          </el-descriptions-item>
          <el-descriptions-item label="声明书数">
            {{ formatNumber(collectProgress.totalCount) }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button @click="collectVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗单独一个组件：列表页已经很长，且弹窗内的分页/空态/头部账
         自成一套，塞回来只会两处互相干扰 -->
    <BooklistBooksDialog v-model="detailVisible" :booklist="detailBooklist" />
  </div>
</template>

<style scoped>
/* ---------------- 凭证表单 ---------------- */

/* 两列 grid：九个字段不换列也排得下；窄窗口由 el-input 自己收缩 */
.cred-form {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0 24px;
}

.cred-cell {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}

.cred-label {
  width: 110px;
  flex-shrink: 0;
  font-size: 13px;
  color: #606266;
  cursor: help;
}

.cred-input {
  flex: 1;
  min-width: 0;
}

.cred-card {
  margin-bottom: 16px;
}

/* synckey 只读展示行：弱化处理，不抢「保存」按钮的注意力 */
.cred-auto {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px dashed #ebeef5;
  font-size: 12px;
  color: #909399;
}

.cred-auto-label {
  flex-shrink: 0;
}

.cred-auto-value {
  color: #606266;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-title {
  font-weight: 600;
}

/* ---------------- 平台分类平铺 ---------------- */

.cat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.cat-label {
  font-size: 13px;
  color: #606266;
}

.cat-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* el-checkbox-button 之间默认有 margin-left，用 flex gap 之后要抹掉，否则间距翻倍 */
.cat-group :deep(.el-checkbox-button) {
  margin-left: 0;
}

.cat-group :deep(.el-checkbox-button__inner) {
  border-radius: 4px;
  border-left: 1px solid #dcdfe6;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}

.hint {
  font-size: 12px;
  color: #909399;
}

/* ---------------- 进度 ---------------- */

/* 列表上方的常驻任务条：弹窗关掉后仍能看到进度 */
.task-strip {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 8px;
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

.progress-bar {
  margin: 18px 0 10px;
}

.progress-msg {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  font-size: 13px;
  color: #606266;
}

.summary {
  margin-bottom: 4px;
}

.num {
  font-variant-numeric: tabular-nums;
}

.num.highlight,
.highlight {
  font-weight: 600;
  color: #409eff;
}

/* 收藏人数没到闸门（10000）的标灰 —— 一眼看出哪些是边缘数据 */
.num-weak {
  color: #a8abb2;
}

/* 「第 1 页为空」非 0 时标红 —— 正常应为 0，非 0 就是有分类一个书单都没返回 */
.num-danger {
  font-weight: 600;
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

/* ---------------- 表格 ---------------- */

.table-card :deep(.el-card__body) {
  padding: 8px 16px 16px;
}

.name-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.name-text {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.id-line {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
}

.cat-tag {
  margin-right: 4px;
}

.toolbar-info {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

/* ---------------- 筛选栏辅助 ---------------- */

.range {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.range-sep {
  color: #909399;
}

/*
 * ⚠️ 筛选栏是 4 列 Grid，`span-2` 让一项占两列。
 * `el-input-number` 的宽度在 SearchForm 里被设成 100%，
 * 两个并排时各占一半（`.range` 是 flex，gap 8px），与图书库页同口径。
 */
.span-2 {
  grid-column: span 2;
}

.order-select {
  max-width: 100px;
}
</style>
