# cursor 模块

> `com.bookcollector.cursor` · 采集游标（断点续传的唯一权威）。

## 职责

记录每个目标采到哪儿；游标是**目标**的属性，主键 `(targetType, targetId)`。

## 关键类

| 类 | 职责 |
|---|---|
| `CursorController` | 3 个端点：列表 / 重置或指定 / 删除 |
| `CursorService(Impl)` | 类型解析 + 校验 + 组装 |
| `CursorStore` | `load` / `loadCursor` / `advance` / `reset` / `setCursor` / `delete` |
| `CollectCursor` | `targetType` / `targetId` / `maxIndex` / `totalCollected` / `finished` / `lastRunId` |

## 数据

`collect_cursors`：`uk_target (targetType, targetId)` 唯一。

## 接口

| 方法 | 路由 | 说明 |
|---|---|---|
| GET | `/api/cursors` | 列表，按最近更新时间倒序 |
| PUT | `/api/cursors` | `maxIndex=0` 或不传 = 重置；`>0` = 指定起点 |
| DELETE | `/api/cursors/{id}` | 删除；下次从 0 开始 |

## 不变量

1. 游标属于目标，不属于任务；重建任务不丢进度。
2. `CollectLoop` 是唯一 `advance` 调用方；`TaskService.launch` 是唯一 `loadCursor` 调用方。
3. `uk_target` 唯一：一个目标只能有一行游标。
4. 删除字典项时顺带清孤儿游标；删任务**不删**游标。
