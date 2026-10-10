# shelf 模块知识

> 包路径：`com.bookcollector.shelf` · 最后更新：2026-10-10
> **本模块 2026-10-10 从「只读视图」改成了「自己的一张表」。改之前先读第 6 节。**

## 1. 这个模块是干什么的

「藏书阁」页 —— 沉浸式浏览（**10 列网格 + 下滑加载**），以及**图书库操作列的「添加藏书 / 移除藏书」**。

数据是自己的集合 `shelf_books`（**快照式**），不再是 `book_interpretations`（AI 解读候选清单）
的只读投影 —— 那是第一版的形态。

## 2. 类清单

| 类 | 角色 | 一句话职责 |
|---|---|---|
| `controller/ShelfController` | Controller | 5 个端点（类目 / 分页取书 / 加入 / 移除 / 批量查状态） |
| `service/ShelfService` | Service 接口 | 5 个方法 |
| `service/impl/ShelfServiceImpl` | Service 实现 | 参数归一化 + 加藏书时取快照 |
| `repository/ShelfBookRepository` | Repository | `shelf_books` 读写 + 类目聚合 + 从 `books` 取快照 |
| `entity/ShelfBook` | 实体 | 集合 `shelf_books`：9 个快照字段 + `addedAt` |
| `vo/ShelfBookVO` | VO | 对外投影（9 个展示字段，**不吐 `_id` / `addedAt`**） |
| `vo/ShelfCategoryVO` | VO | 类目名 + 数量 |
| `req/ShelfAddRequest` | 请求体 | 只带 `bookId` |
| `req/ShelfStatusRequest` | 请求体 | `List<String> bookIds` |

## 3. 调用关系（图谱）

```
ShelfController ──► ShelfService(Impl) ──► ShelfBookRepository ──┬──► shelf_books（读写 + 类目聚合）
                                                                 └──► books（**只读**：按 bookId 取 9 字段快照）

前端：views/shelf/index.vue（藏书阁页）· views/book/index.vue（操作列）──► api/shelf.ts
```

## 4. 数据集合与索引

| 集合 | 用途 | 依赖索引 |
|---|---|---|
| `shelf_books` | 藏书阁自己的表（快照） | `uk_bookId`（**唯一**，幂等加入的正确性依赖）、`idx_category_addedAt (category, addedAt DESC, _id)` |
| `books` | 加藏书时按 `bookId` 取 9 字段快照（**只读**） | `uk_bookId` |

索引在 `config.MongoIndexInitializer.initShelfBooks()` 里建（**2 个**），
它同时是 `shelf_books` 集合的**唯一建表入口**。

## 5. 对外接口

| 方法 | 路由 | 返回 |
|---|---|---|
| GET | `/api/shelf/categories` | `[{ name, count }]`，按数量降序 |
| GET | `/api/shelf/books?category&page&size` | `PageResult<ShelfBookVO>`（默认 `size=30`，上限 200） |
| POST | `/api/shelf/books` | `Boolean`（`true`=新加入；`false`=本来就在） |
| DELETE | `/api/shelf/books/{bookId}` | `Boolean`（`true`=确实删了；`false`=本来就不在） |
| POST | `/api/shelf/status` | `Set<String>`（已在藏书阁的 `bookId`） |

## 6. 不变量与铁律 ← 改这个模块前必读

1. **🔴 字段是「快照」不是「引用」。**
   书名 / 作者 / 封面 / 分类 / 评分 / 简介都是**加入藏书阁那一刻**的值。
   图书库后来改了或删了，都**不该**改写藏书阁 —— 否则「我收藏的是什么」就查不出来了。
   代价：图书库删掉一本书后，藏书阁里**仍会留着它**（要靠「移除藏书」清）。这是刻意换来的。

2. **🔴 加藏书必须 `snapshot.setId(null)`。**
   `readBookSnapshot()` 用 `findOne(query, ShelfBook.class, "books")` 读，读回来的对象
   **带着 books 的 `_id`**。不清掉就直接 `insert`，插进去的是同一个 `_id` ——
   第二次加同一本之外的任何书都可能撞键。见 `ShelfServiceImpl.add()`。

3. **🔴 列表排序是「评分优先」，不是 `addedAt`。**
   `newRating DESC` → `newRatingCount DESC` → `_id DESC`。
   主序沿用旧版书架「评分最高的先看」；后两级只为**确定性** ——
   分页的正确性依赖排序是个**全序**，否则同分的书在翻页时会重复 / 漏。
   ⚠️ **不能用 `addedAt` 排**：迁移进来的 4400 本 `addedAt` 是**同一时刻**，按它排 = 随机序。
   `addedAt` 留着是为了以后做「最近加入」视图 / 排序。

