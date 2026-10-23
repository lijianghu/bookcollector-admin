# 基础设施（common / config / util / system）

> `com.bookcollector.{common,config,util,system}` + `setting` · 全项目地基。

## 定位

| 包 | 放什么 |
|---|---|
| `common` | `ResultBean` / `BizException` / `GlobalExceptionHandler` / `PageResult` / `TraceIdFilter` / `enums` |
| `config` | `@Configuration` + `*Properties` + 启动期初始化（索引 / 字典 / 管理员） |
| `util` | `PageQueryUtil` / `Md5Util` |
| `system` | `PingController` |

判定标准：被 2 个以上业务模块使用才放这里。

## 关键类

| 类 | 职责 |
|---|---|
| `ResultBean<T>` | `{ code, message, traceId, data }` |
| `BizException` | 业务异常，带 `code` |
| `GlobalExceptionHandler` | `@RestControllerAdvice`，8 个分支（含 `NotLoginException` → 4100） |
| `PageResult<T>` | `{ list, total, page, size }` |
| `TraceIdFilter` | 每个请求打 traceId 进 MDC |
| `enums/TargetType` | `CATEGORY` / `RANKING` / `BOOKLIST`，含 URL 语义 |
| `enums/TaskStatus` | 7 状态 + `isTerminal` / `isActive` / `isResumable` |
| `MongoIndexInitializer` | 启动时显式建 **31 个索引**；带字段漂移自愈 |
| `RestTemplateConfig` | `wereadRestTemplate` + `booklistRestTemplate` + Header 拦截器 |
| `WebMvcConfig` | `AuthInterceptor` + `@ExtractLoginUser` 解析器 |
| `AsyncConfig` | `collectExecutor`：core 1 / max 2 / queue 16 / AbortPolicy / 关停唤醒 |
| `SeedRunner` | 播种分类 / 榜单 / settings / 初始管理员（只补缺失） |
| `PageQueryUtil` | 分页样板（page/size 归一化 + count 短路 + 排序） |

## 不变量

1. `MongoIndexInitializer` 建 **31 个索引**；`auto-index-creation: false`；实体零 `@Indexed`。
2. `ensureIndex` 按名字 upsert、不比较字段；字段路径改过要 `drop` 再建。
3. 唯一索引是幂等写入的正确性依赖。
4. `TargetType` 三值：`CATEGORY` / `RANKING` / `BOOKLIST`。
5. 错误码：`200` / `400` / `404` / `405` / `500` / `4100`。
6. 拦截器失败不走 `GlobalExceptionHandler`，自己写响应体。
7. `@Async` 线程不继承 MDC。
8. `AuthProperties` 只是播种参数；`WereadProperties` 只有超时 / 限速 / 重试（URL 在库里）。
