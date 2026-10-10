/**
 * 后端数据结构的手写 TS 类型。
 *
 * 为什么不自动生成：本期后端只有 8 个实体 + 十来个 DTO，手写一遍顺便能核对字段名。
 * 自动生成（openapi-typescript）需要额外工具链，而且生成出来的名字是 `Book1`/`Book2`
 * 这种没法用的东西。
 *
 * ⚠️ 两条必须知道的序列化事实（已用 curl 实测确认）：
 *
 * 1. **日期是 UTC 的 ISO-8601 字符串**，形如 `"2026-09-21T11:39:48.584+00:00"`。
 *    Spring Boot 默认关掉了 `WRITE_DATES_AS_TIMESTAMPS`，所以不是毫秒数。
 *    直接 `new Date(iso)` 会被浏览器按 UTC 解析、再按本地时区渲染 —— 这是对的。
 *    但**千万不要**用 `iso.slice(0, 19).replace('T', ' ')` 这种土办法，
 *    那样会把 UTC 时间当成本地时间显示，中国时区下会差 8 小时。
 *
 * 2. **`null` 会被原样输出**（`"remark": null`），不是省略。所以可选字段要写 `?`
 *    还是 `| null` 取决于后端：实体字段没有 `@JsonInclude(NON_NULL)`，
 *    所以「有 key 但值为 null」是常态 → 这里统一用 `?: T | null` 表达。
 */

// ======================================================================
// 通用返回结构（对应 com.bookcollector.common.ResultBean / PageResult）
// ======================================================================

/** 统一返回体。⚠️ 成功码是 200，不是 0 */
export interface ResultBean<T = unknown> {
  code: number
  message: string
  traceId: string
  data: T
}

/** 分页结构。注意后端还会多带一个 `totalPages` */
export interface PageResult<T = unknown> {
  list: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

/** 业务错误码常量，与 Java 的 ResultBean 保持一致 */
export const CODE_OK = 200
export const CODE_WARN = 400
export const CODE_NOTFOUND = 404
export const CODE_DUPLICATE_KEY = 405
export const CODE_ERR = 500
export const CODE_SESSION_INVALID = 4100

// ======================================================================
// 枚举（后端是 String，取值是枚举名）
// ======================================================================

/** 采集目标类型 */
export type TargetType = 'CATEGORY' | 'RANKING'

export const TARGET_TYPE_LABEL: Record<TargetType, string> = {
  CATEGORY: '分类',
  RANKING: '榜单',
}

/**
 * 任务 / 运行状态。
 *
 * ⚠️ `INTERRUPTED` **不是**终态 —— 它表示「进程被杀了但游标还在」，可以直接恢复。
 * 只有 SUCCESS / FAILED / CANCELED 是终态。
 */
export type TaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'PAUSED'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELED'
  | 'INTERRUPTED'

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  PENDING: '待执行',
  RUNNING: '运行中',
  PAUSED: '已暂停',
  SUCCESS: '已完成',
  FAILED: '失败',
  CANCELED: '已取消',
  INTERRUPTED: '已中断',
}

/** Element Plus 的 tag 类型 */
export type TagType = 'success' | 'warning' | 'info' | 'primary' | 'danger'

export const TASK_STATUS_TAG: Record<TaskStatus, TagType> = {
  PENDING: 'info',
  RUNNING: 'primary',
  PAUSED: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELED: 'info',
  INTERRUPTED: 'warning',
}

/** 终态：不可再推进 */
export function isTerminalStatus(status?: TaskStatus | null): boolean {
  return status === 'SUCCESS' || status === 'FAILED' || status === 'CANCELED'
}

/** 活跃态：正在占用采集线程 */
export function isActiveStatus(status?: TaskStatus | null): boolean {
  return status === 'RUNNING' || status === 'PAUSED'
}

