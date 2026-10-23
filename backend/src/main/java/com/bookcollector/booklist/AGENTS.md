# booklist 模块

> `com.bookcollector.booklist` · 书单池（App 端 `/booklist/*`）。

## 职责

- 书单发现（链路 A）：按 16 个平台分类分页拉书单，闸门 书数 ≥50 且 收藏 ≥10000（只对分类书单）。
- 书单列表 / 详情 / 新增 / 完善信息 / 按书单采书（链路 B，复用 `collect_tasks`，`targetType=BOOKLIST`）。
- 书单名称 / 元数据回填（`/booklist/single`）。

## 关键类

| 类 | 职责 |
|---|---|
| `BooklistController` | 11 个端点：列表 / 分类 / 详情 / 新增 / 同步 / 完善 / 按书单采书 / 进度 |
| `BooklistService(Impl)` | 读：分页 / 分类 / 选项 / 详情；新增（查重） |
| `BooklistSyncService(Impl)` | 链路 A 同步 + 名称 / 元数据回填 + 一键完善（异步 + 进度） |
| `BooklistCollectService` | 链路 B：复用 `collect_tasks`（BOOKLIST），start / retry / resume |
| `BooklistRepository` | `booklists` upsert / 分页 / 更新元数据 |
| `BooklistParser` | `/booklist/category` 与 `/booklist/single` 的节点 → `Booklist` |

## 数据

`booklists`：`uk_booklistId` 唯一；`platformCategories` 用 `$addToSet` 累加。

## 不变量

1. 书单 URL 必带 `synckey`（后端 `new Date()` 秒值）；凭证八项缺一 → 「书单配置信息不完整」，不发请求。
2. 响应书在 `booklist.booklist.books`，元素外包 `bookInfo`；拍平 = 缺 synckey 的降级，`BookParser` 只收完整形态。
3. 书单采集复用 `collect_tasks`（`targetType=BOOKLIST`）；一个书单一个任务。
4. 完善书单信息：覆盖更新（不跳过已有值）；一键完善遍历全部书单。
5. 名称回填不阻断采集（失败返回 null）。
6. 二级分类的来源归并到一级（书单分类展示）。
