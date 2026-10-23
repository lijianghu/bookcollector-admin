# shelf 模块

> `com.bookcollector.shelf` · 我的藏书（快照式）。

## 职责

「我的藏书」页：8 列 × 3 行网格 + 下滑加载 + 书名 / 作者搜索；四个列表页共用的「加入 / 移除藏书」。

## 关键类

| 类 | 职责 |
|---|---|
| `ShelfController` | 5 个端点：类目 / 分页 / 加入 / 移除 / 批量查状态 |
| `ShelfService(Impl)` | 参数归一化 + 加入时取快照 |
| `ShelfBookRepository` | `shelf_books` 读写 + 类目聚合 + 从 `books` 取快照 |
| `ShelfBook` | 9 个快照字段 + `addedAt` |

## 数据

`shelf_books`：快照式，不是 `books` 的投影。

## 不变量

1. 与 `readinglist` 两个维度、互不影响：移除藏书不影响阅读清单，反之亦然。
2. 加入时从 `books` 取快照；书不在 `books` 里不能加入。
3. `ShelfServiceImpl` 不碰 `reading_list`。
4. 前端四个页面并发调 `/api/shelf/status` 与 `/api/reading-list/status`，各自维护。