/** 可被 start / resume 拉起 */
export function isResumableStatus(status?: TaskStatus | null): boolean {
  return status === 'PENDING' || status === 'PAUSED' || status === 'INTERRUPTED'
}

// ======================================================================
// 认证（com.bookcollector.auth）
// ======================================================================

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginData {
  token: string
  username: string
}

export interface MeData {
  username: string
  nickname: string
  roles: string[]
}

// ======================================================================
// 图书（com.bookcollector.book.entity.Book）
// ======================================================================

/** 平台分类（`categories` 数组元素） */
export interface BookCategory {
  categoryId?: number | null
  subCategoryId?: number | null
  categoryType?: number | null
  title?: string | null
}

/**
 * 评分详情。
 *
 * 🔴 **这里没有 `newRating` 字段** —— 推荐值（0~1000）在 `Book` 的**顶层** `newRating` 上。
 * 这是 S4 照出来的一个真实缺陷：一开始把评分当成 `newRatingDetail.newRating` 读，
 * 结果排序、筛选、评分分布图全部静默失效（字段不存在 → 查询匹配不到 → 返回空，
 * 而且不报错）。见 `docs/02` 的「缺陷 1」。
 */
export interface RatingDetail {
  good?: number | null
  fair?: number | null
  poor?: number | null
  recent?: number | null
  title?: string | null
}

export interface MaxFreeInfo {
  maxFreeChapterIdx?: number | null
  maxFreeChapterUid?: number | null
  maxFreeChapterRatio?: number | null
}

/** 图书。46 个字段，与后端实体一一对应 */
export interface Book {
  /** MongoDB 主键 */
  id?: string | null
  bookId: string
  title?: string | null
  author?: string | null
  translator?: string | null
  cover?: string | null
  version?: number | null
  format?: string | null
  type?: number | null
  price?: number | null
  originalPrice?: number | null
  soldout?: number | null
  bookStatus?: number | null
  payingStatus?: number | null
  payType?: number | null
  intro?: string | null
  centPrice?: number | null
  finished?: number | null
  maxFreeChapter?: number | null
  free?: number | null
  mcardDiscount?: number | null
  ispub?: number | null
  extraType?: number | null
  cpid?: number | null
  /** 字符串，形如 "2022-08-01 00:00:00"；库里存在只有年份的脏值 "2022" */
  publishTime?: string | null
  /** 平台分类名，形如「精品小说-社会小说」 */
  category?: string | null
  categories?: BookCategory[] | null
  hasLecture?: number | null
  lastChapterIdx?: number | null
  paperBookSkuId?: string | null
  blockSaveImg?: number | null
  language?: string | null
  isTraditionalChinese?: boolean | null
  hideUpdateTime?: boolean | null
  isEpubComics?: number | null
  isVerticalLayout?: number | null
  isShowTts?: number | null
  webBookControl?: number | null
  selfProduceIncentive?: boolean | null
  isAutoDownload?: number | null
  /** 🔴 推荐值 0~1000，**顶层字段** */
  newRating?: number | null
  newRatingCount?: number | null
  newRatingTitle?: string | null
  searchIdx?: number | null
  typeInfo?: number | null
  readingCount?: number | null
  newRatingDetail?: RatingDetail | null
  maxFreeInfo?: MaxFreeInfo | null
  copyrightChapterUids?: number[] | null
  /**
   * 🔴 注意是小写 `lpush`，不是 `lPush`。
   *
   * Java 实体里字段名是 `lPushName`（`Book.java:210`），但 Jackson 的默认命名策略
   * 会把 `getLPushName()` 序列化成 **`lpushName`** —— 它把开头连续的大写字母
   * 一起小写了（标准 JavaBeans 的 decapitalize 规则是「前两个字母都大写则原样保留」，
   * Jackson 默认**不用**这条规则）。
   *
   * 所以前端要是按 Java 的拼写写成 `lPushName`，读出来永远是 `undefined`，
   * 详情页那一栏永远显示 `-` —— 不报错、不提示，纯静默。
   * S6.2 做详情抽屉时用「拿真实接口返回的 54 个键和 TS 声明逐个比对」才发现。
   */
  lpushName?: string | null
  authorVids?: string | null
  /** 首次采集时间（UTC ISO 字符串）。「今日新增」口径用它，不用 lastCollectedAt */
  firstCollectedAt?: string | null
  /** 最近一次采集时间。每次 upsert 都会刷新，所以**不能**用来数「今天新增」 */
  lastCollectedAt?: string | null
  /** 采集来源列表，形如 ["category:300000", "ranking:rising"] */
  collectSource?: string[] | null
}

