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

/**
 * 采集目标类型。
 *
 * ⚠️ 取值是后端 `TargetType` 枚举的**名字**（不是中文 label），接口传的就是它。
 */
export type TargetType = 'CATEGORY' | 'RANKING' | 'BOOKLIST'

export const TARGET_TYPE_LABEL: Record<TargetType, string> = {
  CATEGORY: '分类',
  RANKING: '榜单',
  BOOKLIST: '书单',
}

/**
 * 类型标签的取色。
 *
 * ⚠️ 与 `TARGET_TYPE_LABEL` 一样是**唯一真相来源** ——
 * 各页面不要再自己写 `type === 'RANKING' ? 'warning' : 'primary'`。
 * 那种二元写法在新增类型时**不会报错**，只会把新类型静默按「分类」渲染，
 * 界面上看不出任何异常。一律走下面的 `targetTypeTag()`。
 */
export const TARGET_TYPE_TAG: Record<TargetType, TagType> = {
  CATEGORY: 'primary',
  RANKING: 'warning',
  BOOKLIST: 'success',
}

/**
 * 类型标签文案。
 *
 * <p>取不到映射时**原样回显**（如脏值 `"FOO"` 显示成 `FOO`），不隐藏异常数据 ——
 * 显示成「分类」会让一个数据问题看起来像正常状态。
 */
export function targetTypeLabel(type?: string | null): string {
  if (!type) {
    return '-'
  }
  return TARGET_TYPE_LABEL[type as TargetType] ?? type
}

/** 类型标签取色。取不到时给中性色，同样是「不假装知道」 */
export function targetTypeTag(type?: string | null): TagType {
  if (!type) {
    return 'info'
  }
  return TARGET_TYPE_TAG[type as TargetType] ?? 'info'
}

/**
 * 字典（分类 / 榜单）的类型。
 *
 * ⚠️ 比 {@link TargetType} **少一个 `BOOKLIST`**：书单不进字典表
 * （它是采集的产物、数量上千，有自己的「书单」模块），
 * 所以「字典类型」与「采集目标类型」不是一回事。
 *
 * <p>收窄成独立的类型，是为了让 `listTaxonomy('BOOKLIST')` 这种调用
 * **编译不过** —— 否则它会静默去请求 `/taxonomy/categories`，返回一堆分类当书单用。
 */
export type TaxonomyType = 'CATEGORY' | 'RANKING'

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

/**
 * 图书。
 *
 * ⚠️ 这里是**前端用到的字段子集**，不是后端实体的全部 ——
 * 后端 `Book` 除这 46 个 `BookInfo` 业务字段外，还有 4 个扩展字段、
 * 以及 2026-10-16 新增的 **50 个 `book/info` 补充字段**（`isbn` / `publisher` /
 * `totalWords` / `chapterSize` / `star` / `deepLink` / `aiSummary` …）。
 * 那些字段目前**没有 UI 展示**，所以这里也没声明；要用时照后端字段名加上即可
 * （后端零 `@Field`，库字段名 == Java 字段名）。
 */
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
  /** 付费精选标记（2026-10-20）：1 = 来自付费精选采集；默认 null */
  paidCollect?: number | null
}

