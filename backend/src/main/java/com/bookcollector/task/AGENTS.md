# task 模块

> `com.bookcollector.task` · 采集任务的编排与状态机（不负责抓取）。

## 职责

任务 = 目标（分类 / 榜单 / 书单）+ 最大页数；启动 → 采集 → 暂停 / 恢复 / 取消 / 重试；重启续传。
HTTP 层唯一入口，也是「任务现在在干什么」的唯一权威。

## 关键类

| 类 | 职责 |
|---|---|
| `TaskController` / `RunController` | 任务 CRUD + 5 个动作；运行记录 / 日志 |
| `TaskService(Impl)` | 状态机的全部规则 |
| `TaskRunner` | `@Async(collectExecutor)`：调 `CollectLoop`，写终态 |
| `TaskRegistry` | 采集权（内存）：一个目标同时只能一个运行 |
| `TaskHandle` | 单次运行的控制句柄（暂停 / 恢复 / 取消），实现 `CollectControl` |
| `InterruptedTaskDetector` | 启动时把遗留 `RUNNING` / `PAUSED` 标成 `INTERRUPTED` |
| `TaskRepository` | 三张集合的 Mongo 访问 |

## 状态机

`PENDING → RUNNING → PAUSED / SUCCESS / FAILED / CANCELED`；`INTERRUPTED` 可恢复。
`start` 只接受 `PENDING` / `INTERRUPTED`；`retry` 只接受终态；`resume` 接受 `PAUSED` / `INTERRUPTED` / `PENDING`。

## 数据

- `collect_tasks`：`uk_target (targetType, targetId)` 唯一 + `idx_status` + `idx_createdAt`
- `collect_task_runs`：`idx_taskId` + `idx_startedAt`
- `collect_task_logs`：`idx_runId`

## 不变量

1. 一个目标只能有一个任务（`uk_target`）；换参数改任务，不建第二个。
2. 游标属于目标；`uk_target` 保证两个任务不会共享同一个游标。
3. `TaskRunner` 写终态（无条件覆盖）；`TaskService` 写中间态（CAS）。
4. `decideStatus` 顺序：先 `isCanceled()` → 再 `isFailed()` → 否则 `SUCCESS`。
5. `TaskRunner` 自己 put / remove MDC（`@Async` 不继承）；`finally` 必须 `registry.release(key)`。
6. 暂停用 `ReentrantLock` + `Condition`（可打断等待）；`cancelAllOnShutdown` 监听 `ContextClosedEvent`。
7. `maxConcurrentRuns` 默认 1；队列满直接拒绝，不阻塞 HTTP 线程。
