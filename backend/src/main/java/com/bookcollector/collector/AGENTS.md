# collector 模块

> `com.bookcollector.collector` · 和平台接口打交道的全部代码。

## 职责

按「目标 + 起始游标」分页抓取图书列表，解析成 `Book`，按 `bookId` 幂等 upsert 进 `books`，每页落 `api_requests`。
它不知道「任务」—— 只接收 `CollectCommand`，通过 `CollectControl` 反向接受暂停 / 取消。

## 关键类

| 类 | 职责 |
|---|---|
| `CollectLoop` | 主循环：翻页 → 限速 → 重试 → 解析 → 落库 → 上报 |
| `WereadClient` | 拼 URL + 发请求，返回不抛异常的 `WereadRawResponse`；含 `/book/info`、`/category/data`、`/booklist/*` |
| `BookParser` | 平台 JSON → `Book`（业务字段 + `/book/info` 补充字段 + 清洗） |
| `BookWriter` | 分批（500/批）调 `BookRepository.upsertMany` |
| `SimpleRateLimiter` | 令牌桶，≥1 秒/页；有状态，由 `CollectLoop` 自己 new |
| `ProgressReporter` | 写 `collect_task_runs` / `collect_task_logs` |
| `CollectControl` | 暂停 / 取消的回调契约（`task.TaskHandle` 实现） |
| `CollectProgressListener` | 逐页进度回调（纯内存，定时刷新用） |
| `WereadHeaderInterceptor` | 注入 Header；`Accept-Encoding` 去掉 `br`（Java 不认 brotli） |
| `WereadProbe` | 手工探针，默认关闭 |

## 数据

- `books`：写（upsert）；`api_requests`：只插；`collect_task_runs` / `collect_task_logs`：进度 / 日志。
- 本模块不读集合做业务判断。

## 不变量

1. **先落库、再推游标**：崩了最多重复写（upsert 幂等），不会漏。
2. 同一目标互斥；`CollectLoop` 不依赖 `TaskService`，只认 `CollectControl`。
3. 上游分类 / 榜单最多 500 本（25 页）；`totalCount` 不是可采完的分母。
4. 书单（App 端）URL 必带 `synckey`（后端 `new Date()` 秒值）；响应书在 `booklist.booklist.books`，元素外包 `bookInfo`。
5. 分类 / 榜单走 Web 端（`listBaseUrl`）；书单 / 书详情 / 付费精选走 App 端（`booklistBaseUrl`），App 头由拦截器按 host 注入。
6. 失败重试 1s → 3s 退避、最多 3 次；4xx 不重试。
7. 进度上报失败静默降级（warn），不影响采集。
8. 取消抛 `CollectCanceledException`（覆写 `fillInStackTrace` 返回 `this`）。