/** 图书列表查询条件，对应后端 BookQuery 的 17 个参数 */
export interface BookQuery {
  /** 一级分类 ID（后端匹配 `categories[].categoryId`）。级联框选一级时发这个 */
  categoryId?: number
  /**
   * 二级分类 ID（后端匹配 `categories[].subCategoryId`）。2026-10-12 新增。
   *
   * ⚠️ 与 `categoryId` 是 `categories[]` 里的**两个不同的键** —— 一级走 `categoryId`、
   * 二级走 `subCategoryId`。把二级 code 当 `categoryId` 传会**恒不匹配、静默返回 0 条**。
   * 级联框选到二级时**只**发本字段（不再同时发 `categoryId`）。
   */
  subCategoryId?: number
  minRating?: number
  maxRating?: number
  minReadingCount?: number
  /**
   * 评价标题（`newRatingTitle`），**精确匹配**。2026-10-12 新增。
   *
   * 取值是阅读平台给的档位文案（`神作` / `好评如潮` / `评分不足` …），
   * 候选清单在 `views/book/index.vue` 的 `RATING_TITLE_OPTIONS`（**写死**，刻意不加接口）。
   *
   * ⚠️ 与 `minRating` / `maxRating` 是两回事：那两个筛**数值**（`newRating` 0~1000），
   * 这个筛**档位文案**。
   * ⚠️ 传 `__NONE__` 表示「暂无评级」—— 库里 46% 的书没有这个字段，
   * 后端把它拼成「该字段为 null 或不存在」。
   */
  newRatingTitle?: string
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
  /**
   * 按书单筛（2026-10-16）。值来自 `GET /api/booklists/options`。
   *
   * 后端匹配 `bookLists.bookListId`（书单引用数组，任一元素命中即可）。
   * 与其它筛选条件是 **AND**。
   */
  booklistId?: string
  /**
   * 字数下限（含），2026-10-16 新增。匹配 `totalWords`。
   *
   * ⚠️ `totalWords` 来自 `/book/info`，只有**补过的书**才有 ——
   * 没补过的会被「字数 >= N」排除掉，这是数据决定的。
   */
  minTotalWords?: number
  /**
   * 补齐状态（2026-10-16）。`true` = 补过，`false` = 从没补过，不传 = 不筛这一项。
   *
   * 🔴 有的书平台本来就没有分类/简介，补也补不出来。只用 `missing='BOTH'` 筛的话，
   * 这些书会永远留在结果里，分不清「还没补」还是「补不出来」。
   * 真正的「待补清单」= `missing='BOTH'` **加上** `enriched=false`。
   */
  enriched?: boolean
  /**
   * 付费精选（2026-10-20）。`true` = 只要付费精选，`false` = 排除，不传 = 不筛这一项。
   */
  paidCollect?: boolean
  /**
   * 按「缺哪个字段」筛（2026-10-16）。取值见 `BookMissingField`。
   *
   * 用途：筛出书单链路采进来、缺简介与分类的书，配合「完善书籍信息」批量补齐。
   * 不传 / 认不出的值 = 不筛这一项。
   */
  missing?: BookMissingField
  sortField?: BookSortField
  asc?: boolean
  page?: number
  size?: number
}

/**
 * 「缺哪个字段」筛选项（图书库，2026-10-16）。
 *
 * 为什么要分三档：有些书**本身就没有分类**（平台没给它打标签），补齐也补不出来 ——
 * 只有一个「无分类」档的话，这些书会永远留在筛选结果里，
 * 用户分不清「是还没补」还是「根本补不出来」。
 */
export type BookMissingField = 'CATEGORY' | 'INTRO' | 'BOTH'

/** 筛选项的下拉候选。后端 `BookQuery.resolveMissing()` 只认这三个值 */
export const BOOK_MISSING_OPTIONS: Array<{ label: string; value: BookMissingField }> = [
  { label: '无分类', value: 'CATEGORY' },
  { label: '无简介', value: 'INTRO' },
  { label: '无分类&无简介', value: 'BOTH' },
]

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

/**
 * 书单下拉选项（`GET /api/booklists/options`，2026-10-16）。
 *
 * 图书库页的「书单」筛选下拉用。后端只投影这两个字段 ——
 * 不像 `GET /api/booklists` 那样返回整个书单文档（含简介、头像、计数，228 条能到 1MB 级）。
 */
export interface BooklistOption {
  /** 书单 ID，形如 `391152476_7sKNjWqRN` */
  booklistId: string
  /** 书单名称。还没回填时是 null，展示时用 booklistId 兜底 */
  name?: string | null
}

/**
 * 「完善书籍信息」的**进度快照**（`POST /api/books/enrich` 提交、
 * `GET /api/books/enrich/progress` 轮询）。
 *
 * 后端是「提交即返回 + 轮询」：一次补齐可能要几分钟，同步等会被网关/浏览器掐断。
 * 前端按 `running` 决定要不要继续轮询，用 `processed / requested` 画进度条。
 *
 * 各计数**互斥**，每本书只落进一个桶：
 * `requested = skipped + derived + fetched(updated + failed)`
 */
export interface EnrichResult {
  /** 是否正在跑。前端据此决定按钮禁用与是否继续轮询 */
  running: boolean
  /** 状态描述，直接显示在页面上 */
  message?: string | null
  /** 开始时间（毫秒时间戳）。从没跑过为 0 */
  startedAtMs: number
  /** 结束时间（毫秒时间戳）。运行中为 0 */
  finishedAtMs: number
  /** 去重后的入参书数（进度条**分母**） */
  requested: number
  /** 已处理完的书数（进度条**分子**） */
  processed: number
  /** 需要调 /book/info 的书数（= updated + failed） */
  fetched: number
  /** 调接口成功且写库的书数 */
  updated: number
  /** 调接口失败的书数 */
  failed: number
  /** 未调接口、只本地派生 category 的书数 */
  derived: number
  /** 跳过的书数（库里没有 / 不是今天采集的 / 不缺任何字段） */
  skipped: number
  /** 总耗时（毫秒） */
  costMs: number
}

// ======================================================================
// 接口凭证（com.bookcollector.credential）
// ======================================================================

