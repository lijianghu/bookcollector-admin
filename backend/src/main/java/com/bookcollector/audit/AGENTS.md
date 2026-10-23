# audit 模块

> `com.bookcollector.audit` · 请求审计 + 回放。

## 职责

每次平台接口调用落一条（成功失败都记）：页码 / 状态码 / 耗时 / 结果数 / 失败原因；支持分页、摘要、详情、回放。

## 关键类

| 类 | 职责 |
|---|---|
| `RequestAuditController` | 4 个端点：分页 / 摘要 / 详情 / 回放 |
| `AuditService(Impl)` | 参数归一化 + 转发 |
| `RequestReplayer` | 回放：JDK `HttpURLConnection` + 手工解 gzip/deflate + body 原文 |
| `ApiRequestRepository` | 只插不改，失败不抛 |
| `ApiRequestQueryRepository` | 条件拼装 + 排序 + 摘要聚合 |
| `ApiRequest` | 18 字段（runId / url / 状态码 / 耗时 / synckey / 结果数 / 错误 / queryParams / requestHeaders / rawResponse） |

## 接口

| 方法 | 路由 | 说明 |
|---|---|---|
| GET | `/api/requests` | 分页（`runId` / `targetType` / `targetId` / `onlyFailed` / `page` / `size`） |
| GET | `/api/requests/summary` | 摘要（同条件、不分页）：`total` / `failed` / `avgMs` / `p95Ms` / `maxMs` |
| GET | `/api/requests/{id}` | 单条完整现场 + 该 run 的步骤日志 |
| POST | `/api/requests/replay` | 回放：`{url, requestHeaders}`，URL 是唯一真相；不写审计 |

## 不变量

1. 写与查分两个 Repository；`recordPage` 只插不改、失败不抛。
2. 列表与摘要共用 `conditions()`，保证同一套筛选。
3. 排序固定 `createdAt` 倒序 + `_id` 升序。
4. `onlyFailed` = `statusCode != 200` 或 `errorMsg` 非空。
5. 回放不写审计（诊断动作不污染采集口径）。
6. 回放 skey：真值照用，掩码 / 空才替换为当前凭证；JDK 静默丢弃受限头（`Connection` / `Host` / `Content-Length` 等），暴露为 `droppedHeaders`。
7. 日志 / 返回里的 skey 一律掩码；`rawResponse` 只存响应体，不存请求头真值。
