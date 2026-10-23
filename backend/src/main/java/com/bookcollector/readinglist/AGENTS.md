# readinglist 模块

> `com.bookcollector.readinglist` · 阅读清单（快照式，三态）。

## 职责

想读 / 在读 / 已读；四个列表页的「加入 / 移除 / 改状态」。

## 关键类

| 类 | 职责 |
|---|---|
| `ReadingListController` | 6 个端点：分页 / 计数 / 加入 / 移除 / 改状态 / 批量查状态 |
| `ReadingListService(Impl)` | 参数归一化 + 状态校验 + 加入时取快照 |
| `ReadingListRepository` | `reading_list` 读写 + 从 `books` 取快照 |
| `ReadingListItem` | 9 个快照字段 + `status` + `addedAt` + `statusUpdatedAt` |
| `ReadingStatus` | `WANT_TO_READ` / `READING` / `FINISHED`；`parse` 严格、`of` 宽松 |

## 数据

`reading_list`：快照式。

## 不变量

1. 与 `shelf` 互不影响：移除藏书不影响阅读清单，反之亦然。
2. 加入前提：书在 `books` 里（不要求已收藏）。
3. 状态只接受三态；`parse` 严格校验，`of` 宽松展示。
4. `ReadingListServiceImpl` 不碰 `shelf_books`。