/**
 * 凭证视图（`GET /api/weread/credential`）。
 *
 * <h3>🔴 2026-10-15 起：明文回显（用户亲自定的口径）</h3>
 * 书单凭证从 3 项扩到 8 项之后，「掩码 + 输入框不回填」的旧口径意味着
 * 用户改任何一个字段都得把 skey 重输一遍 —— 反而催生
 * 「复制粘贴到聊天工具」这种更大的泄漏面。
 * 新口径：表单明文回显、明文保存；敏感值（skey）的防线收敛到
 * <b>不进注释、不进日志</b>（后端 {@code WereadCredential} 上有
 * {@code @ToString.Exclude}，日志只打「配了几项 / 缺哪几项」）。
 *
 * <h3>⚠️ synckey 是只读展示，没有输入框</h3>
 * 用户拍板「自动生成吧，这个不用配置了」，当晚又定了取值口径：
 * 「不是从 root.synckey 取，而是后端 new Date() 的秒值」—— 后端在每次
 * 书单请求发出前按本地时间生成。VO 里的 {@link synckey} 是<b>最近一次
 * 请求用的值</b>，只为在表单下方显示，并可与请求审计对照。
 */
export interface WereadCredentialVO {
  /** 登录态 skey（明文，供回显） */
  skey?: string | null
  /** 最近一次书单请求使用的 synckey（后端按请求时刻本地时间生成，只读展示）。书单 URL 不带它 → 响应拍平、缺 bookInfo 与 intro */
  synckey?: string | null
  /** App 端请求头 channelid */
  channelid?: string | null
  /** App 端请求头 vid（与 skey 同一账号） */
  vid?: string | null
  /** App 端请求头 priority */
  priority?: string | null
  /** App 端请求头 accept-language */
  acceptLanguage?: string | null
  /** App 端请求头 basever（客户端版本号） */
  basever?: string | null
  /** App 端请求头 v（客户端版本号，与 basever 同值） */
  v?: string | null
  /** 书单接口的基础地址（页面上的 sUrl） */
  booklistUrl?: string | null
  /** 分类 / 榜单接口的基础地址 */
  listUrl?: string | null
  /** 书单链路是否可用（八项凭证全配了） */
  booklistReady: boolean
  updatedAt?: string | null
}

/**
 * 保存凭证请求。
 *
 * 🔴 2026-10-15 起：八项书单凭证<b>全量提交</b>，缺任一项后端即拒
 * （「书单配置信息不完整」）。所以没有「留空 = 不改」的语义了 ——
 * 表单每次都回显全部字段，照原样提交即可。
 */
export interface WereadCredentialRequest {
  skey?: string
  channelid?: string
  vid?: string
  priority?: string
  acceptLanguage?: string
  basever?: string
  v?: string
  booklistUrl?: string
  /** 页面不暴露，留空 = 保持原值 */
  listUrl?: string
}

// ======================================================================
// 字典（com.bookcollector.taxonomy.entity.TaxonomyItem）
// ======================================================================

