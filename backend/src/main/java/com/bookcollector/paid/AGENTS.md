# paid 模块

> `com.bookcollector.paid` · 付费精选（App 端 `/category/data`）。

## 职责

按 `taxonomy_config` 一级 + `enabled=true` 的分类（`filterCategory`）分页采集付费精选书籍；
异步、全局串行、可暂停 / 继续 / 停止、断点续采。

## 关键类

| 类 | 职责 |
|---|---|
| `PaidCategoryController` | 6 个端点：分类清单 / 启动 / 进度 / 暂停 / 继续 / 停止 |
| `PaidCategoryService` | 单线程执行 + 全局互斥 + 暂停 / 停止 + 断点 + 状态 |
| `PaidCollectState` / `PaidCollectStateRepository` | `paid_collect_state`：断点 + 状态 |
| `PaidCategoryVO` / `PaidCollectVO` | 列表行 / 进度 |

## 数据

`paid_collect_state`：`_id = filterCategory`；`maxIdx` / `page` / `totalCount` / `lastStatus` / `filterCategoryName`。

## 接口

| 方法 | 路由 | 说明 |
|---|---|---|
| GET | `/api/paid/categories` | 一级 + enabled 分类，附 `lastStatus` |
| POST | `/api/paid/categories/{code}/collect` | 启动；默认续传，`?reset=true` 从头 |
| GET | `/api/paid/progress` | 全局进度 |
| POST | `/api/paid/pause` / `resume` / `stop` | 暂停 / 继续 / 停止 |

## 不变量

1. 一次只跑一个分类（`running` 互斥）。
2. 已有书只 `$addToSet collectSource` + `$set paidCollect=1`，**不覆盖其它字段**；不存在才插入。
3. `collectSource = paid:9000100:{filterCategory}`。
4. 401 = 身份过期 → 暂停；更新凭证后「继续」重试同一页。
5. 每页写断点；采完 `COMPLETED`；暂停 / 401 `PAUSED`；停止 `STOPPED`；失败 `FAILED`；重启把 `RUNNING` → `INTERRUPTED`。
6. `synckey` 用 `refreshSynckeyNow()`（与书单一致）；`categoryId=9000100` 固定。
