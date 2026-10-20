# audit 模块知识

> 包路径：`com.bookcollector.audit` · 最后更新：2026-10-15（深夜，回放保真度：skey 真值照用 + 丢弃头暴露）

## 1. 这个模块是干什么的

每打完一次平台接口就落一条记录 —— **成功失败都记**。

这张表是排查「某页为什么没数据」的**第一现场**：能回答「到底发了几次请求、每次什么状态码、
返回几条、耗时多少」。所以列表默认按时间倒序，且支持 `onlyFailed=true` 一键过滤异常请求。

**列表回答「某一条怎么样」，摘要回答「这一批整体健不健康」。** 2026-10-08 加了
`GET /api/requests/summary`：与列表**同一套筛选条件、但不分页**，返回请求次数 / 失败次数 /
平均 / P95 / 最慢耗时。加它的原因很直接 —— 靠人逐页翻两百条记录去数失败次数，
是把原始日志当报表用。

**2026-10-15 又加了两个「单条」接口**：`GET /api/requests/{id}` 返回一条记录的完整现场
（URL 参数 / 请求头掩码快照 / 原始 body / 该次运行的步骤日志）—— 回答「这次请求到底返了什么」；
`POST /api/requests/replay`（请求体 `ReplayRequest{url, requestHeaders}`）用**最原始 HTTP**
（JDK `HttpURLConnection`，不走 RestTemplate / 拦截器）按**提交的请求**重发 —
URL / 参数 / 请求头在弹窗里均可修改，body 原文返回（不过 fastjson）——
回答「把这个请求改一改再发会怎样」。**回放不写审计**（诊断动作不能污染采集次数的口径）。

## 2. 类清单

| 类 | 角色 | 一句话职责 |
|---|---|---|
| `controller/RequestAuditController` | Controller | 4 个端点（分页 + 摘要 + 详情 + 回放），**极薄转发** |
| `service/AuditService` | Service 接口 | 4 个方法（分页 + 摘要 + 详情 + 回放） |
| `service/impl/AuditServiceImpl` | Service 实现 | 参数归一化 + 转发；回放只做「找记录 + 校验 URL 存在」 |
| `service/RequestReplayer` | Service | 🔴 回放专用：`HttpURLConnection` 最原始 HTTP 重发 + skey 按域名规则替换 + 手工解 gzip/deflate + body 原文（不过 fastjson）+ 从 body 取响应 synckey |
| `repository/ApiRequestRepository` | Repository | **写入**：`recordPage`，只插不改，**失败不抛异常** |
| `repository/ApiRequestQueryRepository` | Repository | **查询**：条件拼装 + 排序 + 摘要聚合（`conditions()` 是筛选条件的唯一来源） |
| `entity/ApiRequest` | Entity | 18 个字段（runId / 目标 / url / 状态码 / 耗时 / synckey / 结果数 / 错误摘要 + 2026-10-15 的 queryParams / requestHeaders / rawResponse） |
| `dto/RequestSummary` | DTO | 摘要的返回体（total / failed / avgMs / p95Ms / maxMs） |
| `dto/RequestDetail` | DTO | 详情返回体 = 审计记录 + 该 run 的步骤日志（最多 300 条） |
| `dto/ReplayRequest` | DTO | 回放入参：url（唯一真相）+ requestHeaders（skey 真值照用、掩码/空才替换） |
| `dto/ReplayResult` | DTO | 回放返回体：url / 参数 / 请求头（skey 掩码）/ **droppedHeaders（被 JDK 丢的头）** / **skeyFrom（skey 用了谁的）** / **shapeHint（形状一行指纹）** / 状态码 / 耗时 / 响应 synckey / body 原文 / errorMsg |

> 写与查**刻意分成两个类**，见铁律 1。

## 3. 调用关系（图谱）

```
collector.CollectLoop ──► ApiRequestRepository.recordPage()  ──► api_requests
                              （只插不改，失败吞掉不抛）

RequestAuditController ──► AuditService(Impl) ──► ApiRequestQueryRepository ──► api_requests
                                                        （条件拼装 + 排序 / 摘要聚合）
```

列表（`pageQuery`）与摘要（`summary`）**共用 `conditions()`**，见铁律 7。

## 4. 数据集合与索引

| 集合 | 索引 | 为什么 |
|---|---|---|
| `api_requests` | `idx_createdAt`（DESC） | 「最近有哪些请求失败」——按时间倒序翻 |
| | `idx_runId` | 「某次运行打了哪些请求」 |

> 写入量大（21 分类 × 数百页），索引克制，只建真正会查的两个。

## 5. 对外接口