/** 图书列表查询条件，对应后端 BookQuery 的 16 个参数 */
export interface BookQuery {
  categoryId?: number
  minRating?: number
  maxRating?: number
  minReadingCount?: number
  targetType?: TargetType | ''
  targetId?: string
  /** 书名关键词，包含匹配（模糊搜索） */
  title?: string
  /** 作者关键词，包含匹配（模糊搜索） */
  author?: string
  /** 出版时间下限，`yyyy-MM`（年月）。后端按字符串字典序比较 */
  minPublishTime?: string
  /** 出版时间上限，`yyyy-MM`（年月）。传 `2023` 时语义是「2023 年全年」 */
  maxPublishTime?: string
  /** 采集时间下限，`yyyy-MM-dd`（本地日期，含当天） */
  minCollectedAt?: string
  /** 采集时间上限，`yyyy-MM-dd`（本地日期，**含当天整天**，后端补到 23:59:59.999） */
  maxCollectedAt?: string
  sortField?: BookSortField
  asc?: boolean
  page?: number
  size?: number
}

/** 排序字段白名单。后端 `resolveSortPath()` 里不认识的值会 fallback 到 newRating */
export type BookSortField =
  | 'newRating'
  | 'newRatingCount'
  | 'readingCount'
  | 'publishTime'
  | 'lastCollectedAt'

export const BOOK_SORT_OPTIONS: Array<{ label: string; value: BookSortField }> = [
  { label: '推荐值', value: 'newRating' },
  { label: '评价人数', value: 'newRatingCount' },
  { label: '阅读人数', value: 'readingCount' },
  { label: '出版时间', value: 'publishTime' },
  { label: '采集时间', value: 'lastCollectedAt' },
]

/** 图书可编辑字段白名单（13 个）。刻意不含 bookId / searchIdx / collectSource / firstCollectedAt */
export interface BookUpdateRequest {
  title?: string
  author?: string
  translator?: string
  intro?: string
  category?: string
  categories?: BookCategory[]
  publishTime?: string
  language?: string
  ispub?: number
  finished?: number
  free?: number
  price?: number
  originalPrice?: number
}

/** 批量删除结果 */
export interface BatchDeleteResult {
  requested: number
  deleted: number
}

// ======================================================================
// 字典（com.bookcollector.taxonomy.entity.TaxonomyItem）
// ======================================================================

