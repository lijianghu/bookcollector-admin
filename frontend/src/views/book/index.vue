<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  batchDeleteBooks,
  deleteBook,
  enrichBooks,
  getBook,
  getEnrichProgress,
  pageBooks,
} from '@/api/book'
import { listBooklistOptions } from '@/api/booklist'
import { listTaxonomy } from '@/api/taxonomy'
import ProTable from '@/components/ProTable.vue'
import SearchForm from '@/components/SearchForm.vue'
import DetailDrawer from '@/components/DetailDrawer.vue'
import BookActionMenu from '@/components/BookActionMenu.vue'
import { useColumnPrefs, type ColumnPref } from '@/composables/useColumnPrefs'
import { useBookActions } from '@/composables/useBookActions'
import { buildBookDetailSections, countDetailItems } from './detail-sections'
import BookEditDialog from './BookEditDialog.vue'
import { dash, formatDate, formatNumber, formatPublishDate } from '@/utils/format'
import {
  BOOK_MISSING_OPTIONS,
  targetTypeLabel,
  type Book,
  type BooklistOption,
  type BookMissingField,
  type BookQuery,
  type BookSortField,
  type EnrichResult,
  type TaxonomyItem,
} from '@/types'

/**
 * 图书库（S6.2）。
 *
 * <h3>R17 在这里兑现：44 字段的宽表不在列表页全展示</h3>
 * 接口一次返 54 个键。全铺到表格里既看不清也会卡（`el-table` 没有虚拟滚动）。
 * 所以列表只放 10 列左右，全部字段进详情抽屉（分 5 组折叠）。
 *
 * <h3>列配置为什么值得做</h3>
 * 不是「锦上添花的设置项」—— 11 列表格在 1440px 屏上放不下，必然横向滚动。
 * 与其让用户忍着滚，不如让他把不关心的列关掉。配置存 localStorage，
 * 按页面 key 隔离（`bookcollector_columns_books`）。
 *
 * <h3>三处「看起来多余但必须有」的处理</h3>
 * 1. **页码越界纠正**：在第 3 页删光之后 `page=3` 会返回空列表，
 *    用户以为「数据没了」。加载后如果 `page > 总页数` 就退回最后一页重来一次。
 * 2. **编辑成功后整页重拉，而不是就地改行**：因为改的可能正是**当前筛选字段**
 *    （比如正按作者筛选，把作者改成了别的），就地改行会让列表和筛选条件自相矛盾。
 * 3. **采集来源两个条件必须都选**：后端 `resolveSourceKey()` 只要缺一个就返回 null
 *    表示「不筛来源」—— 只选类型不选目标会**静默失效**，所以切换类型时清空目标。
 */

// ---------------------------------------------------------------------------
// 列配置
// ---------------------------------------------------------------------------

/**
 * 列定义。
 *
 * <h3>⚠️ 默认可见列的宽度总和必须放得下表格容器</h3>
 * 1440 宽的屏上，扣掉侧边栏 210 + 页面/卡片内边距 64，表格容器约 **1146px**。
 * 列宽总和一旦超过它，`el-table` 会横向滚动 —— 而 Element Plus 给表格的
 * 横向滚动条是 `el-scrollbar__wrap--hidden-default`（**默认隐藏**），
 * 用户不会知道右边还有列，只会觉得「采集来源那一列怎么没了」。
 *
 * 所以这里把「最近采集」「采集来源」设成默认不显示，并把其余列收紧到
 * 总和 ≈ **1142px**，留出余量。想要更多列就去「列配置」里打开 ——
 * 那时用户知道自己在做什么，也愿意横向滚。
 *
 * <p>2026-10-10 重新分配过一次：**「平台分类」150 → 176**（要放 category
 * 加上 `categories[].title` 的全部标签，窄了会把标签挤成两行以上），
 * 腾出的宽度来自 **「推荐值」88 → 78**（它改成百分比之后 `91.3%` 比 `913` 还短）。
 *
 * 这条约定有验收守着：`tools/s6-acceptance.mjs` 里断言
 * 「默认列下表格不出现横向滚动」+「表头没有一列换行」。
 */
const COLUMNS: ColumnPref[] = [
  { key: 'cover', label: '封面' },
  // 书名和操作固定：没有书名的表格没法认，没有操作列就点不动
  { key: 'title', label: '书名', fixed: true },
  { key: 'author', label: '作者' },
  { key: 'category', label: '平台分类' },
  { key: 'newRating', label: '推荐值' },
  // 2026-10-13 加、**默认显示**。它的 84px 是从下面几列各挤一点腾出来的（见列定义处的说明）
  { key: 'newRatingTitle', label: '评价标题' },
  { key: 'newRatingCount', label: '评价人数' },
  { key: 'readingCount', label: '阅读人数' },
  { key: 'publishTime', label: '出版时间' },
  // 下面两列默认不显示：它们是「想深入看的时候才需要」的信息，但很占地方
  { key: 'lastCollectedAt', label: '最近采集', defaultVisible: false },
  { key: 'collectSource', label: '采集来源', defaultVisible: false },
  // 2026-10-20：付费精选采集标记（paidCollect = 1）
  { key: 'paidCollect', label: '付费精选', defaultVisible: true },
  { key: 'actions', label: '操作', fixed: true },
]

const cols = useColumnPrefs('books', COLUMNS)

/**
 * 一次性迁移（2026-10-13）：新增的「评价标题」列要**默认显示**，
 * 但老用户的 localStorage 配置里没有这个 key —— `useColumnPrefs.load()` 的合并口径是
 * 「旧配置 ∩ 当前列清单」，缺的列**不会被补回来**，新列会静默不显示。
 *
 * <p>所以这里补一次并打标记：**只补一次**。之后用户主动关掉它不会被重开
 * （若每次都补，这一列就永远关不掉了）。
 */
const RATING_TITLE_MIGRATION_KEY = 'bookcollector_columns_books_newRatingTitle'
if (!localStorage.getItem(RATING_TITLE_MIGRATION_KEY)) {
  if (!cols.isVisible('newRatingTitle')) {
    cols.visible.value = [...cols.visible.value, 'newRatingTitle']
  }
  try {
    localStorage.setItem(RATING_TITLE_MIGRATION_KEY, '1')
  } catch {
    // 隐私模式下写不进去：退化成「每次进来补一次」= 这一列关不掉，不影响功能
  }
}

// 2026-10-20：新增「付费精选」列。老用户的 localStorage 里没有这个 key，
// 用同样的「只补一次」迁移把它补进来（之后用户可自己关掉）。
const PAID_COLLECT_MIGRATION_KEY = 'bookcollector_columns_books_paidCollect'
if (!localStorage.getItem(PAID_COLLECT_MIGRATION_KEY)) {
  if (!cols.isVisible('paidCollect')) {
    cols.visible.value = [...cols.visible.value, 'paidCollect']
  }
  try {
    localStorage.setItem(PAID_COLLECT_MIGRATION_KEY, '1')
  } catch {
    // 隐私模式：退化成每次进来补一次，不影响功能
  }
}

