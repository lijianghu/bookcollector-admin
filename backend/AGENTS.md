# AGENTS.md

> 项目级开发规范。`backend/CLAUDE.md` 与本文件**逐字节一致**，改一份必须同步另一份。
> 各业务模块另有目录级 `AGENTS.md`（见「模块索引」），改模块前先读。
> 🔴 「用户硬规则」优先级最高，与其余条款冲突时以其为准。

---

## 1. 项目概览

`bookcollector-admin`：阅读平台图书采集的**本地单机可视化后台**（原 Python CLI 重构）。

- Windows 单用户；单体 Spring Boot + Vue 3 SPA；无 Docker / 微服务 / 网关。
- 端口：后端 `8080`，前端 `5173`；MongoDB `localhost:27017/bookcollector`；Redis `6379`（登录会话，**必需**）。

**数据流**

```
平台接口 → WereadClient（限速 / 重试 / App 或 Web 头）
        → BookParser → BookRepository.upsertMany（bookId 幂等）
        → MongoDB → REST（ResultBean + PageResult）→ Vue
```

**采集链路**

```
collect_tasks → TaskService（状态机 / 同目标互斥）
             → TaskRunner（采集线程池）
             → CollectLoop（分页 / 断点 / 限速 / 重试）
             → 逐页 BookWriter 落库 + ProgressReporter
             → collect_task_runs / collect_task_logs
```

**不做**：SSE、数据导出、ETL、Docker、权限体系（`roles` 不参与鉴权）。
**已做**：模糊搜索（包含匹配）、定时刷新（增量配置）、书单采集、付费精选。

## 2. 模块索引

| 包 | 职责 | 目录级文档 |
|---|---|---|
| `book` | 图书库：分页筛选 / 详情 / 编辑 / 删除 | ✅ `book/AGENTS.md` |
| `booklist` | 书单池：发现 / 新增 / 完善 / 按书单采书（App 端） | ✅ `booklist/AGENTS.md` |
| `taxonomy` | 分类 / 榜单字典 | ✅ `taxonomy/AGENTS.md` |
| `task` | 采集任务状态机 + 运行记录 / 日志 | ✅ `task/AGENTS.md` |
| `cursor` | 采集游标（断点续传唯一权威） | ✅ `cursor/AGENTS.md` |
| `audit` | 请求审计 + 回放 | ✅ `audit/AGENTS.md` |
| `auth` | 登录鉴权（Sa-Token + Redis） | ✅ `auth/AGENTS.md` |
| `stats` | 仪表盘聚合 | ✅ `stats/AGENTS.md` |
| `collector` | 采集引擎：主循环 / 限速 / 重试 / 解析 / 落库 | ✅ `collector/AGENTS.md` |
| `refresh` | 增量配置：8 个目标全量刷新 | ✅ `refresh/AGENTS.md` |
| `publish` | AI 书籍解读 + 发布 | ✅ `publish/AGENTS.md` |
| `paid` | 付费精选：按一级分类采集（断点 / 暂停 / 401） | ✅ `paid/AGENTS.md` |
| `credential` | 接口凭证（书单八项 + `listUrl` + `synckey`） | ✅ `credential/AGENTS.md` |
| `shelf` | 我的藏书（快照式） | ✅ `shelf/AGENTS.md` |
| `readinglist` | 阅读清单（快照式，三态） | ✅ `readinglist/AGENTS.md` |
| `common` / `config` / `util` / `system` / `setting` | 公共组件 / 基础设施 | ✅ `common/AGENTS.md` |

> 目录级文档路径相对 `src/main/java/com/bookcollector/`。

**知识最密的模块**：`task`（状态机）、`collector`（数据源事实）、`stats`（统计口径）、`booklist` / `paid`（App 端采集）。

## 3. 数据集合（23 个）

| 分组 | 集合 |
|---|---|
| 采集数据 | `books`（唯一索引 `bookId`） |
| 采集目标 | `taxonomy_config` · `booklists` |
| 任务 / 游标 | `collect_tasks` · `collect_task_runs` · `collect_task_logs` · `collect_cursors` |
| 付费精选 | `paid_collect_state`（断点 + 状态） |
| 审计 / 系统 | `api_requests` · `settings` · `sys_user` · `weread_credential` |
| 书架 | `shelf_books` · `reading_list` |
| 定时刷新 | `refresh_jobs` |
| AI 解读 / 发布 | `book_interpretations` · `ai_publish_job` · `publish_record` · `pick_taxonomy_config` |
| AI 配置（手工维护） | `ai_model` · `ai_yuque_token` · `ai_taxonomy_config` · `ai_sensitive_words` |