export interface TaxonomyItem {
  id?: string | null
  type: TargetType
  /** 采集目标的身份。**编辑时不允许修改** */
  code: string
  /** 展示名。二级分类用「一级-二级」形式，如「历史-历史读物」 */
  name: string
  /**
   * 上级分类的 code。`null` / 缺省 = **一级分类**；有值 = **二级分类**。
   *
   * ⚠️ 一级与二级都是合法的采集目标（`targetId` 只是拼进 URL 路径）。
   * 但只有一级的 code 能当 `categoryId` 用 —— 二级的是 `subCategoryId`，是另一个字段。
   */
  parentCode?: string | null
  enabled?: boolean | null
  sort?: number | null
  remark?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface TaxonomyRequest {
  code?: string
  name?: string
  enabled?: boolean
  sort?: number
  remark?: string
}

// ======================================================================
// 采集任务（com.bookcollector.task.entity.*）
// ======================================================================

export interface CollectTask {
  id?: string | null
  name: string
  targetType: TargetType
  targetId: string
  targetName?: string | null
  maxPages?: number | null
  /**
   * 排除分类 code 列表。命中的图书**不写库**。空数组 / null = 不排除。
   *
   * ⚠️ 存的是 code（`"1000007"`）不是名字，一级与二级混存 ——
   * 一级 `1000000` 排除整个「个人成长」，二级 `1000007` 只掐掉「女性成长」。
   * 展示时要拿字典映射回名字；**映射不到就直接显示 code**
   * （字典覆盖不全，实测库里出现过的分类比字典多 3 个一级 / 53 个二级）。
   */
  excludeCategories?: string[] | null
  status: TaskStatus
  remark?: string | null
  lastRunId?: string | null
  runCount?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 新建任务请求 */
export interface TaskCreateRequest {
  name: string
  targetType: TargetType
  targetId: string
  targetName?: string
  maxPages?: number
  /** 排除分类 code 列表。不传或空 = 不排除 */
  excludeCategories?: string[]
  remark?: string
}

/**
 * 编辑任务请求。
 *
 * ⚠️ 只有 4 个字段 —— `targetType` / `targetId` **刻意不在里面**。
 * 它们是游标的归属键，改了会让游标与目标脱钩，所以让「不能改」在类型层面成立。
 */
export interface TaskUpdateRequest {
  name?: string
  maxPages?: number
  remark?: string
  /**
   * 排除分类 code 列表。
   *
   * ⚠️ 与 `name` / `remark` 不同，这个字段**空数组是有意义的**（= 清空排除），
   * 而 `undefined` = 不改。所以编辑时永远要显式传它，别用「空就不传」的写法。
   */
  excludeCategories?: string[]
}

export interface TaskRun {
  id?: string | null
  taskId: string
  taskName?: string | null
  targetType?: TargetType | null
  targetId?: string | null
  targetName?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  status: TaskStatus
  pagesDone?: number | null
  /** 累计写入/更新的本数（upsert 计数，含重复更新的老书）。`booksInserted` 包含在它里面 */
  booksSaved?: number | null
  /** 其中**新增**的本数（库里原本没有这个 bookId）。更新数 = `booksSaved - booksInserted` */
  booksInserted?: number | null
  /**
   * 其中因命中任务的「排除分类」而**没写库**的本数。
   *
   * ⚠️ 它**不**包含在 `booksSaved` 里 —— 被排除的书压根没写。
   * 界面要把差额讲清楚，否则「采回来 20 本、只入库 13 本」看起来像丢了数据。
   * 没配排除分类的任务恒为 0。
   */
  booksExcluded?: number | null
  /** 这次运行跑到哪了（进度快照，给界面看）。**续传的权威依据是游标，不是这个** */
  currentCursor?: number | null
  totalCount?: number | null
  lastProgressAt?: string | null
  errorMsg?: string | null
}

export interface TaskLog {
  id?: string | null
  runId?: string | null
  taskId?: string | null
  level?: string | null
  message?: string | null
  createdAt?: string | null
}

/** 任务删除结果（级联删了 runs + logs，**不删游标**） */
export interface TaskDeleteResult {
  taskId: string
  runsDeleted: number
  logsDeleted: number
}

export interface RunQuery {
  taskId?: string
  status?: TaskStatus | ''
  page?: number
  size?: number
}

// ======================================================================
// 采集游标（com.bookcollector.cursor.entity.CollectCursor）
// ======================================================================

export interface CollectCursor {
  id?: string | null
  targetType: TargetType
  targetId: string
  targetName?: string | null
  /** 「下次从哪开始」。这是续传的**唯一权威**依据 */
  maxIndex?: number | null
  totalCollected?: number | null
  totalCount?: number | null
  finished?: boolean | null
  lastRunId?: string | null
  updatedAt?: string | null
}

/** 设置游标请求。`maxIndex = 0` 即「重置」 */
export interface CursorSetRequest {
  targetType: TargetType
  targetId: string
  targetName?: string
  maxIndex?: number
}

export interface CursorSetResult {
  targetType: string
  targetId: string
  maxIndex: number
  note: string
}

// ======================================================================
// 请求审计（com.bookcollector.audit.entity.ApiRequest）
// ======================================================================

export interface ApiRequest {
  id?: string | null
  runId?: string | null
  targetType?: string | null
  targetId?: string | null
  pageIndex?: number | null
  maxIndex?: number | null
  url?: string | null
  statusCode?: number | null
  responseTimeMs?: number | null
  synckey?: number | null
  totalCount?: number | null
  hasMore?: boolean | null
  resultCount?: number | null
  errorMsg?: string | null
  createdAt?: string | null
}

export interface ApiRequestQuery {
  runId?: string
  targetType?: TargetType | ''
  targetId?: string
  onlyFailed?: boolean
  page?: number
  size?: number
}

/**
 * 请求审计摘要（`GET /api/requests/summary`）。
 *
 * 口径：与列表**同一套筛选条件**，但**不分页** —— 回答的是「这一批请求整体怎么样」，
 * 不是「当前这一页怎么样」。所以它不随翻页变化。
 */
export interface RequestSummary {
  /** 当前筛选条件下的请求总次数 */
  total: number
  /** 其中失败的次数（状态码非 200 或有错误摘要）。判据与「只看失败」完全一致 */
  failed: number
  /** 平均耗时（ms）。没有可统计的记录时为 null —— 不是 0，0ms 是「很快」 */
  avgMs?: number | null
  /** P95 耗时（ms）。同上 */
  p95Ms?: number | null
  /** 最慢一次（ms）。同上 */
  maxMs?: number | null
}

// ======================================================================
// 仪表盘统计（com.bookcollector.stats.StatsService）
// ======================================================================

export interface StatsOverview {
  /** 图书总数 */
  bookTotal: number
  /** 分类总数 */
  categoryTotal: number
  /** 任务总数 */
  taskTotal: number
  /** 今日新增（按 firstCollectedAt 数「净新增」） */
  todayCollected: number