| 方法 | 路由 | 说明 |
|---|---|---|
| GET | `/api/requests` | 分页。参数：`runId` / `targetType` / `targetId` / `onlyFailed` / `page` / `size` |
| GET | `/api/requests/summary` | 聚合摘要。参数：`runId` / `targetType` / `targetId` / `onlyFailed`（**没有分页参数**） |
| GET | `/api/requests/{id}` | 单条完整现场：URL 参数 / 请求头（skey 掩码）/ 原始 body / HTTP 状态 / 该 run 的步骤日志 |
| POST | `/api/requests/replay` | 回放（可编辑请求）：入参 `{url, requestHeaders}`，按提交的 URL 与头用最原始 HTTP 重发，返回 url / 参数 / 请求头（掩码）/ 状态 / 耗时 / 响应 synckey / body 原文；**不写审计** |

- 排序固定：`createdAt` **倒序** + `_id` 升序兜底
- 分页归一化：`page < 1 → 1`、`size` 默认 20、上限 200
- `onlyFailed=true` 的判据是**或**关系：`statusCode != 200` **或** `errorMsg` 非空
- 摘要返回 `total` / `failed` / `avgMs` / `p95Ms` / `maxMs`；**没有可统计的记录时三个耗时字段是 `null`**
  （不是 0 —— 0ms 是「很快」，不是「没有数据」）
- 摘要是**全量**（当前筛选条件下的全部请求），不随分页变化；`onlyFailed=true` 时 `failed == total` 属正常

## 6. 不变量与铁律 ← 改这个模块前必读

1. **写入与查询分成两个类，不要合并。**
   写入那个类的约束是「**只插不改，且失败不抛异常**」（审计不能影响采集主流程）；
   查询的约束**完全相反** —— 它要能被上层的异常处理器正常兜住，失败了应该报错。
   两者的「错误处理哲学」不同，混在一个类里迟早有人把 `try-catch 吞掉` 复制到查询方法上。

2. **审计失败绝不抛异常。** `recordPage` 内部吞掉异常并打日志。
   为什么：审计是旁路，它不该让一次采集失败。

3. **`onlyFailed` 的判据必须是「或」。**
   有些失败是「HTTP 200 但 body 里没有 `books` 数组」，只看状态码会漏掉它们。

4. **只有查询，没有删除。**
   审计记录的价值恰恰在于「事后还在」。提供一个「清空审计」按钮，
   早晚会有人手滑点掉正在排查的那批数据。要清理请直接连库操作。

5. **失败原因的措辞来自 `WereadRawResponse.describeFailure()`**（`collector` 模块），
   本模块只负责存。用户在「任务失败原因」和「请求审计」里看到的必须是同一句话。

6. **`statusCode = -1` 表示「请求在客户端就失败了」**（网络异常等），
   不是「服务端回了 -1」。⚠️ 但要注意 `collector` 模块第 9.1 节那个陷阱：
   修复前，服务端明确的 4xx/5xx 也会被错记成 `-1`。

7. **列表与摘要必须共用同一套筛选条件。**
   `ApiRequestQueryRepository#conditions()` 是唯一来源，`failedCriteria()` 也是 ——
   列表页的「只看失败」与摘要里的「失败次数」必须走同一个实现。
   两处各拼一遍条件，拼错了**不报错、只会静默算少**；而「列表说 259 条、摘要说 261 次」
   这种对不上的数字，会直接毁掉这一页的可信度。

8. **回放不写审计。** 它是诊断动作：记进去会让「这个目标被采了多少次」的口径
   混进人工重发的次数。回放的 HTTP 异常也**不抛到接口层** —— 装进
   `ReplayResult.errorMsg` 原样展示给用户；但**书单凭证缺项会抛**
   （`requireBooklistComplete` → `IllegalStateException` → 服务层转 `BizException.warn`），
   因为带着残凭证回放出来的结果毫无意义。

9. **回放的 skey：真值照用，掩码/空才替换。** 弹窗 skey 行预填当前凭证的**明文**
   （凭证接口本来就明文回显），可改成 Postman 那把 —— 这样回放才可能「完全复刻 Postman」。
   规则（`RequestReplayer#resolveHeaders`）：提交值非空且**不含 `****`** → 照用；
   掩码 / 空 → 用当前内存凭证（书单域名缺 skey 行时补上）。
   ⚠️ **掩码值绝不发出去**是底线：历史上这里一律替换，导致「库里 skey ≠ Postman skey」
   时请求根本不是同一个（用户实测「回放没有 bookInfo、Postman 有」的元凶之一）。
   来源要**回给界面自证**：`resolveHeaders` 返回 `Resolved{headers, skeyFrom}`，
   `skeyFrom` = `SUBMITTED`（用了提交值）/ `CREDENTIAL`（被替换）/ `null`（未发），
   前端「实际发送」区直接显示 —— 别让用户只能靠猜。