const columnPanelVisible = ref(false)

// ---------------------------------------------------------------------------
// 列表状态
// ---------------------------------------------------------------------------

const loading = ref(false)
const rows = ref<Book[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const selectedRows = ref<Book[]>([])
const deleting = ref(false)
/** 最新进度快照。null = 本次页面会话里还没提交过 */
const enrichProgress = ref<EnrichResult | null>(null)

/** 是否正在跑。模板据此禁用按钮、显示进度条 */
const enrichRunning = computed(() => enrichProgress.value?.running === true)

/** 进度百分比（0~100）。分母为 0 时给 0，别让 el-progress 拿到 NaN */
const enrichPercent = computed(() => {
  const p = enrichProgress.value
  if (!p || !p.requested) {
    return 0
  }
  return Math.min(100, Math.round((p.processed / p.requested) * 100))
})

/** 进度轮询的定时器句柄。用 setTimeout 自递归而不是 setInterval —— 请求慢时不会堆积 */
let enrichTimer: number | undefined

/** 上一次看到的 running。用来识别「刚刚跑完」这一次跃迁（只在那一刻提示 + 刷新列表） */
let enrichWasRunning = false

/** 排序。默认与后端 `BookQuery` 的默认值一致：推荐值降序 */
const sortField = ref<BookSortField>('newRating')
const sortAsc = ref(false)

/**
 * 表头排序箭头的数据源。
 *
 * <h3>🔴 为什么程序化改排序时必须「换 key 重建表格」</h3>
 * `el-table` **只在挂载时读一次** `default-sort`（Element Plus 2.14.6
 * `components/table/src/table-header/index.mjs` 的 `onMounted` 里执行
 * `store.commit('sort', {... init: true})`），之后改这个 prop **不会更新箭头**。
 * 于是「选期刊杂志 → 自动切出版时间倒序」这种程序化改排序，如果只改 `sortField`，
 * 表头箭头会指着「推荐值」而数据实际按「出版时间」排 —— 两边打架。
 * 解法见 `applySortForCategory()`：换 `tableKey` 让 ProTable 重建，箭头跟着重算。
 *
 * <p>带 `init: true` 的那次 commit **不会 emit `sort-change`**（实测 `store/index.mjs`
 * 的 `changeSortCondition` 里有 `options.init` 短路），所以重建**不会**绕回来触发
 * `handleSortChange` —— 既不会多打一次接口，也不会死循环。
 */
const defaultSort = computed<{ prop: string; order: 'ascending' | 'descending' }>(() => ({
  prop: sortField.value,
  order: sortAsc.value ? 'ascending' : 'descending',
}))

/**
 * ProTable 的 `:key`。**唯一用途**是「程序化改了排序时强制重建表格」，
 * 让表头箭头跟上实际排序（原因见 `defaultSort`）。
 *
 * ⚠️ 只在**分类联动真的改了排序**时 +1（见 `applySortForCategory`）；
 * 用户点表头改排序**不换 key** —— 那种情况 el-table 自己管箭头，重建反而会打架。
 * 重建会让 el-table 的内部选中态清空，所以联动里顺手把 `selectedRows` 也清掉，
 * 免得「批量删除」按钮拿着一批界面上根本没勾选的行。
 */
const tableKey = ref(0)

/**
 * 查询条件（**筛选部分**）。后端 `BookQuery` 一共 18 个参数，其余几个
 * （`sortField` / `asc` / `page` / `size`）在下面各自有独立的 ref。
 *
 * `categoryId` / `subCategoryId` 是**数字**（后端分别精确匹配 `categories.categoryId` /
 * `categories.subCategoryId`）。
 *
 * ⚠️ 后端的 `targetType` / `targetId`（按采集来源筛）**前端已不用** ——
 * 2026-10-12 用户把筛选栏里的「采集来源」项删了；后端接口仍然支持（`BookQuery` 未删），
 * 但这里不再带这两个字段。列表里的「采集来源」**列**是另一回事，仍在（读 `collectSource`）。
 *
 * ⚠️ `categoryId`（一级）与 `subCategoryId`（二级）**不会同时有值** ——
 * 由下面「平台分类」的级联框按选中的层级二选一发，见 `categoryPath` 的说明。
 *
 * `title` / `author` 是**包含匹配**（模糊搜索）—— 后端把它们编译成转义过的正则，
 * 用不上索引。这是 2026-10-10 用户明确要求的，代价已知并接受。
 *
 * <h3>两个时间区间的口径不一样（2026-10-10）</h3>
 * - `minPublishTime` / `maxPublishTime`：**年月**，格式 `yyyy-MM`。
 *   后端按**字符串字典序**比较（`publishTime` 在库里就是字符串，还有 `"2022"` 这种脏值）。
 * - `minCollectedAt` / `maxCollectedAt`：**年月日**，格式 `yyyy-MM-dd`。
 *   后端转成 `Date` 做真正的日期区间比较（`lastCollectedAt` 是 BSON date）。
 *   ⚠️ 上限**含当天整天**（后端补到 23:59:59.999）。
 *
 * <h3>`newRatingTitle`（2026-10-12 新增）</h3>
 * 筛的是**档位文案**（`神作` / `好评如潮` …），与 `minRating` / `maxRating` 的**数值**区间
 * 是两回事。候选项**写死**在下面的 `RATING_TITLE_OPTIONS`（用户 2026-10-12 选定不加接口）。
 */
const query = reactive<{
  categoryId?: number
  subCategoryId?: number
  minRating?: number
  maxRating?: number
  minReadingCount?: number
  newRatingTitle?: string
  title?: string
  author?: string
  minPublishTime?: string
  maxPublishTime?: string
  minCollectedAt?: string
  maxCollectedAt?: string
  /** 缺哪个字段（2026-10-16）。取值见 BOOK_MISSING_OPTIONS；不传 = 不筛这一项 */
  missing?: BookMissingField
  /** 书单（2026-10-16）。值来自 /api/booklists/options */
  booklistId?: string
  /** 字数下限（2026-10-16）。后端语义是 `totalWords >= 这个值` */
  minTotalWords?: number
  /** 补齐状态（2026-10-16）。true = 补过，false = 从没补过，不传 = 不筛这一项 */
  enriched?: boolean
  /** 付费精选（2026-10-20）。true = 只要付费精选，false = 排除，不传 = 不筛这一项 */
  paidCollect?: boolean
}>({})

/**
 * 书单下拉选项（2026-10-16）。
 *
 * 一次性拉全量 —— 后端 `/api/booklists/options` 不分页、只投影 `booklistId` + `name`。
 * 名称还没回填时用 `booklistId` 兜底显示：下拉里不能出现空白项。
 */
const booklistOptions = ref<BooklistOption[]>([])

async function loadBooklistOptions(): Promise<void> {
  try {
    booklistOptions.value = await listBooklistOptions()
  } catch {
    // 拦截器已提示；下拉为空不影响其它筛选
  }
}

/**
 * 「评价标题」（`books.newRatingTitle`）的候选项。
 *
 * <h3>🔴 为什么写死在这里，而不是加个接口</h3>
 * 用户 2026-10-12 明确选了「前端写死」。**代价已知**：阅读平台新增档位时这里会漏
 * —— 2026-10-12 实测库里就多出一个上次调研没有的 `烂书一本`（2 本）。
 * 要改成数据驱动，得加 `GET /api/books/rating-titles`（对 `books` 做 `distinct` 聚合）。
 *
 * <p>⚠️ 顺序是用户 2026-10-12 **手工重排**的，**不再是**「库里数量降序」—— 现在按档位从高到低
 * （值得一读 → 好评如潮 → 脍炙人口 → 神作潜力 → 神作 → 褒贬不一 → 不值一读 → 烂书一本），
 * 最后三项 `评分不足` / `待评分` / `暂无评级` 是**没有档位**的状态，收尾。
 * （库里数量参考：评分不足 14672 / 待评分 4179 / 值得一读 2334 / 好评如潮 1869 / 脍炙人口 1596 /
 * 神作潜力 1233 / 神作 1146 / 褒贬不一 633 / 不值一读 23 / 烂书一本 2）
 *
 * <p>⚠️ 「暂无评级」那一项的 value 是**哨兵**，不是真实档位：库里 46%（23678 本）**没有**
 * 这个字段，后端 `BookRepository.buildCriteria` 见到哨兵会把条件拼成「字段为 null 或不存在」。
 */
const RATING_TITLE_NONE = '__NONE__'

const RATING_TITLE_OPTIONS: Array<{ label: string; value: string }> = [
  { label: '值得一读', value: '值得一读' },
  { label: '好评如潮', value: '好评如潮' },
  { label: '脍炙人口', value: '脍炙人口' },
  { label: '神作潜力', value: '神作潜力' },
  { label: '神作', value: '神作' },
  { label: '褒贬不一', value: '褒贬不一' },
  { label: '不值一读', value: '不值一读' },
  { label: '烂书一本', value: '烂书一本' },
  { label: '评分不足', value: '评分不足' },
  { label: '待评分', value: '待评分' },
  { label: '暂无评级', value: RATING_TITLE_NONE },
]

/** 字典：分类与榜单。用于筛选栏的下拉框，也用于把 sourceKey 翻译成人话 */
const categories = ref<TaxonomyItem[]>([])
const rankings = ref<TaxonomyItem[]>([])

/**
 * 「平台分类」级联框 —— **一个框、两级**（2026-10-12 改造）。
 *
 * <h3>为什么必须是级联，而不是「把二级也塞进原来的单选下拉」</h3>
 * 后端一级分类匹配 `categories.categoryId`、二级分类匹配 `categories.subCategoryId`
 * —— **两个不同的键**。旧实现只列一级，正是因为把二级 code 塞进 `categoryId` 会
 * **恒不匹配、静默返回 0 条**（不报错），用户只会以为「这个分类没数据」。
 * 级联框化解了这个矛盾：**选中的层级本身就是信息**，一级发 `categoryId`、二级发 `subCategoryId`。
 *
 * <h3>⚠️ 二级分类的 `name` 自带父级前缀，展示时要剥掉</h3>
 * 字典里二级的 `name` 是「一级-二级」形式（如 `期刊杂志-财经`；2026-10-12 实测 166 条
 * **全部**如此）。直接用会显示成「期刊杂志 / 期刊杂志-财经」这种重复，所以剥掉 `${父名}-`。
 */
interface CategoryNode {
  value: number
  label: string
  children?: CategoryNode[]
}

/**
 * 级联框的字段映射。
 *
 * - **`checkStrictly: true` —— 允许只选一级**。选一级 = 筛该分类下的全部书（含它的二级），
 *   这是改造前 `el-select` 的既有行为，不能丢（后端按 `categories.categoryId` 本来就能带出二级的书）。
 * - **`checkOnClickNode: true` —— 必须显式开**。⚠️ 实测 Element Plus 2.14.6 的
 *   `cascader-panel/src/node.vue` 里 `handleClick` 是：
 *   `isLeaf && !checkStrictly && !multiple` → 选中；否则要求
 *   `checkOnClickNode && (multiple || checkStrictly)`；**再否则只展开、不选中**。
 *   而 `checkOnClickNode` 默认是 **false** → **光开 `checkStrictly` 是选不中一级分类的**
 *   （点「文学」只会展开，选不上）。两个都要开。
 * - **`emitPath: true`（默认）—— 必须拿完整路径**。靠路径长度区分选的是哪一级：
 *   `[一级]` → 发 `categoryId`；`[一级, 二级]` → 发 `subCategoryId`。
 *   若改成 `emitPath: false` 只拿末级 code，就再也分不清 `1700000` 是「一级被选中」
 *   还是「某个二级恰好等于它」了。
 */
const CASCADER_PROPS = {
  value: 'value',
  label: 'label',
  children: 'children',
  checkStrictly: true,
  checkOnClickNode: true,
  emitPath: true,
  // 2026-10-13：鼠标**悬停**到一级就展开二级，且**不选中任何一项**。
  // 默认值是 'click' —— 本项目 `checkOnClickNode: true`，点一级会**选中**它，
  // 于是「只想看看二级有哪些」必须先点一下（顺带把筛选条件改了），不好用。
  // 改成 hover 后：悬停 = 只展开（node.vue 的 handleHoverExpand → handleExpand），
  // 点击仍然照旧选中（handleClick 里 `checkOnClickNode && checkStrictly` 分支）。
  expandTrigger: 'hover',
}

/** 「期刊杂志」的一级 code（后端 `CollectLoop.JOURNAL_CATEGORY_ID` 也是这个值） */
const JOURNAL_CATEGORY_ID = 1700000

/**
 * 「平台分类」的级联树：一级 + 挂在它下面的二级。
 * 顺序沿用字典返回的顺序（后端按 `sort` 排好，不在这里重排）。
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
 * 级联框选中的路径。`[]` = 不筛；`[一级]` = 筛一级；`[一级, 二级]` = 筛二级。
 *
 * 用**本地 ref** 而不是直接绑 `query.categoryId`：级联的值是个数组，
 * 而 `BookQuery` 要的是两个标量字段，中间必须有一次「按层级拆解」的翻译（见 `applyCategoryPath`）。
 *
 * ⚠️ 这里刻意**不用 `v-model`** —— 级联的 `update:modelValue` 载荷类型是
 * `CascaderValue | null | undefined`（含 string / number / 对象），绑到 `number[]` 上过不了 `vue-tsc`。
 * 改成 `:model-value` + `@change` 单向绑定，等价且类型安全。
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
 * 把「平台分类」的选中路径落到 `query` 上，并做排序联动。级联框的 `@change`
 * 与「重置」共同调用它。
 *
 * <h3>排序联动（2026-10-12 需求）—— 三个口径由用户拍板</h3>
 * 1. **一级和二级都触发**：选「期刊杂志」（1700000）或它下面任意二级分类
 *    （财经 / 生活 / 文学 / 其他）都算；
 * 2. **切走恢复默认**：换成别的分类或清空，排序回到「推荐值降序」（= 后端默认值）；
 * 3. **允许手动覆盖**：联动只在**分类变化这一刻**生效一次，之后用户点表头可自由改
 *    —— 所以这里是「设置一次」，不是「锁死」。
 *
 * <p>口径 1 / 2 的落地在 `applySortForCategory()`：它**只在期刊状态翻转时**才动手，
 * 所以「从没碰过期刊杂志 → 点重置」不会把用户手动选的排序冲掉。
 */
function applyCategoryPath(path: number[]): void {
  categoryPath.value = path
  const journal = (path[0] ?? -1) === JOURNAL_CATEGORY_ID
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
  applySortForCategory(journal)
}

/**
 * 级联框实例。**只为了在一个地方调 `togglePopperVisible(false)`**。
 *
 * <h3>⚠️ 为什么必须手动收起</h3>
 * `el-cascader` 在 `checkStrictly` 模式下**选中后不会自动收起下拉** ——
 * `cascader-panel` 的 `handleCheckChange` 里那行是
 * `emitClose && !multiple && !checkStrictly && emit("close")`，**条件里带 `!checkStrictly`**。
 * 而为了保住「能选一级」的能力，我们又必须开 `checkStrictly`。
 * 折中：**选到二级（叶子）时手动收起**；只选了一级时**保持展开**，用户还要继续往下点。
 *
 * <p>类型写成结构化的最小接口、而不是 `InstanceType<typeof ElCascader>`：
 * 只为调一个方法，没必要把整个组件类型拖进来。
 */
const cascaderRef = ref<{ togglePopperVisible?: (visible?: boolean) => void } | null>(null)

/** 级联框的 `@change`：归一 → 落 query → 联动排序 → 回第 1 页重查 */
function onCategoryChange(value: unknown): void {
  const path = normalizeCategoryPath(value)
  applyCategoryPath(path)
  // 选到二级（叶子）→ 收起下拉（原因见 cascaderRef）；只选一级时保持展开，方便继续往下点
  if (path.length >= 2) {
    cascaderRef.value?.togglePopperVisible?.(false)
  }
  page.value = 1
  scheduleReload()
}

/**
 * 「上一次的分类是不是期刊杂志」。用来判断**期刊状态有没有真的翻转** ——
 * 排序联动只在**翻转的那一刻**生效一次（口径 3：之后允许用户手动改）。
 *
 * <p>⚠️ 为什么要有这个闸，而不是「每次分类变化都按当前分类设一遍排序」：
 * 那样的话，用户从没碰过期刊杂志、只是手动按「阅读人数」排了序，
 * 点一下「重置」（会走到 `applyCategoryPath([])`）就会被无声地把排序冲回推荐值降序
 * —— 那不是需求 2 说的场景（口径 2 讲的是「**从期刊杂志切走**」）。
 */
let lastJournal = false

/**
 * 按「是不是期刊杂志」把排序设成出版时间倒序 / 推荐值降序。
 *
 * <p>**只在期刊状态翻转时动手**（见 `lastJournal`）：选「期刊杂志」→ 出版时间倒序；
 * 从它切到别的分类或清空 → 推荐值降序。用户在这之后手动点表头改排序，不会被冲掉。
 *
 * <p>只有**排序真的变了**才换 `tableKey` 重建表格 —— 来回切同一个分类
 * （或本来就在默认排序上）不该白白重建一次。
 *
 * <p>重建会清掉 el-table 的内部选中态，所以顺手清空 `selectedRows`：
 * 否则「批量删除（N）」会拿着一批界面上根本没勾选的行。这与 ProTable
 * 「翻页不保留选中」的既有取舍一致。
 */
function applySortForCategory(journal: boolean): void {
  if (journal === lastJournal) {
    return
  }
  lastJournal = journal
  const nextField: BookSortField = journal ? 'publishTime' : 'newRating'
  const nextAsc = false
  if (sortField.value === nextField && sortAsc.value === nextAsc) {
    return
  }
  sortField.value = nextField
  sortAsc.value = nextAsc
  selectedRows.value = []
  tableKey.value += 1
}

/*
 * ⚠️ 2026-10-12：用户把筛选栏里的「采集来源」项删了（他不关心 `collectSource`），
 * 只服务于它的 `targetOptions`（目标下拉候选）与 `handleTargetTypeChange`（切类型清目标）
 * 一并删除 —— 留着会被 `noUnusedLocals` 判为未使用，前端直接编译不过。
 * 2026-10-13：`query.targetType` / `query.targetId` 也一并删了 —— 它们**只写不读**，
 * 且 `toParams()` 会把空串 / undefined 跳过，所以从来没被发出去过（确认是死代码）。
 */

/** `sourceKey`（"category:300000"）→ 「分类 · 文学」这样的人话 */
function sourceText(sourceKey?: string | null): string {
  if (!sourceKey) {
    return '-'
  }
  const idx = sourceKey.indexOf(':')
  const type = idx > 0 ? sourceKey.slice(0, idx).toUpperCase() : ''
  const code = idx > 0 ? sourceKey.slice(idx + 1) : sourceKey
  // ⚠️ 字典只有分类 / 榜单两种。不要用「不是榜单就当分类」的二元写法 ——
  //    那样新增类型（如书单）时，它的来源会被静默显示成「分类」。
  //    未知前缀（如 booklist）查不到字典，就原样回显 sourceKey，不假装知道。
  const dict =
    type === 'RANKING' ? rankings.value : type === 'CATEGORY' ? categories.value : []
  const hit = dict.find((t) => t.code === code)
  const typeName = targetTypeLabel(type)
  return hit ? `${typeName} · ${hit.name}` : sourceKey
}

function sourceList(sourceKeys?: string[] | null): string[] {
  if (!Array.isArray(sourceKeys) || sourceKeys.length === 0) {
    return []
  }
  return sourceKeys.map(sourceText)
}

/**
 * 推荐值 0~1000 → 百分比文案。`913` → `"91.3%"`。
 *
 * <p>口径与「发布配置」「发布记录」「藏书阁抽屉」保持一致（那三处各自有一份同名实现，
 * 格式都是 `(v / 10).toFixed(1) + '%'`）。图书库原来直接显示 0~1000 的原始值，
 * 是唯一不一致的一处。
 *
 * <p>⚠️ **只改展示，不改筛选**：`BookQuery.minRating / maxRating` 仍然是 0~1000
 * （后端 `newRating` 就是这个量纲），筛选栏也保持原样 —— 和「发布配置」的行为一致。
 */
function ratingText(v?: number | null): string {
  return v === null || v === undefined ? '-' : `${(v / 10).toFixed(1)}%`
}

/**
 * `categories[]` 里除首项之外的分类标题。
 *
 * <p>为什么去掉首项：`Book.category`（字符串）**恒等于 `categories[0].title`**
 * （已对全库 51352 本逐条核对，不一致 0 本）。首项已经在上面那行显示过了，
 * 再列一遍就是同一个标签出现两次。所以这一列 = 「category 主行 + 其余标签」。
 *
 * <p>顺带按出现顺序去重：平台偶尔会把同一个 title 打两遍。
 */
function extraCategoryTitles(row: Book): string[] {
  if (!Array.isArray(row.categories)) {
    return []
  }
  const seen = new Set<string>()
  const out: string[] = []
  for (const c of row.categories) {
    const t = c?.title
    if (!t || t === row.category || seen.has(t)) {
      continue
    }
    seen.add(t)
    out.push(t)
  }
  return out
}

// ---------------------------------------------------------------------------
// 加载
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 藏书 / 阅读清单：两个独立维度（决定操作列「更多」下拉里显示什么）
// ---------------------------------------------------------------------------

/**
 * 两个维度的状态与五个动作 —— 与精选图书 / 我的藏书 / 阅读清单**同一套实现**。
 *
 * <p>🔴 收藏（`shelf_books`）与阅读计划（`reading_list`）**不可互相推导**：
 * 这里只分别读「在不在藏书阁」与「阅读状态」，不会用其中一个去算另一个。
 *
 * <p>本页列的是**全部图书**，与两个维度都无关，所以移除后**不需要**从表格里去掉行
 * —— 两个回调都不传。
 */
const {
  busy: actionBusy,
  isOnShelf,
  readingStatusOf,
  loadStatus,
  toggleShelf,
  toggleReadingList,
  setReadingStatus,
} = useBookActions()

/** 防止「页码越界纠正」反复触发 */
let correcting = false

async function load(): Promise<void> {
  loading.value = true
  try {
    const payload: BookQuery = {
      ...query,
      sortField: sortField.value,
      asc: sortAsc.value,
      page: page.value,
      size: size.value,
    }
    const result = await pageBooks(payload)
    rows.value = result.list
    total.value = result.total
    // 两个维度（藏书 / 阅读清单）的状态跟着这一页一起刷新；失败各自兜底，不影响列表
    await loadStatus(result.list)

    // 页码越界纠正：在第 3 页删光后 page=3 会返回空列表，用户以为数据没了。
    // 退回最后一页重来一次（只做一次，避免死循环）。
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

async function loadDict(): Promise<void> {
  try {
    const [cats, ranks] = await Promise.all([
      listTaxonomy('CATEGORY'),
      listTaxonomy('RANKING'),
    ])
    categories.value = cats
    rankings.value = ranks
  } catch {
    // 字典拉不到不影响列表本身，下拉框空着即可
    categories.value = []
    rankings.value = []
  }
}

/**
 * 合并同一轮事件里的多次加载请求。
 *
 * 改每页条数时 ProTable 会**同步**先 emit `update:size` 再 emit `update:page(1)`，
 * 两个事件各自都会请求一次数据 —— 不合并就是两次一模一样的请求，
 * 而且它们的**返回顺序不保证**：后发的先回来就会把先发的结果覆盖掉。
 *
 * 用微任务攒一下：两个事件在同一个调用栈里跑完，微任务才执行，于是只请求一次。
 */
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
  // 「平台分类」走级联框，单独清：`applyCategoryPath([])` 会一并清掉 categoryId / subCategoryId，
  // 并且**如果之前选的是期刊杂志**，还会按「切走恢复默认排序」把排序复位
  // （2026-10-12 需求 2 的口径 2）。从来没碰过期刊杂志时，排序保持用户手动选的那个。
  applyCategoryPath([])
  query.minRating = undefined
  query.maxRating = undefined
  query.minReadingCount = undefined
  query.newRatingTitle = undefined
  query.title = undefined
  query.author = undefined
  query.minPublishTime = undefined
  query.maxPublishTime = undefined
  query.minCollectedAt = undefined
  query.maxCollectedAt = undefined
  query.missing = undefined
  query.booklistId = undefined
  query.minTotalWords = undefined
  query.enriched = undefined
  query.paidCollect = undefined
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

function handleSelectionChange(list: unknown[]): void {
  selectedRows.value = list as Book[]
}

/** 排序字段 → 表头的中文名，给工具栏那行说明用 */
const SORT_LABELS: Record<BookSortField, string> = {
  newRating: '推荐值',
  newRatingCount: '评价人数',
  readingCount: '阅读人数',
  publishTime: '出版时间',
  lastCollectedAt: '最近采集',
}

/**
 * el-table 的自定义排序回调。
 *
 * `order` 是 `'ascending' | 'descending' | null`，`null` 表示用户取消了排序 ——
 * 这时要回到后端默认的「推荐值降序」，而不是「保持上一次的字段、只是不排」。
 */
function handleSortChange({ prop, order }: { prop: string | null; order: string | null }): void {
  if (!order || !prop) {
    sortField.value = 'newRating'
    sortAsc.value = false
  } else {
    sortField.value = prop as BookSortField
    sortAsc.value = order === 'ascending'
  }
  page.value = 1
  scheduleReload()
}

// ---------------------------------------------------------------------------
// 详情抽屉
// ---------------------------------------------------------------------------

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailBook = ref<Book | null>(null)

const detailSections = computed(() =>
  detailBook.value ? buildBookDetailSections(detailBook.value) : [],
)
const detailItemCount = computed(() => countDetailItems(detailSections.value))

/**
 * 打开详情时**重新拉一次详情接口**，而不是直接用列表里那一行。
 *
 * 列表里的 `Book` 字段其实也是全的（54 个键都在），但可能是几分钟前拉的。
 * 重新拉一次的成本是一个请求，换来的是「抽屉里看到的和库里一致」。
 */
async function openDetail(book: Book): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detailBook.value = book
  try {
    detailBook.value = await getBook(book.bookId)
  } catch {
    // 拉失败就继续用列表里那份，至少不是空白
  } finally {
    detailLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 编辑
// ---------------------------------------------------------------------------

const editVisible = ref(false)
const editBook = ref<Book | null>(null)

/**
 * 打开编辑弹窗。
 *
 * 优先用抽屉里那份（它是刚从详情接口拉的，最新），其次才用列表那一行。
 * 不这么做的话会出现「抽屉里显示作者是 A，点编辑打开的表单里是 B」。
 */
function openEdit(book: Book): void {
  editBook.value =
    detailBook.value && detailBook.value.bookId === book.bookId ? detailBook.value : book
  editVisible.value = true
}

/**
 * 保存成功后的处理：重拉列表 + 刷新抽屉。
 *
 * 为什么不是「就地替换那一行」：改的可能正是当前筛选字段（比如正按作者筛选，
 * 把作者改掉了）。就地改行会让列表里留着一行**不满足筛选条件**的数据，
 * 而用户完全看不出来 —— 下次刷新它就凭空消失。
 * 重拉一次代价很小，换来「列表永远和筛选条件自洽」。
 */
async function handleSaved(updated: Book): Promise<void> {
  if (detailBook.value && detailBook.value.bookId === updated.bookId) {
    detailBook.value = updated
  }
  await load()
}

// ---------------------------------------------------------------------------
// 删除
// ---------------------------------------------------------------------------

async function confirmDelete(book: Book): Promise<void> {
  try {
    // ⚠️ ElMessageBox 默认不解析 HTML，`\n` 和 markdown 都不会换行/加粗 ——
    // 想用富文本得开 dangerouslyUseHTMLString，而这本书的书名是**接口数据**，
    // 拿它拼 HTML 就是一个 XSS 面。所以这里只说人话，不用富文本。
    await ElMessageBox.confirm(
      `确定删除《${book.title ?? book.bookId}》吗？` +
        `注意：下次采集到这本书时它会被重新写入，删除不是永久拉黑。`,
      '删除图书',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    // 用户点了取消
    return
  }
  deleting.value = true
  try {
    const result = await deleteBook(book.bookId)
    ElMessage.success(`已删除 ${result.deleted} 本`)
    if (detailBook.value?.bookId === book.bookId) {
      detailVisible.value = false
      detailBook.value = null
    }
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    deleting.value = false
  }
}

async function confirmBatchDelete(): Promise<void> {
  const count = selectedRows.value.length
  if (count === 0) {
    return
  }
  const titles = selectedRows.value
    .slice(0, 5)
    .map((b) => `《${b.title ?? b.bookId}》`)
    .join('、')
  const more = count > 5 ? ` 等 ${count} 本` : ''
  try {
    await ElMessageBox.confirm(
      `确定删除 ${titles}${more}吗？共 ${count} 本。` +
        `注意：下次采集到这些书时它们会被重新写入。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  deleting.value = true
  try {
    const result = await batchDeleteBooks(selectedRows.value.map((b) => b.bookId))
    // 返回的是**实际**删除数，可能小于请求数（有的 bookId 已经不在了）
    const missed = result.requested - result.deleted
    ElMessage.success(
      missed > 0
        ? `请求删除 ${result.requested} 本，实际删除 ${result.deleted} 本（${missed} 本已不存在）`
        : `已删除 ${result.deleted} 本`,
    )
    selectedRows.value = []
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    deleting.value = false
  }
}

/**
 * 「完善书籍信息」（2026-10-16）。
 *
 * 用法：先用筛选条件「缺失信息」把缺字段的书筛出来 → 勾选当页 → 点这个按钮。
 *
 * ⚠️ 后端是**同步**长耗时调用（5 并发 + 每请求随机等 1~5 秒），一页可能要一两分钟。
 * 所以既给 loading，也在确认框里提前讲清楚，免得用户以为卡死了。
 */
async function confirmEnrich(): Promise<void> {
  const count = selectedRows.value.length
  if (count === 0) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将用平台的「书详情」接口补齐这 ${count} 本书的简介、分类等信息。` +
        `只补空缺字段、不覆盖已有内容；只处理「采集时间是今天」的书，其余跳过。` +
        `后端 5 并发、每个请求间隔 1~5 秒随机，可能要几分钟 —— 可以离开这一页，回来看进度还在。`,
      '完善书籍信息',
      { type: 'info', confirmButtonText: '开始补齐', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    // ⚠️ 提交即返回（不等它跑完），随后靠轮询看进度
    enrichProgress.value = await enrichBooks(selectedRows.value.map((b) => b.bookId))
    selectedRows.value = []
    startEnrichPolling()
  } catch {
    // 拦截器已提示
  }
}

function stopEnrichPolling(): void {
  if (enrichTimer !== undefined) {
    window.clearTimeout(enrichTimer)
    enrichTimer = undefined
  }
}

/**
 * 拉一次进度；还在跑就 1.5 秒后再拉。
 *
 * 照 `views/booklist/index.vue` 同步书单那套 —— 是否继续轮询完全看后端的 `running`，
 * 前端不自己猜时长。
 */
async function pollEnrich(): Promise<void> {
  try {
    const data = await getEnrichProgress()
    enrichProgress.value = data
    if (enrichWasRunning && !data.running) {
      // 刚跑完：提示结果 + 刷新列表（补好的书会从「缺失信息」筛选里消失）
      ElMessage.success(buildEnrichSummary(data))
      await load()
    }
    enrichWasRunning = data.running
    if (data.running) {
      enrichTimer = window.setTimeout(pollEnrich, 1500)
    }
  } catch {
    // 拦截器已提示；停止轮询，避免一直刷错误提示
    enrichWasRunning = false
  }
}

function startEnrichPolling(): void {
  stopEnrichPolling()
  enrichWasRunning = true
  enrichTimer = window.setTimeout(pollEnrich, 800)
}

/** 把终态快照拼成一句话 */
function buildEnrichSummary(p: EnrichResult): string {
  const parts = [`补齐 ${p.updated} 本`]
  if (p.derived > 0) {
    parts.push(`本地派生分类 ${p.derived} 本`)
  }
  if (p.skipped > 0) {
    parts.push(`跳过 ${p.skipped} 本`)
  }
  if (p.failed > 0) {
    parts.push(`失败 ${p.failed} 本`)
  }
  const seconds = p.costMs > 0 ? (p.costMs / 1000).toFixed(1) : '0'
  return `${parts.join('，')}（共 ${p.requested} 本，耗时 ${seconds} 秒）`
}

// 离开页面时停掉轮询，别让定时器在后台一直打接口
onUnmounted(() => {
  stopEnrichPolling()
})

// ---------------------------------------------------------------------------

onMounted(() => {
  loadDict()
  loadBooklistOptions()
  load()
  // 挂载时拉一次补齐进度：页面刷新 / 从别处切回来时，能接着看到还在跑的任务
  pollEnrich()
})
</script>

<template>
  <div class="page">
    <!-- ================= 筛选栏（18 个查询参数） ================= -->
    <SearchForm
      :model="query"
      :loading="loading"
      :columns="4"
      label-width="100px"
      @search="handleSearch"
      @reset="handleReset"
    >
      <!--
        顺序由用户 2026-10-12 重排：书名 → 作者 → 推荐值区间 → 阅读人数 →
        平台分类 → 出版时间 → 评价标签 → 采集时间（同日**移除了「采集来源」筛选项**）。
        `span-2` 的项都是「一个标签下放两个控件」或内容较长（平台分类是**两级级联框**，
        选项形如「文学（300000）」，选到二级还要再展开一层），单列放不下；
        4 个单列 + 4 个 `span-2` 正好把 4 列排满 3 行，没有空洞。
      -->

      <el-form-item label="书名">
        <el-input v-model="query.title" clearable placeholder="模糊搜索" />
      </el-form-item>

      <el-form-item label="作者">
        <el-input v-model="query.author" clearable placeholder="模糊搜索" />
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

      <el-form-item label="平台分类" class="span-2">
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


      <!-- 出版时间：**年月**，两个月份组件。两个都可以空，只填一个也生效 -->
      <el-form-item label="出版时间" class="span-2">
        <div class="range">
          <el-date-picker
              v-model="query.minPublishTime"
              type="month"
              value-format="YYYY-MM"
              placeholder="起始年月"
          />
          <span class="range-sep">~</span>
          <el-date-picker
              v-model="query.maxPublishTime"
              type="month"
              value-format="YYYY-MM"
              placeholder="结束年月"
          />
        </div>
      </el-form-item>

      <el-form-item label="评价标签" class="span-2">
        <el-select v-model="query.newRatingTitle" clearable placeholder="全部档位">
          <el-option
              v-for="opt in RATING_TITLE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
          />
        </el-select>
      </el-form-item>



      <!--
        采集时间：**年月日**，两个日期组件。
        ⚠️ 上限是「含当天整天」（后端补到 23:59:59.999）—— 选「到 10-10」要能查到 10 号采的书。
      -->
      <el-form-item label="采集时间" class="span-2">
        <div class="range">
          <el-date-picker
            v-model="query.minCollectedAt"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="起始日期"
          />
          <span class="range-sep">~</span>
          <el-date-picker
            v-model="query.maxCollectedAt"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="结束日期"
          />
        </div>
      </el-form-item>

      <!--
        缺失信息筛选（2026-10-16）：无分类 / 无简介 / 无分类&无简介。
        为什么要分三档：有些书**本身就没有分类**（平台没给它打标签），补齐也补不出来 ——
        只有一个「无分类」档的话，这些书会永远留在筛选结果里，用户分不清
        「是还没补」还是「根本补不出来」。
        ⚠️ 选项值必须与后端 `BookQuery.resolveMissing()` 的白名单一致（大写）。
      -->
      <el-form-item label="缺失信息">
        <el-select v-model="query.missing" clearable placeholder="全部">
          <el-option
            v-for="opt in BOOK_MISSING_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </el-form-item>

      <!--
        书单筛选（2026-10-16）：选项来自 GET /api/booklists/options（全量、不分页）。
        与其它筛选条件是 AND。名称没回填时用 booklistId 兜底显示。
      -->
      <el-form-item label="书单" class="span-2">
        <el-select
          v-model="query.booklistId"
          clearable
          filterable
          placeholder="全部书单"
        >
          <el-option
            v-for="opt in booklistOptions"
            :key="opt.booklistId"
            :label="opt.name || opt.booklistId"
            :value="opt.booklistId"
          />
        </el-select>
      </el-form-item>

      <!--
        字数筛选（2026-10-16）：语义是 `totalWords >= 输入值`。
        ⚠️ totalWords 来自 /book/info，只有「完善书籍信息」补过的书才有 ——
        没补过的会被这个条件排除掉，这是数据决定的。
      -->
      <el-form-item label="字数">
        <el-input-number
          v-model="query.minTotalWords"
          :min="0"
          :controls="false"
          placeholder="下限，不限则空"
        />
      </el-form-item>

      <!--
        补齐状态（2026-10-16）：区分「还没补」和「补过但补不出来」。
        🔴 有的书平台本来就没有分类/简介，补也补不出来 —— 只用「缺失信息」筛的话，
        它们会永远留在结果里，分不清是没补还是补不出来。
        要拿「真正的待补清单」就选「缺失信息 = 无分类&无简介」再选「未补齐」。
      -->
      <el-form-item label="补齐状态">
        <el-select v-model="query.enriched" clearable placeholder="全部">
          <el-option label="未补齐" :value="false" />
          <el-option label="已补齐" :value="true" />
        </el-select>
      </el-form-item>

      <el-form-item label="付费精选">
        <el-select v-model="query.paidCollect" clearable placeholder="全部">
          <el-option label="是" :value="true" />
          <el-option label="否" :value="false" />
        </el-select>
      </el-form-item>

    </SearchForm>

    <!-- ================= 表格 ================= -->
    <el-card shadow="never" class="table-card">
      <!--
        `:key` = `tableKey` —— 只在「分类联动改了排序」时 +1，用来强制重建表格。
        原因：el-table 只在挂载时读一次 `default-sort`，程序化改排序后表头箭头不会跟着变
        （详见 `defaultSort` / `applySortForCategory` 的注释）。
        用户点表头改排序**不会**换 key，那时 el-table 自己管箭头。
      -->
      <!--
        补齐进度（2026-10-16）。后端是「提交即返回 + 轮询」，这里显示实时进度。
        只有「正在跑」或「本次会话跑过」才显示 —— 从没跑过时后端回的是全零快照。
      -->
      <div
        v-if="enrichProgress && (enrichRunning || enrichProgress.startedAtMs)"
        class="enrich-progress"
      >
        <el-progress
          :percentage="enrichPercent"
          :status="
            enrichRunning ? undefined : enrichProgress.failed > 0 ? 'warning' : 'success'
          "
        />
        <div class="enrich-progress-msg">
          {{ enrichProgress.message || '—' }}
          · 已处理 {{ enrichProgress.processed }} / {{ enrichProgress.requested }}
          <template v-if="enrichProgress.fetched > 0">
            · 调接口
            {{ enrichProgress.updated + enrichProgress.failed }} / {{ enrichProgress.fetched }}
          </template>
          <template v-if="enrichProgress.updated > 0">
            · 补齐 {{ enrichProgress.updated }}
          </template>
          <template v-if="enrichProgress.derived > 0">
            · 本地派生分类 {{ enrichProgress.derived }}
          </template>
          <template v-if="enrichProgress.failed > 0">
            · 失败 {{ enrichProgress.failed }}
          </template>
        </div>
      </div>

      <ProTable
        :key="tableKey"
        :data="rows"
        :loading="loading"
        :total="total"
        :page="page"
        :size="size"
        :page-sizes="[20, 50, 100, 500]"
        row-key="bookId"
        selectable
        :default-sort="defaultSort"
        max-height="calc(100vh - 430px)"
        empty-text="没有符合条件的图书"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
        @selection-change="handleSelectionChange"
        @sort-change="handleSortChange"
      >
        <template #toolbar>
          <el-button type="primary" :loading="loading" @click="load">
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

          <el-button
            v-if="selectedRows.length > 0"
            type="primary"
            plain
            :disabled="enrichRunning"
            @click="confirmEnrich"
          >
            <el-icon><MagicStick /></el-icon>
            完善书籍信息（{{ selectedRows.length }}）
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

          <span class="toolbar-info">
            共 {{ formatNumber(total) }} 本 · 本页 {{ rows.length }} 条 ·
            排序：{{ SORT_LABELS[sortField] }} {{ sortAsc ? '升序' : '降序' }}
          </span>
        </template>

        <el-table-column v-if="cols.isVisible('cover')" label="封面" width="68" align="center">
          <template #default="{ row }">
            <el-image
              v-if="row.cover"
              :src="row.cover"
              :preview-src-list="[row.cover]"
              fit="cover"
              lazy
              preview-teleported
              class="cover"
            />
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('title')"
          prop="title"
          label="书名"
          min-width="160"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="openDetail(row)">
              {{ row.title || '（无书名）' }}
            </el-link>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('author')"
          prop="author"
          label="作者"
          width="100"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ dash(row.author) }}</template>
        </el-table-column>

        <!--
          平台分类：第一行是 category（= categories[0].title），
          下面把 categories[] 里**其余**的 title 也一并列出来（最多见过 5 个，99.9% 是 1~3 个）。
          ⚠️ 不要用 show-overflow-tooltip —— 多标签会换行，截断后用户看不到剩下的分类。
        -->
        <el-table-column
          v-if="cols.isVisible('category')"
          prop="category"
          label="平台分类"
          min-width="156"
        >
          <template #default="{ row }">
            <div class="cat-cell">
              <span class="cat-main">{{ dash(row.category) }}</span>
              <el-tag
                v-for="t in extraCategoryTitles(row)"
                :key="t"
                size="small"
                effect="plain"
                class="cat-tag"
              >
                {{ t }}
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('newRating')"
          prop="newRating"
          label="推荐值"
          width="78"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ ratingText(row.newRating) }}</span>
          </template>
        </el-table-column>

        <!--
          「评价标题」= 档位文案（神作 / 好评如潮 / 神作潜力 / 脍炙人口 / 值得一读 …），
          2026-10-13 加、默认显示。它**不是数值列**，所以不给 sortable ——
          `BookSortField` 白名单里也没有它。
        -->
        <el-table-column
          v-if="cols.isVisible('newRatingTitle')"
          prop="newRatingTitle"
          label="评价标题"
          width="84"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ dash(row.newRatingTitle) }}</template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('newRatingCount')"
          prop="newRatingCount"
          label="评价人数"
          width="92"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.newRatingCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('readingCount')"
          prop="readingCount"
          label="阅读人数"
          width="92"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.readingCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('publishTime')"
          prop="publishTime"
          label="出版时间"
          width="92"
          sortable="custom"
        >
          <!-- 库里存在 "2022" 这种只有年份的脏值，所以不能按时间格式化，只能原样显示 -->
          <template #default="{ row }">{{ formatPublishDate(row.publishTime) }}</template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('lastCollectedAt')"
          prop="lastCollectedAt"
          label="最近采集"
          width="112"
          sortable="custom"
        >
          <template #default="{ row }">{{ formatDate(row.lastCollectedAt) }}</template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('collectSource')"
          label="采集来源"
          min-width="150"
        >
          <template #default="{ row }">
            <template v-if="sourceList(row.collectSource).length">
              <el-tag
                v-for="s in sourceList(row.collectSource)"
                :key="s"
                size="small"
                effect="plain"
                class="source-tag"
              >
                {{ s }}
              </el-tag>
            </template>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('paidCollect')"
          label="付费精选"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            <el-tag v-if="row.paidCollect === 1" size="small" type="success" effect="plain">
              付费
            </el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('actions')"
          label="操作"
          width="200"
          fixed="right"
          align="center"
        >
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <!--
              藏书 / 阅读清单 / 阅读状态共五个动作，收进「更多」下拉。
              平铺要 250px 以上，而默认列宽总和已经贴着 1440 屏的表格容器宽度
              （Element Plus 的横向滚动条默认隐藏，超了用户只会以为「右边少了几列」）。
            -->
            <BookActionMenu
              :book-id="row.bookId"
              :on-shelf="isOnShelf(row.bookId)"
              :reading-status="readingStatusOf(row.bookId)"
              :disabled="actionBusy"
              @toggle-shelf="row.bookId && toggleShelf(row.bookId)"
              @toggle-reading="row.bookId && toggleReadingList(row.bookId)"
              @set-status="(s) => row.bookId && setReadingStatus(row.bookId, s)"
            />
            <el-button link type="danger" :disabled="deleting" @click="confirmDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </ProTable>
    </el-card>

    <!-- ================= 详情抽屉 ================= -->
    <DetailDrawer
      v-model="detailVisible"
      :title="detailBook?.title || '图书详情'"
      :subtitle="detailBook ? `${detailBook.author || '佚名'} · 推荐值 ${ratingText(detailBook.newRating)} · ${detailItemCount} 个字段` : ''"
      :sections="detailSections"
      :loading="detailLoading"
      width="760px"
    >
      <template #footer>
        <el-button v-if="detailBook" @click="openEdit(detailBook)">
          <el-icon><Edit /></el-icon>
          编辑
        </el-button>
        <el-button
          v-if="detailBook"
          type="danger"
          :loading="deleting"
          @click="confirmDelete(detailBook)"
        >
          <el-icon><Delete /></el-icon>
          删除
        </el-button>
      </template>
    </DetailDrawer>

    <!-- ================= 编辑弹窗 ================= -->
    <BookEditDialog v-model="editVisible" :book="editBook" @saved="handleSaved" />
  </div>
</template>

<style scoped>

/* 完善书籍信息的进度条（2026-10-16） */
.enrich-progress {
  margin-bottom: 12px;
}

.enrich-progress-msg {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.table-card :deep(.el-card__body) {
  padding: 16px;
}

/* 区间输入：两个控件 + 中间的波浪号 */
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
/*
 * ⚠️ `el-date-picker` 默认 `width: 220px`（`--el-date-editor-width`），
 * 在 flex 行里不放开就会把第二个控件挤出去。`flex: 1 1 0` + `width: auto` 才让它参与分配。
 */
.range :deep(.el-date-editor) {
  flex: 1 1 0;
  min-width: 0;
  width: auto;
}
.range-sep {
  color: #909399;
  flex-shrink: 0;
}

/*
 * 跨两列。用在「一个标签下要放两个控件」的筛选项（出版时间 / 采集时间）
 * 和选项文案较长的平台分类 / 评价标签上 —— 单列放不下，会被压到没法用。
 * 2026-10-12 重排后共 8 个筛选项：4 个单列 + 4 个 `span-2` = 12 列单位，
 * 正好把 4 列排满 3 行，没有空洞。
 */
.span-2 {
  grid-column: span 2;
}

/*
 * ⚠️ `el-cascader` 默认是 `display: inline-block` 且**没有 width**
 * （Element Plus 2.14.6 的 `dist/index.css` 里 `.el-cascader` 只有
 * `display:inline-block;position:relative`），放进网格会缩成内容宽度。
 * 给它 100%，才和同排的 `el-select` / 日期区间一样占满 `span-2`。
 */
.category-cascader {
  width: 100%;
}
.toolbar-info {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.cover {
  width: 44px;
  height: 60px;
  border-radius: 3px;
  background: #f5f7fa;
  vertical-align: middle;
}

.source-tag {
  margin: 0 4px 2px 0;
}

/*
 * 平台分类单元格：主行（category）在下，其余分类标签在上一行之后自动换行。
 * ⚠️ 不能设 `white-space: nowrap` —— 那会把多余的标签裁掉，
 * 而这一列存在的意义就是「把所有平台分类都看到」。
 */
.cat-cell {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 6px;
  line-height: 1.5;
}

.cat-main {
  word-break: break-all;
}

.cat-tag {
  max-width: 100%;
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