  // --- 上下文 ---
  categoryEnabled: number
  rankingTotal: number
  rankingEnabled: number
  /** 运行中的任务数（RUNNING + PAUSED） */
  taskRunning: number
  /** 内存里活跃的采集线程数 */
  activeRuns: number
  cursorTotal: number
  requestTotal: number
  last7DaysCollected: number
  lastCollectedAt?: string | null
}

/** 分类分布（饼图）。按「采集目标」分，不按平台分类分 */
export interface CategoryDistributionItem {
  sourceKey: string
  type: string
  targetId: string
  /** 字典里查不到时是 sourceKey 本身（不隐藏异常数据） */
  name: string
  count: number
}

/** 评分分布（柱图）。固定 10 个桶，空桶已补齐 */
export interface RatingDistributionItem {
  label: string
  min: number
  max: number
  count: number
}

/** 出版年份趋势（折线图）。年份已过滤到 1900–2100 */
export interface PublishYearTrendItem {
  year: string
  count: number
}

/** 近 7 天采集量（柱图）。固定 7 天，空日期已补齐 */
export interface RecentCollectedItem {
  date: string
  count: number
}

export interface StatsCharts {
  categoryDistribution: CategoryDistributionItem[]
  ratingDistribution: RatingDistributionItem[]
  publishYearTrend: PublishYearTrendItem[]
  recentCollected: RecentCollectedItem[]
}

// ======================================================================
// 定时刷新（com.bookcollector.refresh）
// ======================================================================

/**
 * 刷新任务状态。
 *
 * ⚠️ 同一个类型同时用于**批次级**（`lastRunStatus`）和**目标级**（`detail.status`）——
 * 它们描述的是同一件事，只是粒度不同，所以后端是一个枚举而不是两个。
 * 目标级只会用到 SUCCESS / FAILED / CANCELED / ABORTED 四个值。
 */
export type RefreshStatus =
  | 'IDLE'
  | 'RUNNING'
  | 'SUCCESS'
  | 'PARTIAL'
  | 'FAILED'
  | 'ABORTED'
  | 'CANCELED'
  | 'INTERRUPTED'

export const REFRESH_STATUS_LABEL: Record<RefreshStatus, string> = {
  IDLE: '从未执行',
  RUNNING: '执行中',
  SUCCESS: '成功',
  PARTIAL: '部分失败',
  FAILED: '失败',
  ABORTED: '已中止',
  CANCELED: '已取消',
  INTERRUPTED: '已中断',
}

export const REFRESH_STATUS_TAG: Record<RefreshStatus, TagType> = {
  IDLE: 'info',
  RUNNING: 'primary',
  SUCCESS: 'success',
  PARTIAL: 'warning',
  FAILED: 'danger',
  ABORTED: 'warning',
  CANCELED: 'info',
  INTERRUPTED: 'warning',
}

/**
 * 一个刷新目标。
 *
 * ⚠️ 这份清单**写在后端代码里**（`RefreshTarget.ALL`），页面只能读、不能改。
 * 见 `views/refresh/index.vue` 的说明：做成可配置的话，
 * 改坏了范围只会表现为「某个榜单一整期没更新」，这种静默失败最难查。
 */
export interface RefreshTarget {
  type: TargetType
  targetId: string
  name: string
}

/** 批次里单个目标的结果 */
export interface RefreshDetail {
  targetType?: TargetType | null
  targetId?: string | null
  targetName?: string | null
  /**
   * ⚠️ 执行中的目标会以 `RUNNING` 出现在明细里（后端的内存快照，不落库），
   * 此时 `pagesDone` / `booksSaved` / `booksInserted` 是**已采到**的数字。
   */
  status?: RefreshStatus | null
  pagesDone?: number | null
  /** 写入/更新的本数（含重复更新的老书）。`booksInserted` 包含在它里面 */
  booksSaved?: number | null
  /** 其中**新增**的本数（库里原本没有这个 bookId）。更新数 = `booksSaved - booksInserted` */
  booksInserted?: number | null
  totalCount?: number | null
  costMs?: number | null
  message?: string | null
}

/** 页面全量数据（`GET /api/refresh/job`）。配置 + 内存态 + 目标清单 + 最近一次结果 */
export interface RefreshJobVO {
  /** cron 表达式（**6 段式**：秒 分 时 日 月 周），如 `0 0 3 * * ?` */
  cron: string
  enabled: boolean
  /** 下次触发时间。未启用或算不出时为 null */
  nextFireTime?: string | null
  /**
   * 是否正在执行。
   *
   * ⚠️ 它来自**后端内存**（AtomicBoolean），不是 DB —— 页面刷新后靠它决定要不要继续轮询。
   */
  running: boolean
  /** 固定的 8 个目标 */
  targets: RefreshTarget[]
  lastRunAt?: string | null
  lastRunFinishedAt?: string | null
  lastRunStatus: RefreshStatus
  lastRunStatusLabel: string
  /** MANUAL / SCHEDULED */
  lastRunTrigger?: string | null
  lastRunMessage?: string | null
  lastRunSuccess?: number | null
  lastRunFailed?: number | null
  /**
   * 各目标结果。
   *
   * ⚠️ 长度**不一定**是 8：批次中途被占用而中止时，中止点之后的目标压根没跑。
   *
   * ⚠️ `running === true` 时这里是后端的**实时进度快照**（内存态，每采完一页更新一次），
   * 里面会包含 `status === 'RUNNING'` 的占位项 —— 用来显示「正在采哪个目标、已采到第几页」。
   * 批次结束后它才变成落库的终态。
   */
  lastDetails?: RefreshDetail[] | null
}

/** 改配置请求。两个字段都可选，null / 不传 = 不改这一项 */
export interface RefreshConfigRequest {
  cron?: string
  enabled?: boolean
}

// ======================================================================
// 发布配置 / 发布记录（com.bookcollector.publish）
// ======================================================================

/**
 * 单条解读记录的状态。
 *
 * ⚠️ 与 `RefreshStatus` 那套「批次状态」无关，这是**一本书**的状态。
 * `GENERATING` 会在进程被杀后残留（后端刻意不做启动清理），
 * 页面上按状态筛出来之后，用行内的「重置为待解读」把它捞回去。
 */
export type PublishStatus = 'PENDING' | 'GENERATING' | 'PUBLISHED' | 'FAILED'

export const PUBLISH_STATUS_LABEL: Record<PublishStatus, string> = {
  PENDING: '待解读',
  GENERATING: '生成中',
  PUBLISHED: '已发布',
  FAILED: '失败',
}

export const PUBLISH_STATUS_TAG: Record<PublishStatus, TagType> = {
  PENDING: 'info',
  GENERATING: 'primary',
  PUBLISHED: 'success',
  FAILED: 'danger',
}

/**
 * 页面全量数据（`GET /api/publish/job`）。
 *
 * 🔴 **不含任何密钥**：`ai_model.api_key` 与 `ai_yuque_token.token` 只在后端内存里用，
 * 接口只回「配没配」和三个非敏感字段（`modelName` / `modelId` / `modelBaseUrl`）。
 */
export interface PublishJobVO {
  /** cron 表达式（**6 段式**：秒 分 时 日 月 周），如 `0 0 4 * * ?` */
  cron: string
  enabled: boolean
  /** 下次触发时间。未启用或算不出时为 null */
  nextFireTime?: string | null

