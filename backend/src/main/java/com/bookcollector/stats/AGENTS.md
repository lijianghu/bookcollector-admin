# stats 模块

> `com.bookcollector.stats` · 仪表盘聚合（全部走 MongoDB 聚合管道）。

## 职责

4 个指标卡 + 4 张图 + 今日新增明细。

## 接口

| 方法 | 路由 | 返回 |
|---|---|---|
| GET | `/api/stats/overview` | 指标卡 |
| GET | `/api/stats/charts` | `{ categoryDistribution, ratingDistribution, publishYearTrend, recentCollected }` |
| GET | `/api/stats/today-collected` | 今日新增分页 |

## 数据

`books`（`idx_firstCollectedAt` / `idx_newRating` / `idx_lastCollectedAt`）、`taxonomy_config`、`collect_tasks`、`collect_cursors`、`api_requests`。

## 不变量

1. 「今日采集数」按 `firstCollectedAt`（净新增），不按 `lastCollectedAt`（每次 upsert 都刷新）；近 7 天同口径。
2. 必须用聚合管道，不把文档拉到 JVM；管道直接写 `Document`，输出用 `Document.class`。
3. 分布图必须补齐空桶 / 空日期；年份过滤 `$gte 1900 $lte 2100`（库里存在 `0000-00-00` 占位）。
4. 评分分桶用 `$cond` + `$floor`，不用 `$bucket`。
5. 聚合失败返回空列表 + error 日志，不让仪表盘 500。
6. **分类分布按 `books.categories.categoryId`（书的实际分类）**，不是 `collectSource`；`$unwind categories` 后按 `categoryId` 计数，二级 code 按 `parentCode` 归并到一级；一本书可能属于多个分类，扇区之和 > 图书总数。
7. 没有 `categories` 的书不计入分类分布。