- 集合名 / 字段映射**不得变更**；**Mongo 字段名 == Java 字段名**（零 `@Field`）。
- 索引全部由 `MongoIndexInitializer` 启动时显式创建（**31 个**）；`auto-index-creation: false`；实体零 `@Indexed`。
- 唯一索引是幂等写入的**正确性依赖**，不是性能优化。
- 最后 4 张 `ai_*` 表由用户手工维护，`MongoIndexInitializer` 刻意不碰。

## 4. API 路由（17 个 Controller / 87 个端点）

前缀 `/api`。

| 前缀 | 说明 |
|---|---|
| `/api/auth` | 登录 / 登出 / 当前用户 |
| `/api/books` | 图书库 |
| `/api/booklists` | 书单池 / 同步 / 完善 / 按书单采书 |
| `/api/taxonomy` | 分类 / 榜单字典 |
| `/api/tasks` · `/api/runs` | 采集任务 / 运行记录 |
| `/api/cursors` | 采集游标 |
| `/api/stats` | 仪表盘 |
| `/api/requests` | 请求审计 + 回放 |
| `/api/refresh` | 增量刷新 |
| `/api/publish` · `/api/publish/records` | AI 解读 / 发布记录 |
| `/api/paid` | 付费精选 |
| `/api/credential` | 接口凭证 |
| `/api/shelf` · `/api/reading-list` | 书架 / 阅读清单 |
| `/api/ping` | 连通性 |

Swagger：`http://localhost:8080/swagger-ui.html`。

## 5. 技术栈

| 层 | 组件 |
|---|---|
| 语言 | Java **1.8**（`maven.compiler.source/target=1.8`） |
| 框架 | Spring Boot **2.3.12.RELEASE**（单体，非 Cloud） |
| 数据 | Spring Data MongoDB（驱动 **4.0.6**）；服务端 MongoDB **7.0.30** |
| 缓存 | spring-boot-starter-data-redis（Lettuce）；Redis **3.2.100**；Sa-Token 会话存 Redis |
| JSON / 文档 | fastjson2 **2.0.60**；springdoc-openapi-ui **1.6.15** |
| 工具 | hutool-all **5.8.4**；commons-lang3 **3.12.0**；Lombok |
| 测试 | spring-boot-starter-test（JUnit 5 + AssertJ） |

**编译红线（JDK 8 / Boot 2.3.12 / Spring 5.2.15）**

- 禁用：`var`、`List.of` / `Map.of`、`String.isBlank`、`Optional.isEmpty`、text block、switch 表达式、`record`、`Stream.toList`、接口私有方法、`java.net.http.HttpClient`。
- HttpClient 只能 **4.5.x**；Redis 前缀 **`spring.redis.*`**；springdoc **1.6.15**。
- Maven 平台编码 GBK → 必须显式 `project.build.sourceEncoding=UTF-8`。
- 改 `src/main` 方法签名后跑 `clean test-compile`（`spring-boot:run` 会先编译 test，编译错会挡住启动）。

## 6. 分层与依赖

- `Controller → Service(接口) → ServiceImpl → Repository → MongoTemplate`；禁止反向依赖。
- Service 必须 **接口 + `service/impl/` 实现类**；Controller 只依赖接口。
- 统一 `MongoTemplate`；**禁止** `MongoRepository`。
- 简单 CRUD 可在 ServiceImpl 直接用 `MongoTemplate`；复杂查询 / 聚合 / 批量 upsert 抽 Repository。
- 更新用 `Update` 逐字段 `$set`；**禁止**整本 `save()` 覆盖（隐式清空）。
- 动态条件用 `Criteria` + `andOperator`；不要连续 `addCriteria` 叠加同字段。
- 批量 `$in` 分片（`IN_QUERY_CHUNK = 1000`），避免 BSON 超 16MB。

## 7. 请求与响应

- 统一 `ResultBean<T>`；**HTTP 恒 200**，业务看 body `code`。
- 码：`200` 成功 / `400` 参数 / `404` 未找到 / `405` 重复 / `500` 错误 / `4100` 身份失效。
- 业务异常 `throw new BizException(...)`；全局 `GlobalExceptionHandler` 兜底，Controller 不写 try/catch。
- 拦截器里失败不走全局处理器，必须自己写响应体（参考 `AuthInterceptor.writeSessionInvalid`）。
- Entity 可直接作为响应；`Req` / `Resp` / `DTO` 只在需要收敛 / 聚合时创建。

## 8. 分页