  // ---- 分类白名单（只读展示，来自 ai_taxonomy_config，用户自己维护）----
  /** 当前生效的分类名，如 ['文学', '历史'] */
  categoryNames: string[]
  categoryCount: number

  // ---- 模型 / 语雀（脱敏）----
  modelConfigured: boolean
  modelName?: string | null
  modelId?: string | null
  modelBaseUrl?: string | null
  yuqueConfigured: boolean

  // ---- 内存态：页面靠它们决定要不要继续轮询 ----
  /** 「初始化候选」是否正在执行 */
  initRunning: boolean
  /** 「生成并发布」是否正在执行 */
  genRunning: boolean

  // ---- 初始化进度 ----
  initScanned?: number | null
  /** 真正插入清单的条数 */
  initInserted?: number | null
  /** 因已存在而跳过的条数（= initScanned − initInserted） */
  initSkipped?: number | null

  // ---- 生成进度 ----
  currentBookTitle?: string | null
  currentPhase?: string | null
  /** 当前阶段已耗时（毫秒）。由前端自己按开始时间算，这里给后端算好的值 */
  currentElapsedMs?: number | null
  /** 最近一次执行的一句话总结 */
  lastMessage?: string | null

  // ---- 清单统计 ----
  pendingCount?: number | null
  generatingCount?: number | null
  publishedCount?: number | null
  failedCount?: number | null
  totalCount?: number | null
}

/**
 * 候选清单的一行（`GET /api/publish/interpretations`）。
 *
 * ⚠️ **不含正文** —— `content` / `rawContent` 是几千字一条，列表一页 20 条就是几十 KB，
 * 后端用 VO 收敛掉了。当前页面也不需要预览正文。
 */
export interface InterpretationVO {
  id: string
  bookId: string
  title?: string | null
  author?: string | null
  /** 平台分类，如「精品小说-社会小说」 */
  category?: string | null
  /** 推荐值 0~1000（913 = 91.3%） */
  newRating?: number | null
  newRatingCount?: number | null