export interface TaxonomyItem {
  id?: string | null
  /** ⚠️ 是 {@link TaxonomyType}（只有分类 / 榜单），不是 TargetType —— 书单不进字典 */
  type: TaxonomyType
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
  /**
   * URL 上的 query 参数（key→value，原样编码态）。
   * 2026-10-15 新增字段 —— 历史记录没有，按「没有」处理
   */
  queryParams?: Record<string, string> | null
  /**
   * 请求头快照。🔴 skey 只会是掩码值（如 `tu****`）—— 后端不存明文登录态
   */
  requestHeaders?: Record<string, string> | null
  /** 返回原始响应 body 原文。后端最长存 8000 字符，超出会截断并拼 "..." */
  rawResponse?: string | null
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

/**
 * 请求审计详情（`GET /api/requests/{id}`，2026-10-15 新增）。
 *
 * 一次请求的**完整现场** + 该次运行的步骤日志 —— 用来判断一次 0 条采集
 * 是「数据访问问题」（rawResponse 里就没数据）还是「解析问题」（有数据没解析出来）。
 */
export interface RequestDetail {
  /** 审计记录本体（含 queryParams / requestHeaders / rawResponse） */
  record: ApiRequest
  /** 该记录所属运行的核心步骤日志，按时间正序；无 runId 时为空数组 */
  logs: TaskLog[]
}

/**
 * 「回放」的请求体（POST /api/requests/replay）。
 *
 * 🔴 URL 是唯一真相：弹窗里的「请求参数」行是 URL query 的可编辑视图
 * （改参数行 → 前端重建 query 回写 URL；改 URL → 前端重新解析出行），
 * 所以后端不单独收 params —— 收两份必然出现「不一致时听谁的」的歧义。
 *
 * ⚠️ requestHeaders.skey 是掩码值：后端对 skey 一律用当前内存凭证的值替换
 * （书单域名强制替换，其余域名发了也换），绝不会把掩码真发出去。
 */
export interface ReplayRequest {
  /** 这次要发的完整 URL（含 query，弹窗里改过的那份） */
  url: string
  /** 请求头（skey 会被后端替换成当前凭证的值） */
  requestHeaders: Record<string, string>
}

/**
 * 「回放」一次请求的结果（POST /api/requests/replay）。
 *
 * 🔴 body 是原始 HTTP 响应的**原文**：后端刻意不过 fastjson、不重序列化，
 * 字节 → UTF-8 解码 → 原样返回。排查时要看的就是「服务端到底发了什么」。
 *
 * ⚠️ requestHeaders.skey 是掩码值（与审计记录同一口径）。
 * statusCode 为 0 = 请求没发出去（网络异常等），此时 errorMsg 有值。
 * responseSynckey = 本次响应 body 里的 synckey（取不到为 null）——
 * 与记录里那个「当时的 synckey」是两回事，对照两者能看出数据源推进了没有。
 */
export interface ReplayResult {
  /** 实际发出的 URL（= 提交的那份） */
  url: string
  /** 实际发出 URL 的 query 拆解（编码态原样） */
  queryParams: Record<string, string> | null
  /** 实际发送的请求头（skey 掩码；提交了真值就发真值，掩码/空则用当前凭证） */
  requestHeaders: Record<string, string> | null
  /**
   * 本次发出的 skey 来自哪里 —— 回答「你额外做了什么」：
   * - `SUBMITTED` 用了提交的真值（想完全复刻 Postman 时就是这种）
   * - `CREDENTIAL` 提交的是掩码 / 留空，已替换为当前凭证的 skey
   * - `null` 本次没发 skey 头
   */
  skeyFrom?: string | null
  /**
   * 响应形状的**一行指纹** —— 直接回答「为什么没有 bookInfo」：
   * `错误信封：errcode=-2012（登录超时）` / `书单响应 499 条；books[0] 无 bookInfo（拍平形态），元素键=[…]` /
   * `书单响应 499 条；books[0] 带 bookInfo（完整形状）`。
   * 只是诊断提示，body 本身仍是原文。
   */
  shapeHint?: string | null
  /** HTTP 状态码；没发出去时为 0 */
  statusCode: number
  /** 耗时（毫秒） */
  costMs: number
  /** 本次响应 body 里的 synckey；取不到为 null */
  responseSynckey?: number | null
  /** 原始 body 原文；无响应体时为空串 */
  body: string
  /** 失败原因（异常摘要）；成功为 null */
  errorMsg?: string | null
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

/**
 * 「重置为」下拉里可选的目标状态（2026-10-16 新增）—— **只有这两个**。
 *
 * <p>刻意从 `PublishStatus` 里用 `Extract` 抠出来，而不是另写一遍字面量联合：
 * 以后枚举改了名，这里会跟着报错，不会留一个对不上的影子类型。
 *
 * <p>为什么不给 `GENERATING` / `FAILED`（后端也会拒）：
 * 「生成中」必须由挑书流程设置（它有 job 句柄、会被轮询推进），手设就是假死；
 * 「失败」必须带 `errorMsg`，光设个状态页面上讲不出故事。
 */
export type InterpretationResetTarget = Extract<PublishStatus, 'PENDING' | 'PUBLISHED'>

/** 「重置为」下拉的选项顺序与文案 —— 待解读在前（更常用） */
export const INTERPRETATION_RESET_TARGETS: InterpretationResetTarget[] = ['PENDING', 'PUBLISHED']

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