4. **🔴 本模块不依赖任何业务模块（单向叶子）。**
   - 从 `books` 取快照时，`BOOKS_COLLECTION = "books"` 是**字符串常量**，
     **不 import `book` 模块的实体**（字段名两边一致，项目零 `@Field`，按名字投影即可）。
   - 反向也守住：「这本书是否在藏书阁」**不由图书库接口返回**，而是前端拉完图书库列表后
     带当前页 `bookId` 调 `POST /api/shelf/status` 问一次 —— 这样 `book` 模块也**不用**依赖 `shelf`。
   - 新增能力优先在本模块内解决，别为了复用去给别的模块加方法。

5. **`category` 是「一级-二级」拼串**（如 `文学-散文杂著`），一级 = 第一个 `-` 之前。
   匹配用 `^\Q文学\E(-|$)`：前缀匹配 + 也接受裸值 + `Pattern.quote` 转义。
   ⚠️ **必须转义** —— 类目名是用户输入，不转义的话 `.*` 能查出全表。

6. **类目聚合先 `$match` 掉非字符串 / 空串的 `category`。**
   `$split` 对非字符串会**直接报错**，一条脏数据就能让整个类目导航 500。

7. **不存正文**（`content` / `rawContent`）。藏书阁只用来「看」，存了只会让表和查询都变重。

8. **参数归一化在 Service 层**：`topCategoryOf()`（截到一级，避免「传了但查不到还不报错」）、
   `normalizePage()`（`>=1`）、`normalizeSize()`（默认 30 / 上限 200，防 `size=99999`）。

## 7. 依赖与耦合

- **依赖**：`MongoTemplate`、`common.BizException` / `common.PageResult`、Lombok。
  **不 import 任何业务模块的类。**
- **被依赖**：前端「藏书阁」页（`views/shelf/`）+ 图书库页操作列（`views/book/index.vue`）。
- 前端侧：`api/shelf.ts`（5 个函数）、`views/shelf/components/ShelfBookCard.vue`（网格卡片）、
  `CategoryNav.vue`（类目导航，**直接喂后端返回的全量 categories**，没有前端白名单）。

## 8. 测试与验收

**无模块专属测试**（按用户硬规则 4：不写单测）。验收走前端：

- 藏书阁页：10 列网格 / 每页 30 条 / 下滑加载 / 类目导航按数量降序
- 图书库操作列：「添加藏书 / 移除藏书」按该书是否在架上切换文案

## 9. 已知陷阱（现象 → 根因 → 修法）

### 9.1 加藏书报 `E11000` / 第二次加不进

- **现象**：给一本书「添加藏书」时报 duplicate key。
- **根因**：`readBookSnapshot()` 读回来的对象**带着 books 的 `_id`**，没清就 insert。
- **修法**：`snapshot.setId(null)`（铁律 2）。并发下两次「添加」撞 `uk_bookId` 由
  `catch (DuplicateKeyException)` 兜底 → 视为「已存在」返回 `false`，**不报错**。

### 9.2 类目导航 500 / 某个类目凭空消失

- **现象**：`GET /api/shelf/categories` 报错，或某个类目的书不见了。
- **根因**：`category` 有**非字符串 / 空串**的脏值 → `$split` 直接报错；
  或者 `category` 为空 → 归不到任何一级，被聚合跳过。
- **修法**：管道首段 `$match { category: { $type: 'string', $ne: '' } }`（铁律 6）。
  数据侧可用 `tools/migrate_shelf_books.py` 的 dry-run 报告核对（它会统计空 `category` 条数）。

### 9.3 翻页重复 / 漏书

- **现象**：下滑加载时同一本书出现两次，或某本永远看不到。
- **根因**：排序不是全序（只按 `newRating` 排，同分的书在两次查询间顺序不稳定）。
- **修法**：排序补到三级（铁律 3）。

### 9.4 迁移进来的书「顺序随机」

- **现象**：`shelf_books` 迁完之后，翻页顺序看不出规律。
- **根因**：迁移脚本一次性写入，4400 本 `addedAt` 是**同一时刻** → 按它排等于随机序。
- **修法**：列表不按 `addedAt` 排（铁律 3）。

### 9.5 索引的后两段不再匹配排序

- **现象**：`idx_category_addedAt` 里带着 `addedAt DESC` / `_id DESC`，但列表已按评分排。
- **根因**：2026-10-10 排序从「按加入时间」改成「评分优先」，索引定义没跟着改。
- **为什么不改**：`category` 是**前缀范围**（不是等值），排序本来就用不上索引的后继字段 ——
  实际由 MongoDB 做内存排序，集合只有几千条，可接受。
  且 `ensureIndex` **不会删旧索引**，改名会在库里留一个孤儿索引。

## 10. 数据迁移（一次性，已执行）

`tools/migrate_shelf_books.py` —— 把 `book_interpretations`（候选清单，每条本就是 `books`
的完整 BSON 快照）投影进 `shelf_books`：补 `_class` + `addedAt`、**不带 `_id`**、
按 `bookId` 去重（**幂等**）、默认 dry-run、`--apply` 时落备份。

**2026-10-10 已执行**：4400 条。

⚠️ 脚本在 `tools/` 下，而 `.gitignore:37` 忽略了整个 `/tools/` → **不进仓库**（备份 json 同理）。