  status?: PublishStatus | null
  /** 状态的中文文案，后端已给，前端不用再维护一份映射 */
  statusLabel?: string | null
  initializedAt?: string | null
  startedAt?: string | null
  finishedAt?: string | null

  localPath?: string | null
  yuqueDocId?: number | null
  yuqueUrl?: string | null
  model?: string | null
  /** 命中的敏感词个数 */
  sensitiveHitCount?: number | null
  errorMsg?: string | null
  /** MANUAL / SCHEDULED */
  trigger?: string | null
}

/** 改配置请求。两个字段都可选，null / 不传 = 不改这一项 */
export interface PublishConfigRequest {
  cron?: string
  enabled?: boolean
}

/**
 * 候选清单的查询条件，对应后端 `InterpretationQuery`。
 *
 * <h3>与 `BookQuery` 的关系</h3>
 * 候选清单是图书库的**完整快照**，所以筛选条件基本一致。刻意**不含**
 * `targetType` / `targetId`（采集来源）—— 在「找出某本书并删掉它」这个场景下没用。
 *
 * ⚠️ `categoryId` 只传**一级分类**的 code：二级分类的 code 是另一个字段，
 * 传进来恒不匹配、静默返回 0 条（与图书库同源的坑）。
 */
export interface InterpretationQuery {
  /** PENDING / GENERATING / PUBLISHED / FAILED，空 = 全部 */
  status?: PublishStatus | ''
  /** 书名关键词，包含匹配 */
  title?: string
  /** 作者关键词，包含匹配 */
  author?: string
  categoryId?: number
  minRating?: number
  maxRating?: number
  minReadingCount?: number
  minPublishTime?: string
  maxPublishTime?: string
  page?: number
  size?: number
}

/**
 * 一条发布记录（`GET /api/publish/records`）。
 *
 * <h3>它是流水账，不是当前状态</h3>
 * 同一本书可以发布多次（「重置为待解读」会重新生成一篇），每次一条独立记录。
 * 书名 / 作者 / 分类 / 推荐值都是**发布那一刻**的快照 ——
 * 图书库里的记录后来被改被删，都不会改写历史。
 *
 * ⚠️ 后端**实体直出**（这张表没有大字段，正文只落本地与语雀），
 * 所以字段名就是 Mongo 里的键名。
 */
export interface PublishRecord {
  id: string
  bookId?: string | null
  title?: string | null
  author?: string | null
  /** 平台分类，如「文学-经典作品」 */
  category?: string | null
  /** 推荐值 0~1000（913 = 91.3%） */
  newRating?: number | null

