# refresh 模块

> `com.bookcollector.refresh` · 增量配置：8 个目标的全量重采（与「采集任务」是两套东西）。

## 职责

把 7 个榜单 + 期刊杂志（`RefreshTarget.ALL`）做一次全量重采；cron + 手动。
不建任务、不写运行记录、不碰游标；与手工任务共享同一把锁。

## 关键类

| 类 | 职责 |
|---|---|
| `RefreshTarget` | 8 个目标的固定清单 |
| `RefreshJob` / `RefreshDetail` | 单例配置 + 最近一次结果（内嵌） |
| `RefreshJobRepository` | `loadOrCreate` / `saveConfig` / `markRunning` / `markFinished` / `resetStaleRunning` |
| `RefreshService(Impl)` | 批次编排 + 独立线程池 + `AtomicBoolean` 互斥 + 内存进度 |
| `RefreshScheduler` | 动态 Trigger（每次重读 cron） |
| `RefreshController` | 3 个端点 |

## 数据

`refresh_jobs`：`_id="default"` 单例。

## 不变量

1. 不建任务、不写 `collect_task_runs`、不碰游标（`skipCursor=true`）。
2. 直接调 `CollectLoop`（不走 `TaskRunner`）；进度走 `CollectProgressListener`（纯内存）。
3. 与手工任务共享 `TaskRegistry` 同一把锁；冲突时**中止整批**。
4. `maxPages=0` = 跑到底；`runId=null`，`ProgressReporter` 空转。
5. 批次级与目标级状态共用 `RefreshStatus`；`lastDetails` 只留最近一次。
