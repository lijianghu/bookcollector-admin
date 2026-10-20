<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  batchDeleteInterpretations,
  getPublishJob,
  pageInterpretations,
  resetInterpretation,
} from '@/api/publish'
import { listTaxonomy } from '@/api/taxonomy'
import ProTable from '@/components/ProTable.vue'
import SearchForm from '@/components/SearchForm.vue'
import BookActionMenu from '@/components/BookActionMenu.vue'
import ConfigDialog from './ConfigDialog.vue'
import { useBookActions } from '@/composables/useBookActions'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import {
  INTERPRETATION_RESET_TARGETS,
  PUBLISH_STATUS_LABEL,
  PUBLISH_STATUS_TAG,
  type InterpretationResetTarget,
  type InterpretationSortField,
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
 * <h3>数据源没变，但接口早已不止「三个端点」</h3>
 * 数据源仍是 `book_interpretations`，列表仍是 `GET /api/publish/interpretations`。
 * ⚠️ 这段原文写的是「后端 `publish` 模块**一行没改**」—— **已过时**：
 * 2026-10-12 加了「精选配置」的 `POST /candidates/preview` / `collect`（端点 8 → 10），
 * 2026-10-13 又给列表加了服务端排序（`sortField` + `asc`）。
 * 本页已是 `publish` 模块的主要调用方，改它之前先看 `publish/AGENTS.md`。
 *
 * <h3>2026-10-13：6 个数值列可正反序排（默认推荐值降序）</h3>
 * 排序是**服务端**做的（`sortable="custom"` + `@sort-change` → `sortField` / `asc`），
 * 字段名过 `InterpretationQuery` 的白名单（不认识 → 回落推荐值）。
 * 本页**没有**程序化改排序的场景，所以不像图书库那样需要「换 key 重建表格」。
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

// ---------------------------------------------------------------------------
// 「合规替换」明细弹窗（2026-10-13）
//
// 那一列原来只显示「N 词」，看不到**替换了什么**。数据来自
// `InterpretationVO.sensitiveHits`（命中的**原词**列表）。
// ⚠️ 这里只有**最近一次**的替换词 —— `book_interpretations` 一本书只有一行、
// 状态会被覆盖；看历史去「发布记录」页（那边一次发布一条流水）。
// ⚠️ 2026-10-13 **之前**的记录没有这个字段（那时只存了个数）→ `null`，
// 弹窗里如实说明「只存了个数」，不是坏了。
// ---------------------------------------------------------------------------

const hitsVisible = ref(false)
const hitsRow = ref<InterpretationVO | null>(null)

function openHits(row: InterpretationVO): void {
  hitsRow.value = row
  hitsVisible.value = true
}

/** 弹窗里的词；历史记录（没有该字段）给空数组 */
const hitsWords = computed<string[]>(() => hitsRow.value?.sensitiveHits ?? [])

/** 弹窗标题：合规替换 ·《书名》 */
const hitsTitle = computed<string>(() => {
  const name = hitsRow.value?.title ?? hitsRow.value?.bookId
  return name ? `合规替换 ·《${name}》` : '合规替换'
})

/**
 * 排序。默认与后端 `InterpretationQuery` 的默认值一致：**推荐值降序**（用户 2026-10-13 指定）。
 *
 * <p>6 个数值列都支持正反序，排序是**服务端**做的（`sortable="custom"` + `@sort-change`）。
 * 这里只存字段名与方向，真正的 `Sort` 由后端按 `resolveSortPath()` 拼。
 */
const sortField = ref<InterpretationSortField>('newRating')
const sortAsc = ref(false)

/**
 * 表头排序箭头的数据源。
 *
 * <p>⚠️ `el-table` **只在挂载时读一次** `default-sort`，之后改它**不会更新箭头**。
 * 本页没有「程序化改排序」的场景（不像图书库有分类联动），所以不用换 key 重建表格；
 * 用户点表头时 el-table 自己管箭头，这里只负责把字段/方向同步给后端。
 */
const defaultSort = computed<{ prop: string; order: 'ascending' | 'descending' }>(() => ({
  prop: sortField.value,
  order: sortAsc.value ? 'ascending' : 'descending',
}))

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
 * <p>⚠️ 平台分类**两级**（2026-10-13 起，需求：「支持二级，同图书库的筛选」）：
 * 选一级发 `categoryId`、选到二级发 `subCategoryId`，**两者不会同时有值** ——
 * 由下面的级联框按选中层级拆解（见 `applyCategoryPath`）。
 */
const query = reactive<{
  status: PublishStatus | ''
  title?: string
  author?: string
  categoryId?: number
  subCategoryId?: number
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

/**
 * 「平台分类」级联树的节点。
 *
 * <p>⚠️ 二级分类的 `name` 自带父级前缀（如 `期刊杂志-财经`），展示时要剥掉，
 * 否则会显示成「期刊杂志 / 期刊杂志-财经」这种重复。同图书库。
 */
interface CategoryNode {
  value: number
  label: string
  children?: CategoryNode[]
}

/**
 * 级联框选中的路径。`[]` = 不筛；`[一级]` = 筛一级；`[一级, 二级]` = 筛二级。
 *
 * <p>用**本地 ref** 而不是直接绑 `query.categoryId`：级联的值是数组，
 * 而 `InterpretationQuery` 要的是两个标量字段，中间必须有一次「按层级拆解」的翻译
 * （见 `applyCategoryPath`）。同图书库。
 */
const categoryPath = ref<number[]>([])

/** 把级联框的原始值归一成 `number[]`（`null` / 非数组 / 被清空 → `[]`） */
function normalizeCategoryPath(value: unknown): number[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.map((v) => Number(v)).filter((v) => !Number.isNaN(v))
}

/**
 * 把选中路径落到 `query` 上。级联框的 `@change` 与「重置」共同调用它。
 *
 * <p>⚠️ 图书库那边同名的函数还会**联动排序**（选「期刊杂志」→ 出版时间倒序）。
 * 这里**刻意不做**：① 候选清单的排序白名单（`InterpretationQuery.SORT_PATHS`）
 * 里没有 `publishTime`；② 本页默认就是推荐值降序（用户 2026-10-13 指定），
 * 被分类联动冲掉反而违背那条默认。
 */
function applyCategoryPath(path: number[]): void {
  categoryPath.value = path
  if (path.length === 0) {
    query.categoryId = undefined
    query.subCategoryId = undefined
  } else if (path.length === 1) {
    query.categoryId = path[0]
    query.subCategoryId = undefined
  } else {
    query.categoryId = undefined
    query.subCategoryId = path[1]
  }
}

/**
 * 级联框实例。**只为了在一个地方调 `togglePopperVisible(false)`**。
 *
 * <p>⚠️ `el-cascader` 在 `checkStrictly` 模式下选中后**不会自动收起**下拉
 * （`cascader-panel` 的 `emitClose && !multiple && !checkStrictly` 条件里带
 * `!checkStrictly`），而为了保住「能选一级」的能力又必须开它。
 * 折中：**选到二级（叶子）时手动收起**；只选一级时保持展开，用户还要继续往下点。
 */
const cascaderRef = ref<{ togglePopperVisible?: (visible?: boolean) => void } | null>(null)

/** 级联框的 `@change`：归一 → 落 query → 回第 1 页重查 */
function onCategoryChange(value: unknown): void {
  const path = normalizeCategoryPath(value)
  applyCategoryPath(path)
  if (path.length >= 2) {
    cascaderRef.value?.togglePopperVisible?.(false)
  }
  page.value = 1
  void loadList()
}

/**
 * 「平台分类」的级联树：一级 + 挂在它下面的二级。
 * 顺序沿用字典返回的顺序（后端按 `sort` 排好，不在这里重排）。同图书库。
 */
const categoryTree = computed<CategoryNode[]>(() => {
  const byParent = new Map<string, TaxonomyItem[]>()
  categories.value.forEach((c) => {
    if (!c.parentCode) {
      return
    }
    const list = byParent.get(c.parentCode)
    if (list) {
      list.push(c)
    } else {
      byParent.set(c.parentCode, [c])
    }
  })
  return categories.value
    .filter((c) => !c.parentCode)
    .map((c) => {
      const node: CategoryNode = {
        value: Number(c.code),
        label: `${c.name}（${c.code}）`,
      }
      const prefix = `${c.name}-`
      const kids = (byParent.get(c.code) ?? []).map((k) => ({
        value: Number(k.code),
        // 剥掉父名前缀：`期刊杂志-财经` → `财经`
        label: `${k.name.startsWith(prefix) ? k.name.slice(prefix.length) : k.name}（${k.code}）`,
      }))
      if (kids.length > 0) {
        node.children = kids
      }
      return node
    })
})

/**
 * `el-cascader` 的固定属性。
 *
 * <p>🔴 `checkStrictly` + `checkOnClickNode` **两个都要开**：只开 `checkStrictly`
 * 时点一级只会展开、选不上（`checkOnClickNode` 默认 false）。
 * `emitPath: true` 必须保留 —— 靠路径长度区分选的是哪一级（同图书库）。
 */
const CASCADER_PROPS = {
  value: 'value',
  label: 'label',
  children: 'children',
  checkStrictly: true,
  checkOnClickNode: true,
  emitPath: true,
  // 2026-10-13：鼠标悬停到一级即展开二级，不选中（口径同图书库）。
  // 默认 'click' + `checkOnClickNode: true` 时，点一级会顺带选中它。
  expandTrigger: 'hover',
}

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
      sortField: sortField.value,
      asc: sortAsc.value,
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

/**
 * 行内「重置为」下拉（2026-10-16 由单一「重置」按钮演化）。
 *
 * <h3>为什么变成下拉</h3>
 * 原来只有「重置为待解读」一个动作。用户要的第二个动作是「标记为已发布」——
 * 场景是：这篇文章是手工发出去的（或早期用别的方式发过），清单里却还是
 * 「待解读 / 失败」，状态与实际不符，需要手工纠正。
 *
 * <p>两个目标共用一个按钮：下拉开销（一次点击 + 一次选择）能接受，
 * 而平铺两个按钮会把操作列撑宽（现在 150px 里还塞着 `BookActionMenu`）。
 *
 * <h3>🔴 文案必须按目标状态分开写</h3>
 * 「重置为待解读」和「标记为已发布」的后果完全不同，绝不能用同一句确认词：
 * <ul>
 *   <li><b>待解读</b>：下次挑书时它重新参与 → 如果已经发布过，
 *       重跑会在语雀里**新增**一篇（旧的不删）—— 这条必须讲出来；</li>
 *   <li><b>已发布</b>：只改清单状态，**不会**在语雀上产生文章、
 *       也不写发布记录流水 —— 不写清楚会让人以为「点一下就等于发了」。</li>
 * </ul>
 *
 * <p>而且两个方向都**不**碰时间字段（`finishedAt` / `publishedAt`）：
 * 标记状态不等于真的发过，写个假时间进去会污染「最近发布」这类统计。
 */
async function onResetTo(row: InterpretationVO, target: InterpretationResetTarget): Promise<void> {
  const label = PUBLISH_STATUS_LABEL[target]
  const bookTitle = `《${row.title ?? row.bookId}》`

  // —— 确认文案按目标状态分岔 ——
  let message: string
  if (target === 'PUBLISHED') {
    const wasPublished = row.status === 'PUBLISHED'
    message =
      `把 ${bookTitle} 标记为「已发布」？\n\n` +
      `⚠️ 这只改候选清单里的状态，**不会**在语雀上新建文章，` +
      `也不会写一条「发布记录」流水 —— 已发布的文档链接请另外维护。\n` +
      (wasPublished ? '（它当前已经是「已发布」）' : '')
  } else {
    // 已经发布过的重跑会在语雀里**新增**一篇（旧的那篇不会删）—— 必须讲出来
    const extra =
      row.status === 'PUBLISHED'
        ? '\n\n⚠️ 这本书已经发布过了。重新生成会在语雀里新增一篇文档，旧的那篇不会被删除。'
        : ''
    message = `把 ${bookTitle} 重置为「待解读」，下次挑书时它会重新参与。${extra}`
  }

  try {
    await ElMessageBox.confirm(message, `重置为${label}`, {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await resetInterpretation(row.bookId, target)
    ElMessage.success(`已重置为「${label}」`)
    await loadList()
  } catch {
    // 拦截器已提示
  }
}

/** 下拉选中项：el-dropdown 的 command 是字符串，收窄回目标状态类型 */
function onResetCommand(row: InterpretationVO, command: string): void {
  void onResetTo(row, command as InterpretationResetTarget)
}

// ---------------------------------------------------------------------------
// 精选配置（弹窗）
// ---------------------------------------------------------------------------

/**
 * 「精选配置」= 原「重置精选」，2026-10-12 改成弹窗（见 `ConfigDialog.vue`）。
 *
 * <p>变化：原来那个按钮是「按配置里的白名单全量重建候选池」，条件写死；
 * 现在由用户在弹窗里当场挑条件、先预览、再决定精选哪些。
 *
 * <p>🔴 **精选是「提交即返回」的后台长任务**：`collectCandidates()` 一返回只说明任务已受理，
 * 真正的进度要从 `getPublishJob()` 的 `initRunning / initScanned / initInserted / initSkipped`
 * 读 —— 这几个字段来自后端**内存**，所以刷新页面之后也接着看得到。
 *
 * <p>弹窗打开时由它自己轮询，本页负责「弹窗关掉之后」的收尾：
 * 用户很可能点完「全部精选」就关掉弹窗，这时进度得在页面上继续看、跑完得刷新列表。
 */

/** 弹窗可见性 */
const configVisible = ref(false)

/** 后台真的在跑（不是按钮的 loading：提交返回后它还可能是 true） */
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
      `精选完成：扫描 ${formatNumber(p?.scanned)} 本，新增 ${formatNumber(p?.inserted)} 条，` +
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
 * <p>精选可能是在**别的时刻**触发的（用户点完就关了弹窗、或者刷新了页面），
 * 不查一次的话进度会显示成没在跑，用户会以为任务丢了。
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

/**
 * 打开弹窗。
 *
 * <p>先停掉本页的轮询 —— 弹窗打开后由它自己轮询，
 * 两边同时轮询会让「完成」提示弹两次。
 */
function onOpenConfig(): void {
  stopResetPoll()
  configVisible.value = true
}

/**
 * 候选清单可能变了 —— 同步状态并（在没有任务跑时）局部刷新列表。
 *
 * <p>两个触发点：**弹窗关闭**、**弹窗里的精选跑完**。两种情况：
 * <ul>
 *   <li>没有任务在跑 → 直接刷新列表；</li>
 *   <li>任务还在跑 → `syncResetState()` 会接上轮询，跑完自动刷新。</li>
 * </ul>
 */
async function onCollected(): Promise<void> {
  await syncResetState()
  if (!resetRunning.value) {
    page.value = 1
    await loadList()
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
  // 「平台分类」走级联框，单独清：`applyCategoryPath([])` 会一并清掉 categoryId / subCategoryId
  applyCategoryPath([])
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

/**
 * el-table 的自定义排序回调（经 `ProTable` 转发）。
 *
 * <p>`order` 是 `'ascending' | 'descending' | null`，`null` 表示用户取消了排序 ——
 * 这时回到后端默认的「推荐值降序」，而不是「保持上一次的字段、只是不排」。
 *
 * <p>换排序要**回到第 1 页** —— 否则在第 5 页改排序会请求一个基于旧序的页码，看到的不是真正的第 5 页。
 */
function handleSortChange({ prop, order }: { prop: string | null; order: string | null }): void {
  if (!order || !prop) {
    sortField.value = 'newRating'
    sortAsc.value = false
  } else {
    sortField.value = prop as InterpretationSortField
    sortAsc.value = order === 'ascending'
  }
  page.value = 1
  void loadList()
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
    <!-- ============ 筛选 ============ -->
    <SearchForm
      :model="query"
      :loading="listLoading"
      :columns="4"
      label-width="100px"
      @search="handleSearch"
      @reset="handleReset"
    >
      <el-form-item label="发布状态">
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
        <el-cascader
          ref="cascaderRef"
          :model-value="categoryPath"
          :options="categoryTree"
          :props="CASCADER_PROPS"
          clearable
          filterable
          placeholder="全部（可选到二级分类）"
          class="category-cascader"
          @change="onCategoryChange"
        />
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
    </SearchForm>

    <!-- ============ 清单 ============ -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
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
        :default-sort="defaultSort"
        row-key="bookId"
        selectable
        empty-text="清单还是空的（或没有符合筛选条件的条目）。点上面的「精选配置」挑符合条件的图书进来。"
        @update:page="onPageChange"
        @update:size="onSizeChange"
        @sort-change="handleSortChange"
        @selection-change="handleSelectionChange"
      >
        <template #toolbar>
          <el-button type="primary" plain @click="onOpenConfig">
            <el-icon><Setting /></el-icon>
            精选配置
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
            正在精选：已扫描 {{ formatNumber(resetProgress?.scanned) }} 本，
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

        <el-table-column
          prop="newRating"
          label="推荐值"
          width="84"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">{{ ratingText(row.newRating) }}</template>
        </el-table-column>

        <!-- 评价标题（档位文案：神作 / 好评如潮 / …），2026-10-13 加。非数值列，不参与排序 -->
        <el-table-column prop="newRatingTitle" label="评价标题" width="84" show-overflow-tooltip>
          <template #default="{ row }">{{ dash(row.newRatingTitle) }}</template>
        </el-table-column>

        <el-table-column
          prop="newRatingCount"
          label="评价人数"
          width="96"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.newRatingCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          prop="readingCount"
          label="阅读人数"
          width="96"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.readingCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          prop="goodCount"
          label="好评数"
          width="84"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.goodCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          prop="fairCount"
          label="中评数"
          width="84"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.fairCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          prop="poorCount"
          label="差评数"
          width="84"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.poorCount) }}</span>
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
            <!-- 点开看**替换了哪些词**（2026-10-13）；没有命中的行不可点 -->
            <el-link
              v-if="row.sensitiveHitCount"
              type="primary"
              :underline="false"
              @click="openHits(row)"
            >
              {{ row.sensitiveHitCount }} 词
            </el-link>
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

        <!--
          操作列 168px：从 150 放宽，容纳「重置为 ▾」小按钮 + 「更多 ▾」链接按钮。
          （本表固定列合计就已超过屏宽，本来就有横向滚动；这里只是把 fixed-right
            区多占 18px，属于必要开销。）
        -->
        <el-table-column label="操作" width="168" align="center" fixed="right">
          <template #default="{ row }">
            <!--
              「重置为」下拉：待解读 / 已发布。
              当前状态那一项禁用（不能重复设成同一个值）—— 同 `BookActionMenu`
              里阅读状态三项的处理，避免用户点了个「什么都没发生」的动作。
            -->
            <el-dropdown
              trigger="click"
              :disabled="busy"
              @command="(cmd: string) => onResetCommand(row, cmd)"
            >
              <el-button size="small" :disabled="busy">
                重置为
                <el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="t in INTERPRETATION_RESET_TARGETS"
                    :key="t"
                    :command="t"
                    :disabled="row.status === t"
                  >
                    {{ row.status === t ? `当前：${PUBLISH_STATUS_LABEL[t]}` : PUBLISH_STATUS_LABEL[t] }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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

    <!-- 精选配置弹窗（原「重置精选」，2026-10-12 改） -->
    <ConfigDialog v-model="configVisible" @collected="onCollected" />

    <!--
      合规替换明细：点那一列弹出。数据是本次命中的敏感词**原词**列表
      （顺序 = 替换时的扫描顺序；同一个词出现多次会列多次）。
      ⚠️ 2026-10-13 之前的记录没有列表、只有个数 —— 走下面的空态说明。
    -->
    <el-dialog v-model="hitsVisible" :title="hitsTitle" width="520px">
      <template v-if="hitsWords.length">
        <div class="hits-hint">共 {{ hitsWords.length }} 处命中，按替换时的扫描顺序：</div>
        <div class="hits-list">
          <el-tag
            v-for="(w, i) in hitsWords"
            :key="`${w}-${i}`"
            size="small"
            type="warning"
            effect="plain"
          >
            {{ w }}
          </el-tag>
        </div>
      </template>
      <el-empty
        v-else
        :image-size="72"
        :description="hitsRow?.sensitiveHitCount
          ? '这条记录只存了替换词个数，没存具体词（2026-10-13 起才记录）'
          : '没有命中敏感词'"
      />
    </el-dialog>
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

/*
 * ⚠️ `el-cascader` 默认 `display: inline-block` 且**没有 width**
 * （Element Plus 2.14.6 的 `.el-cascader` 只有 `display:inline-block;position:relative`），
 * 放进表单会缩成内容宽度。给 100% 才和同排的 `el-select` / 数字框一样占满。
 */
.category-cascader {
  width: 100%;
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

/* 「合规替换」弹窗 */
.hits-hint {
  margin-bottom: 10px;
  font-size: 12px;
  color: #909399;
}

.hits-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 320px;
  overflow: auto;
}
</style>