  yuqueDocId?: number | null
  yuqueUrl?: string | null
  localPath?: string | null
  model?: string | null
  promptTokens?: number | null
  completionTokens?: number | null
  sensitiveHitCount?: number | null
  /** MANUAL / SCHEDULED */
  trigger?: string | null

  /** 发布时间。列表按它倒序 */
  publishedAt?: string | null
  createdAt?: string | null
}

/** ==================================================================
 * 藏书阁（11. 藏书阁）
 * ================================================================== */

/**
 * 藏书阁里的一本书（快照）。
 *
 * <h3>数据源</h3>
 * 后端集合 `shelf_books`（2026-10-10 新建，替代原来的 AI 解读候选清单）。
 * 字段是**加入藏书阁那一刻**的快照 —— 图书库里的记录后来被改过或被删过，
 * 都不影响这里；想让某本书从藏书阁消失，用图书库操作列的「移除藏书」。
 */
export interface ShelfBook {
  bookId: string
  title?: string | null
  author?: string | null
  cover?: string | null
  /** 平台分类，如 文学-经典作品 */
  category?: string | null
  /** 推荐值 0~1000 */
  newRating?: number | null
  newRatingCount?: number | null
  readingCount?: number | null
  intro?: string | null
}

/** 藏书阁分类导航的一个一级类目（含数量）。类目来自数据本身，不是写死的常量 */
export interface ShelfCategory {
  name: string
  count: number
}