10. **回放要给出「形状一行指纹」。** `ReplayResult.shapeHint`（`RequestReplayer#probe`）把
   「为什么没有 bookInfo」压成一句：`错误信封：errcode=…` / `books[0] 无 bookInfo（拍平形态），
   元素键=[…]` / `books[0] 带 bookInfo（完整形状）`。书单 body 可达 90 万字符，让人翻
   `books[0]` 太费眼。⚠️ 它只**读** body（body 仍原文返回），是**诊断提示不是解析** ——
   解析口径仍在 `WereadPage` / `BookParser`，改那边不用改这里。

11. **JDK 会静默丢弃受限头，必须暴露。** `HttpURLConnection` 把
   `Connection` / `Host` / `Content-Length` / `Origin` / `Via` / `Upgrade` 当受限头丢掉，
   **不报错、不提示**；审计快照里恰好带 `Connection: keep-alive`，每次回放都会少它。
   所以 `fillResponse` 逐个 `conn.getRequestProperty()` 回读，丢掉的写进
   `ReplayResult.droppedHeaders`，前端黄条提示 —— 「提交的头 ≠ 发出的头」这件事
   不能让用户自己猜。

12. **URL 是唯一真相，后端不单独收 params。** 弹窗的「参数」行是 URL query 的
    可编辑视图（改行 → 前端重建 query 回写 URL；改 URL → 前端重新解析出行）。
    入参见 `ReplayRequest`，只有 url + requestHeaders 两个字段 ——
    收两份必然出现「不一致时听谁的」的歧义。

## 7. 依赖与耦合

- **依赖**：`common.{PageResult,ResultBean}`、`util.PageQueryUtil`、`collector.dto.{WereadPage,WereadRawResponse}`；
  回放链路还依赖 `credential.WereadCredentialStore`（当前内存凭证）+ `config.WereadProperties`
  （connect/read 超时与 Referer）+ `collector.WereadHeaders`（头的唯一构造处，skey 掩码）。
- **被依赖**：`collector.CollectLoop`（写）、`stats`（数 `requestTotal`）。
- ⚠️ `audit ← collector.dto` 与 `collector → audit.repository` 构成**已知的环**，
  设计层面耦合，本轮不解决。

## 8. 测试与验收

`CollectEngineIT` / `TaskControlIT` 运行时会在 `api_requests` 里留下真实记录，
可用 `pymongo` 直接核对条数与状态码。

```bash
tools/mvn8.sh -f backend/pom.xml -o -B test -Dtest=CollectEngineIT
```

## 9. 已知陷阱（现象 → 根因 → 修法）

### 9.1 审计页把 HTTP 500 显示成 `-1`

- **现象**：一条明显是服务端错误的请求，`statusCode` 是 `-1`，
  `errorMsg` 是「网络异常 InternalServerError」。
- **根因**：`RestTemplate` 的默认错误处理器在 4xx/5xx 上抛异常，
  异常在 `WereadClient.fetchRaw` 里被包成 `error`，`statusCode` 落成 `-1`。
- **修法**：见 `collector` 模块第 9.1 节 —— `RestTemplateConfig.RawResponseErrorHandler`。
  **本模块不需要改**，但排查这类现象时要往上游看。

### 9.3 摘要的 P95 为什么不用 `$percentile`

- **现象**：本机 MongoDB 是 7.0.30（`$percentile` 累加器可用），但代码里没用它。
- **根因**：它是**近似**算法，而且 `$skip` 只接受字面量 —— 想一条管道算完，
  就得同时依赖「服务端版本」和「近似精度」两个隐性前提。
- **修法**：先 `count` 出条数，再「按 `responseTimeMs` 升序 `$skip` 第 ⌈0.95n⌉−1 条」取**精确值**。
  集合只有几千条，多一次往返可以忽略。**别为了省一次查询把它换成近似值。**
  ⚠️ 耗时统计要先 `{responseTimeMs: {$ne: null}}` 过滤：`$avg` 会跳过缺失值，
  但 P95 是按**下标**取的，两处口径不一致会算出偏移一位的分位值。

### 9.2 `api_requests` 增长很快

- **现象**：跑一轮全量采集后表里多了几百条。
- **根因**：设计如此 —— 每页一条。
- **修法**：**不要**加「只记失败」的开关。成功的记录同样有价值
  （「这一页返回了几条」是判断上游是否改过接口的唯一依据）。
  要清理请手工连库。