  // ---- 发布范围（只读展示，来自 pick_taxonomy_config，用户自己维护；发布挑书按它过滤）----
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
  /** 「重置精选」（原「初始化候选」，入口在「精选图书」页）是否正在执行 */
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
  /** 阅读人数（2026-10-13 加） */
  readingCount?: number | null
  /**
   * 好评 / 中评 / 差评数（2026-10-13 加）。
   *
   * 来自快照里的 `newRatingDetail.{good,fair,poor}` —— **嵌套路径**，不是顶层字段。
   * 后端取不到时给 `null`（页面显示「-」）。
   */
  goodCount?: number | null
  fairCount?: number | null
  poorCount?: number | null

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
  /**
   * 本次命中的敏感词**原词列表**（2026-10-13 加）。
   *
   * ⚠️ 与 `sensitiveHitCount` 是**同一个事实的两种粒度**（个数 = 列表长度）。
   * 这里只有**最近一次**的：`book_interpretations` 一本书只有一行，状态会被覆盖；
   * 想看历史每次替换了什么词，去「发布记录」页。
   */
  sensitiveHits?: string[] | null
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
 * 候选清单的**排序字段**白名单（2026-10-13 加）。
 *
 * ⚠️ 后端只认这 6 个，其余一律**回落 `newRating`**（不报错）——
 * 见 `InterpretationQuery.SORT_PATHS`。所以字段名拼错**不会报错**，
 * 只会表现为「点了表头但排序没生效」。
 *
 * ⚠️ 三个「数」在库里是**嵌套路径** `newRatingDetail.{good,fair,poor}`；
 * 前端传的是这里的 key（`goodCount` 等），key → 路径的映射在后端做。
 */
export type InterpretationSortField =
  | 'newRating'
  | 'newRatingCount'
  | 'readingCount'
  | 'goodCount'
  | 'fairCount'
  | 'poorCount'

/**
 * 候选清单的查询条件，对应后端 `InterpretationQuery`。
 *
 * <h3>与 `BookQuery` 的关系</h3>
 * 候选清单是图书库的**完整快照**，所以筛选条件基本一致。刻意**不含**
 * `targetType` / `targetId`（采集来源）—— 在「找出某本书并删掉它」这个场景下没用。
 *
 * ⚠️ 平台分类**两级**（2026-10-13 起）：选一级发 `categoryId`、选到二级发
 * `subCategoryId`，**两者不会同时有值**（同图书库的级联框口径）。
 */
export interface InterpretationQuery {
  /** PENDING / GENERATING / PUBLISHED / FAILED，空 = 全部 */
  status?: PublishStatus | ''
  /** 书名关键词，包含匹配 */
  title?: string
  /** 作者关键词，包含匹配 */
  author?: string
  /** 一级分类 code（与 `subCategoryId` 互斥） */
  categoryId?: number
  /** 二级分类 code（与 `categoryId` 互斥）。2026-10-13 加 */
  subCategoryId?: number
  minRating?: number
  maxRating?: number
  minReadingCount?: number
  minPublishTime?: string
  maxPublishTime?: string
  /**
   * 排序字段。不传 → 后端按 `newRating` **降序**（用户 2026-10-13 指定的默认）。
   *
   * ⚠️ 后端只认 {@link InterpretationSortField} 那 6 个，其余回落 `newRating`。
   */
  sortField?: InterpretationSortField
  /** 排序方向：`true` = 升序；`false` / 不传 = 降序 */
  asc?: boolean
  page?: number
  size?: number
}

/**
 * 「精选配置」的筛选条件（2026-10-12 新增）。
 *
 * `POST /api/publish/candidates/preview`（只看）与 `POST /api/publish/candidates/collect`
 * （写入候选清单）**共用这一份条件** —— 这样「预览到什么、精选的就是什么」在结构上成立。
 *
 * <h3>🔴 推荐值是百分制</h3>
 * `minRating` 填的是 `0~100`（与列表里显示的「91.3%」同口径），
 * 库里 `books.newRating` 存的是 `0~1000`。**换算由后端做** ——
 * 前端换算的话，哪一处忘了乘 10 就会静默筛错一个数量级。
 */
export interface CandidateBuildRequest {
  /**
   * 平台分类（一级）code 列表，**必填非空**。匹配 `books.categories[].categoryId`。
   *
   * ⚠️ 只能传一级分类的 code。二级的是 `subCategoryId`（另一个字段），
   * 传进来恒不匹配、静默返回 0 条 —— 与图书库同源的坑。
   */
  categoryIds?: number[]
  /** 评价标签（`books.newRatingTitle`），如 `['神作', '好评如潮']` */
  ratingTitles?: string[]
  /** 推荐值下限（**百分制 0~100**，含） */
  minRating?: number
  /** 书名关键词，包含匹配 */
  title?: string
  /** 作者关键词，包含匹配 */
  author?: string
  /** 隐藏已精选：把候选清单（`book_interpretations`）里已有的 bookId 排除掉 */
  excludeCollected?: boolean
  /** `true` = 全部精选（忽略 `bookIds`，按条件全量写入） */
  all?: boolean
  /** 批量精选：当页勾选的 bookId 列表 */
  bookIds?: string[]
}

/**
 * 精选预览的一行 —— 图书库里的书，**尚未**进候选清单。
 *
 * ⚠️ 只有 8 个字段：图书库的 `Book` 有 46 个（含几千字的 `intro`），
 * 预览一页 20 条没必要背那些负载。想看完整信息去图书库页。
 */
export interface CandidateBookVO {
  bookId: string
  title?: string | null
  author?: string | null
  /** 平台分类，如 文学-经典作品 */
  category?: string | null
  /** 推荐值 0~1000（913 = 91.3%） */
  newRating?: number | null
  /** 评价人数 */
  newRatingCount?: number | null
  /** 阅读人数 */
  readingCount?: number | null
  /** 评价标签，如 神作 / 好评如潮 */
  newRatingTitle?: string | null
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
  /**
   * 本次命中的敏感词**原词列表**（2026-10-13 加）。
   *
   * <p>⚠️ 2026-10-13 之前的记录**没有这个字段**（那时只存了个数）→ `null`。
   * 「合规替换」列点开弹窗就是靠它列出替换了哪些词。
   */
  sensitiveHits?: string[] | null
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

/** ==================================================================
 * 阅读清单（13. 阅读清单）
 * ================================================================== */

/**
 * 阅读状态。三态。
 *
 * <h3>与 `PublishStatus` 毫无关系</h3>
 * 那个是 AI 解读在流水线上的位置（待解读 / 生成中 / 已发布 / 失败），
 * 这个**人的阅读进度**（想读 / 在读 / 已读）。名字都带 status，但语义、
 * 生命周期、归属模块完全不同 —— 不要互相复用。
 */
export type ReadingStatus = 'WANT_TO_READ' | 'READING' | 'FINISHED'

export const READING_STATUS_LABEL: Record<ReadingStatus, string> = {
  WANT_TO_READ: '想读',
  READING: '在读',
  FINISHED: '已读',
}

export const READING_STATUS_TAG: Record<ReadingStatus, TagType> = {
  WANT_TO_READ: 'info',
  READING: 'primary',
  FINISHED: 'success',
}

/**
 * 阅读清单里的一本书（快照）。
 *
 * <h3>数据源</h3>
 * 后端集合 `reading_list`。字段是**加入阅读清单那一刻**的快照 ——
 * 图书库里的记录后来被改过或被删过，都不影响这里。
 *
 * <h3>与「我的藏书」(`ShelfBook`) 是两个维度</h3>
 * 收藏 ≠ 想读，两者不可互相推导，也不可互相影响（移除一个不影响另一个）。
 */
export interface ReadingListItem {
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
  status?: ReadingStatus | null
  /** 状态的中文文案，后端已给，前端不用再维护一份映射 */
  statusLabel?: string | null
  /** 状态最后变更时间（UTC ISO-8601，格式化必须过 `new Date()`） */
  statusUpdatedAt?: string | null
}

/** 批量查阅读清单状态的返回项。只含**已在清单里**的书 */
export interface ReadingListStatusItem {
  bookId: string
  status: ReadingStatus
}

/** 各状态计数（给状态 Tab 的角标） */
export interface ReadingListSummary {
  wantToRead: number
  reading: number
  finished: number
  /** 总数。以库为准，不一定等于前三项之和（万一混进非法状态） */
  total: number
}

// ======================================================================
// 书单（com.bookcollector.booklist）
// ======================================================================

/**
 * 平台分类引用（`booklists.platformCategories[]` 的元素）。
 *
 * ⚠️ `categoryId` 是**字符串**，形如 `"200000"` 甚至 `"topic"`。
 * 与 `BookCategory.categoryId`（**number**）不是一回事 ——
 * 拿字符串去匹配 int 字段会恒 0 条且不报错，本项目踩过这个坑。
 */
export interface BooklistPlatformCategory {
  /** 分类 ID，字符串。`"topic"` = 主题书单 */
  categoryId: string
  /** 分类名，如 `历史` */
  title?: string | null
}

/**
 * 书单的**采书状态**。
 *
 * ⚠️ 取值是后端 `BooklistCollectStatus` 枚举的**名字**。刻意**没有** `COLLECTING`：
 * 「正在采」的唯一真相来源是任务表（`collect_tasks.status`），在书单表再记一份
 * 会造出「强杀后永远卡在采书中」的死结。
 */
export type BooklistCollectStatus = 'PENDING' | 'COLLECTED' | 'FAILED'

export const BOOKLIST_COLLECT_STATUS_LABEL: Record<BooklistCollectStatus, string> = {
  PENDING: '待采',
  COLLECTED: '已采',
  FAILED: '失败',
}

export const BOOKLIST_COLLECT_STATUS_TAG: Record<BooklistCollectStatus, TagType> = {
  PENDING: 'info',
  COLLECTED: 'success',
  FAILED: 'danger',
}

/** 采书状态文案。认不出的脏值**原样回显**，不假装是「待采」 */
export function booklistStatusLabel(status?: string | null): string {
  if (!status) {
    return '-'
  }
  return BOOKLIST_COLLECT_STATUS_LABEL[status as BooklistCollectStatus] ?? status
}

/** 采书状态取色。认不出给中性色 */
export function booklistStatusTag(status?: string | null): TagType {
  if (!status) {
    return 'info'
  }
  return BOOKLIST_COLLECT_STATUS_TAG[status as BooklistCollectStatus] ?? 'info'
}

/**
 * 一张书单（后端集合 `booklists`）。
 *
 * <h3>两个写入者，各写各的字段</h3>
 * - **链路 A**（发现书单）：`name` / `description` / `author*` / 各计数 / 各时间 /
 *   `platformCategories` / `lastSeenAt`
 * - **链路 B**（采书）：`collectStatus` / `bookCountCollected` / `lastCollectedAt`
 *
 * 所以「同步一次书单」**不会**把 `collectStatus` 冲回 `PENDING`（后端靠 `$setOnInsert` 守住）。
 */
export interface Booklist {
  id?: string | null
  /** 书单 ID，形如 `352003193_8cKur0WC3`。★ 也是任务的 `targetId` */
  booklistId: string
  /**
   * 书单名。
   *
   * ⚠️ **可能为 null / 空串**：用户自己的 124 张「我的书单」由一次性脚本预置，
   * 那批书单**只有 ID 没有名称**，名称要等链路 B 启动时回填。
   * 展示一律走 {@link booklistDisplayName}。
   */
  name?: string | null
  description?: string | null
  /** 创建者 ID。后端是 Long，防止 int 静默溢出 */
  userVid?: number | null
  authorName?: string | null
  authorAvatar?: string | null
  /**
   * 书单里有几本书。★ 采集闸门 1（`>= 50`）看它。
   *
   * ⚠️ 接口里这个字段叫 `totalCount`，与本表改名过 —— 见后端 `Booklist` 的类注释。
   */
  bookCount?: number | null
  /** 收藏人数。★ 采集闸门 2（`>= 10000`），也是「提前终止」的判据 */
  collectCount?: number | null
  likedCount?: number | null
  shareCount?: number | null
  /** 创建时间（UTC ISO 字符串，格式化必须过 `new Date()`） */
  createTime?: string | null
  updateTime?: string | null
  lastReadTime?: string | null
  /** 这张书单出现在哪些平台分类下。同一张书单可能挂在多个分类里 */
  platformCategories?: BooklistPlatformCategory[] | null
  /** 采书状态（枚举名）。见 {@link BooklistCollectStatus} */
  collectStatus?: string | null
  /** 实际采到几本书。与 `bookCount` 对不上 = 书单被改过或采集时被过滤 */
  bookCountCollected?: number | null
  /** 首次发现时间。upsert 时用 `$setOnInsert` 写入，之后不再改 */
  firstSeenAt?: string | null
  /** 最近一次被采集链路扫到的时间 */
  lastSeenAt?: string | null
  /** 最近一次采书时间（链路 B）。没采过时为 null */
  lastCollectedAt?: string | null
}

/** 书单展示名：没有名称时**回退到 ID**（用户明确要求的口径） */
export function booklistDisplayName(b: { name?: string | null; booklistId: string }): string {
  const name = b.name?.trim()
  return name ? name : b.booklistId
}

/** 是否「还没有名称」= 等待链路 B 启动时回填 */
export function isBooklistUnnamed(b: { name?: string | null }): boolean {
  return !b.name || b.name.trim() === ''
}

/** 书单列表查询条件，对应后端 `BooklistQuery` */
export interface BooklistQuery {
  /** 关键词，**同时**匹配 `name` 与 `booklistId`（包含匹配） */
  keyword?: string
  /** 平台分类 ID（**字符串**，匹配 `platformCategories[].categoryId`） */
  categoryId?: string
  /** 采书状态（枚举名） */
  collectStatus?: BooklistCollectStatus | ''
  minCollectCount?: number
  maxCollectCount?: number
  minBookCount?: number
  maxBookCount?: number
  /** 只看「还没有名称」的（= 等待链路 B 回填） */
  nameMissing?: boolean
  sortField?: BooklistSortField
  asc?: boolean
  page?: number
  size?: number
}

/** 排序字段白名单。后端 `resolveSortPath()` 里不认识的值会 fallback 到 `collectCount` */
export type BooklistSortField =
  | 'collectCount'
  | 'bookCount'
  | 'bookCountCollected'
  | 'lastSeenAt'
  | 'firstSeenAt'
  | 'lastCollectedAt'

export const BOOKLIST_SORT_OPTIONS: Array<{ label: string; value: BooklistSortField }> = [
  { label: '收藏人数', value: 'collectCount' },
  { label: '书单书籍数', value: 'bookCount' },
  { label: '已采书籍数', value: 'bookCountCollected' },
  { label: '最近扫到', value: 'lastSeenAt' },
  { label: '首次发现', value: 'firstSeenAt' },
  { label: '最近采集', value: 'lastCollectedAt' },
]

/**
 * 「同步书单」（链路 A）的进度快照（后端内存快照，不落库）。
 *
 * ⚠️ `running` 来自后端**内存**，所以刷新页面之后还能接着轮询，
 * 不会卡在「执行中」；但进程重启后进度归零。
 */
export interface BooklistSyncVO {
  /** 是否正在跑。前端据此决定按钮禁用与是否继续轮询 */
  running: boolean
  /** 本次要遍历的分类总数（**不固定是 16** —— 用户可能只勾了几个） */
  totalCategories: number
  /** 已跑完的分类数 */
  categoriesDone: number
  /** 当前正在跑的分类 ID。没在跑时为 null */
  currentCategoryId?: string | null
  /** 当前正在跑的分类名，如「历史」 */
  currentCategoryTitle?: string | null
  /** 已发出的请求页数 */
  pagesFetched: number
  /** 扫到的书单条数（含被闸门挡掉的） */
  booklistsSeen: number
  /** 入库条数（更新 + 新增） */
  booklistsSaved: number
  /** 其中新增条数 */
  booklistsInserted: number
  /** 被闸门挡掉的条数（书籍数 < 50，或收藏人数 < 10000） */
  booklistsSkipped: number
  /**
   * 第 1 页就返回空列表的分类数 —— **正常应为 0**。
   *
   * 服务端「返回空」与「该分类真的没书单」在结果上一样，所以单列出来：
   * 非 0 就说明有分类一个书单都没返回，需要去看后端日志（tag / skey / sUrl）。
   */
  emptyFirstPageCategories: number
  /** 当前状态的一句话描述，直接显示在页面上 */
  message?: string | null
  /** 本次开始时间（毫秒时间戳，**不是** ISO 字符串） */
  startedAtMs?: number | null
  /** 本次结束时间（毫秒时间戳） */
  finishedAtMs?: number | null
  /** 失败原因。成功时为 null */
  errorMsg?: string | null
}

/**
 * 「同步某张书单的书籍」（链路 B）的进度快照（2026-10-20）。
 *
 * 数据取自该书的采集任务与最近一次运行（TaskRun）；`running` 为 true 时前端继续轮询。
 */
export interface BooklistCollectVO {
  booklistId: string
  taskId?: string | null
  runId?: string | null
  /** TaskStatus 枚举名：PENDING / RUNNING / PAUSED / SUCCESS / FAILED / CANCELED / INTERRUPTED */
  status: string
  /** 状态中文名 */
  statusLabel: string
  running: boolean
  pagesDone?: number | null
  booksSaved?: number | null
  booksInserted?: number | null
  booksExcluded?: number | null
  /** 该目标声明的总书数，用于算进度百分比 */
  totalCount?: number | null
  /** ISO 字符串（后端 Date 序列化） */
  startedAt?: string | null
  finishedAt?: string | null
  errorMsg?: string | null
}

/** 「一键完善书单信息」的进度快照（2026-10-20） */
export interface BooklistBackfillVO {
  running: boolean
  total: number
  processed: number
  updated: number
  failed: number
  currentBooklistId?: string | null
  message?: string | null
  startedAtMs?: number | null
  finishedAtMs?: number | null
  errorMsg?: string | null
}

/**
 * 同步请求。
 *
 * ⚠️ `categoryIds` 不传 / 传空数组 = **全部 16 个分类**。
 * 传了清单外的 ID 返回 code=400（不是静默忽略）。
 */
export interface BooklistSyncRequest {
  categoryIds?: string[]
}

// ---------------------------------------------------------------------------
// 付费精选配置（2026-10-20）
// ---------------------------------------------------------------------------

/** 付费精选配置的一行：taxonomy_config 一级 + enabled=true 的分类 */
export interface PaidCategoryVO {
  /** 分类 code（= filterCategory） */
  code: string
  /** 分类名 */
  name: string
  /** 最近一次状态：RUNNING / PAUSED / INTERRUPTED / COMPLETED / STOPPED / FAILED；没跑过为 null */
  lastStatus?: string | null
  /** 已翻页数 */
  page?: number
  /** 接口声明的总书数 */
  totalCount?: number | null
  /** 断点最后更新时间 */
  updatedAt?: string | null
}

/** 「付费精选」采集的进度快照 */
export interface PaidCollectVO {
  running: boolean
  /** 是否已暂停（暂停时 running 仍为 true） */
  paused: boolean
  filterCategory?: string | null
  filterCategoryName?: string | null
  totalCount?: number | null
  /** 已翻页数 */
  page: number
  /** 累计解析到的书数 */
  booksSaved: number
  /** 新增入库数 */
  booksInserted: number
  /** 已存在、只补了来源/标记的数 */
  booksUpdated: number
  message?: string | null
  startedAtMs?: number | null
  finishedAtMs?: number | null
  errorMsg?: string | null
}
