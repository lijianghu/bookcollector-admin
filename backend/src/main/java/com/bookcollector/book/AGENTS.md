# book 模块

> `com.bookcollector.book` · 图书库的读写（不负责采集）。

## 职责

分页筛选、详情、编辑、单条 / 批量删除；「完善书籍信息」异步补齐。

## 关键类

| 类 | 职责 |
|---|---|
| `controller/BookController` | 7 个端点，薄转发 |
| `service/BookService(Impl)` | 分页 / 详情 / 编辑 / 删除 / 批量删除；编辑只写传了的字段 |
| `service/BookEnrichService` | 完善书籍信息：`/book/info` 逐本补齐，5 并发 + 1~5s 随机；异步 + 内存进度 |
| `repository/BookRepository` | 分页、`upsertMany`、`upsertPaidMany`、局部更新、批量删 |
| `req/BookQuery` | 筛选条件 + 排序白名单 + 分页归一化 |
| `entity/Book` | 46 业务字段 + 4 扩展 + 50 个 `/book/info` 补充字段 + 采集元数据 |

## 数据与索引

- `books`：`uk_bookId` 唯一（幂等写入的正确性依赖）、`idx_categories`、`idx_lastCollectedAt`、`idx_newRating`、`idx_firstCollectedAt`。
- `lastCollectedAt` 每次 upsert 刷新；统计「今日新增」必须用 `firstCollectedAt`。
- `paidCollect`（Integer）：`1` = 来自付费精选；默认 `null`。

## 接口

| 方法 | 路由 | 说明 |
|---|---|---|
| GET | `/api/books` | 分页筛选 |
| GET | `/api/books/{bookId}` | 详情 |
| PUT | `/api/books/{bookId}` | 局部更新（只写传了的字段） |
| DELETE | `/api/books/{bookId}` | 单条删除（下次采集会重新写入） |
| POST | `/api/books/batch-delete` | 批量删除（返回实际删除数） |
| POST | `/api/books/enrich` | 完善书籍信息（提交即返回） |
| GET | `/api/books/enrich/progress` | 进度（内存态） |

## 筛选

- 分类：`categoryId`（一级）/ `subCategoryId`（二级），**不能混用**（传错恒 0 条）。
- 关键词：`title` / `author` **包含匹配**（未锚定正则，全集合扫描已接受）。
- 评分：`minRating` / `maxRating`（库值 0~1000）；`newRatingTitle` 是档位文案精确匹配，`__NONE__` = 暂无评级。
- 来源：`targetType` + `targetId`；`booklistId` 匹配 `bookLists.bookListId`。
- 补齐：`missing`（CATEGORY / INTRO / BOTH）、`minTotalWords`、`enriched`。
- 付费精选：`paidCollect`（`true` = 只要 `1`；`false` = 排除 `1` 与不存在）。
- 排序白名单：`newRating` / `readingCount` / `newRatingCount` / `lastCollectedAt` / `publishTime`。

## 不变量

1. 排序字段必须走 `BookQuery#resolveSortPath()` 白名单，不能把用户字符串拼进 Mongo 路径。
2. `newRating` 是**顶层字段**，不是 `newRatingDetail.newRating`（后者只有 good / fair / poor / recent / title）。
3. 编辑只写请求体里出现的字段（`Update.set` 逐字段）；绝不整本 `save()`（隐式清空）。
4. 不可改字段（`bookId` / `searchIdx` / `collectSource` / `firstCollectedAt`）在 `BookUpdateRequest` 里直接不存在。
5. 批量删除返回**实际删除数**（列表可能是旧快照）。
6. `maxPublishTime` 粗粒度补齐（`2023` → `2023-12-31 23:59:59`）：`publishTime` 是定长字符串、按字典序比较。
7. `upsertMany` 用 `bulkOps(UNORDERED)` + 手搓 `Document`（`$set` + `$setOnInsert` + `$addToSet`）；`_id` 不能进 `$set`。
8. `upsertPaidMany`：不存在才插入全字段；已存在只 `$addToSet collectSource` + `$set paidCollect=1`，不覆盖其它字段。
9. 按 id 批量查 / 删按 `IN_QUERY_CHUNK = 1000` 分片。