- 统一 `PageQueryUtil`（`page` / `size`，size 上限 200）；不要手写分页。
- 排序字段必须白名单校验（`BookQuery#resolveSortPath`），不能把用户字符串直接拼进 Mongo 路径。
- 正则查询必须转义特殊字符。

## 9. 编码规范

- 命名：类大驼峰、包全小写、方法 / 字段小驼峰、常量全大写下划线（`ResultBean.code_*` 保持不动）。
- 构造器注入 + `private final`；**禁止** `@Autowired` 字段注入。
- Lombok 只用于实体 / 值对象（`@Data @Builder @NoArgsConstructor @AllArgsConstructor`）；带自定义行为的类手写 getter/setter。
- 日志 SLF4J + 占位符；关键动作（写入 / 删除 / 状态流转 / 失败）必须打日志。
- 时间字段 `java.util.Date`；`publishTime` 是字符串（定长 `yyyy-MM-dd HH:mm:ss`，字典序 = 时间序，不要解析）。
- 注释中文，写「为什么」；类级 Javadoc 写边界。
- Swagger：`@Tag` / `@Operation` / `@Parameter` / `@Schema`；`@Tag(name = "0X. 模块")`。
- 回复默认简体中文。

## 10. 用户硬规则（优先级最高）

1. **git 只读**：允许 `status` / `log` / `diff` / `show` / `branch`；禁止 `add` / `commit` / `push` / `merge` / `rebase` / `reset` / `revert` / `cherry-pick` / `checkout` / `switch` / `stash` / `tag` / `rm`。
2. **只改需求明确提及的方法 / 文件**；不重构、不「顺手优化」。
3. **禁止大范围 grep + Edit 批量替换**；10+ 文件先说明范围并获同意。
4. **不写单元测试**；不创建测试文件；现有测试保持不动。
5. **不做运行时验证**；不启动服务、不调接口做真实读写。
6. **能力不足先说明**，不擅自换模型 / 引擎。
7. **歧义先问**；模糊之处按「无授权」处理。

## 11. 通用约束

- 优先复用：`PageQueryUtil` / `PageResult` / `common` / `util`。
- 不做无意义抽象；不重复造轮子；不改既有契约（集合名 / 字段 / 路径 / 参数 / 响应）。
- 不擅自升级依赖或引入新框架；需求与规范冲突先指出。
- 改代码保留原注释；改动范围收敛。
- 完成后说明：改了哪些文件、为什么、有无未完成项。

## 12. 关键不变量与已知陷阱

| 主题 | 结论 |
|---|---|
| 采集顺序 | **先落库、再推游标**；崩了最多重复写（upsert 幂等），不会漏。 |
| 幂等 | `books.bookId` 唯一索引 + `bulkOps().upsert()`；同 bookId 不重复插入、不覆盖 `firstCollectedAt`。 |
| 游标 | 归属**目标**（`(targetType, targetId)`），不是任务；重建任务不丢进度。 |
| 上游上限 | 分类 / 榜单接口最多给 **500 本（25 页）**；`totalCount` 不是可采完的分母。 |
| 书单（App 端） | URL 必带 `synckey`（后端 `new Date()` 秒值）；凭证八项缺一 → 「书单配置信息不完整」，不发请求；响应拍平 = 缺 synckey 的降级。 |
| 书单响应 | 书在 `booklist.booklist.books`，元素外包 `bookInfo`；`BookParser` 只收带 `bookInfo` 的完整形态。 |
| 付费精选 | 已有书只 `$addToSet collectSource` + `$set paidCollect=1`，**不覆盖其它字段**；401 → 暂停；断点 / 状态在 `paid_collect_state`。 |
| 书单同步 | 二级分类归并到一级；书数 ≥50 且 收藏 ≥10000 的闸门**只对分类书单**生效。 |
| 敏感信息 | apiKey / skey / token / synckey 不进日志 / 注释 / 返回；只打长度或前缀（`@ToString.Exclude`）。 |
| 统计口径 | 分类分布按 **`books.categories.categoryId`**（书的实际分类），不是 `collectSource`。 |
| `@Async` | 不继承 MDC，需要手动 put / remove。 |
| 日志编码 | logback CONSOLE **GB18030**，FILE UTF-8。 |

## 13. 模块级 `AGENTS.md` 写规则

1. 只写非显而易见的；方法名 / getter 不写。
2. 每条铁律带「为什么」。
3. 陷阱写全：现象 → 根因 → 修法。
4. 文档同步只在**需求明确要求**时做（硬规则 2 推翻了「改完必须同步」）。
