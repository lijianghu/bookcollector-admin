# bookcollector-admin

把「微信读书分类 / 榜单图书采集」做成一个**可视化后台**的单机应用。

后端 Spring Boot + MongoDB，前端 Vue 3 + Element Plus。按分类或榜单建采集任务，
随时启动 / 暂停 / 恢复 / 取消，看进度与运行日志，浏览检索采到的图书，
并对每一次上游接口调用留下审计记录。

除采集之外还有两块自动化：**定时刷新**（8 个目标定期全量刷新）和
**AI 书籍解读**（挑一本高分书 → AI 写解读 → 落本地 Markdown → 发布到语雀）。

> **范围与取舍**
> 做：分类 / 榜单管理 → 采集任务 → 进度与日志 → 图书库 → 请求审计 → 定时刷新 → AI 解读发布。
> 不做：SSE 实时推送（手动刷新）、分布式 / 多实例、Docker 部署、ETL 迁移、数据导出、
> 权限体系（`roles` 字段存在，但不参与任何鉴权判断）。

---

## 功能

| 页面 | 能力 |
|---|---|
| **仪表盘** | 4 个指标卡（图书总数 / 分类数 / 任务数 / 今日新增）+ 4 张 ECharts 图（评分分布、年份分布、分类分布、采集趋势）。指标卡与图表**分两个接口**返回，数字先出、图表后到。「今日新增」卡可点击，弹出当天净新增图书的明细列表（与卡片数字同一口径，按需加载） |
| **图书库** | 14 个筛选条件（平台分类 —— **支持二级分类级联**，选「期刊杂志」时自动按出版时间倒序 / 二级分类 / 评分区间 / 阅读人数下限 / 采集来源 / 书名 / 作者 / 出版时间区间 / 评价标签 / 采集时间；**书名与作者为包含匹配**、大小写不敏感 —— 代价是未锚定正则走不了索引、全集合扫描，**已接受**）、服务端分页排序（排序字段走白名单）、列显隐持久化到 `localStorage`、详情抽屉 60 个字段分 5 组、**只提交被改过的字段**的编辑、批量删除 |
| **任务配置** | 任务增删改查 + 启动 / 暂停 / 恢复 / 取消 / 重试；7 状态机（`PENDING` `RUNNING` `PAUSED` `SUCCESS` `FAILED` `CANCELED` `INTERRUPTED`）；一个采集目标**同时只能有一个任务**；详情抽屉 3 个 Tab（概览 / 运行历史 / 运行日志）；重启时自动把遗留的 `RUNNING` / `PAUSED` 标成 `INTERRUPTED` |
| **分类配置** | 采集目标字典的增删改查与启停；删除时若有任务引用则拒绝；首次启动自动 seed **188 个分类**（22 个一级 + 166 个二级）+ **7 个榜单**，只补缺失、不覆盖已有修改 |
| **采集游标** | 查看每个目标采到哪了（`maxIndex`）；「重置到 0」与「指定起点」；游标**归属目标而不是任务**，所以重建任务不会丢进度 |
| **请求审计** | 每次上游调用记一条：页码 / 状态码 / 耗时 / 结果条数 / 失败原因；只读，失败行高亮 |
| **增量配置** | 8 个目标（7 个榜单 + 期刊杂志）一键或按 cron 全量刷新；**不建任务、不写运行记录、不碰游标** —— 所以不会干扰手工任务的断点续传；与手工任务共享同一把锁，冲突时**中止整批** |
| **AI 书籍解读** | 从候选清单里随机挑一本 → 生成解读 → 存成 `docs/{日期}_《书名》.md` → 发到语雀；**一次一本**；产出**不做校验**，AI 写什么就发什么。⚠️ 用之前要手工往 `ai_model` / `ai_yuque_token` 里配好模型与语雀 token（程序只读，不提供界面） |
| **登录** | Sa-Token 签发 token、会话落 Redis，**登出会真的让 token 失效**；密码存 MD5 摘要 |

---

## 技术栈

| 层 | 组件 |
|---|---|
| **后端** | Java **8** · Spring Boot **2.3.12** · Spring Data MongoDB（驱动 4.0.6）· Apache HttpClient 4.5.x · fastjson2 · springdoc-openapi-ui 1.6.15 · Lombok · Maven |
| **前端** | Vue 3.4 · Vite 5.2 · TypeScript 5.4（严格模式）· Element Plus 2.7 · Pinia · Vue Router（hash 模式）· ECharts 5.5（按需引入）· Axios |
| **存储** | MongoDB 7.0（业务数据唯一权威存储）· Redis 3.2（**必需** —— Sa-Token 的 token 与会话存在这里，登录态才能跨 JVM 重启存活） |

> 刻意**没有**引入 Guava：只为限速一个功能背 3MB 依赖不划算，自写的 `SimpleRateLimiter` 十行就够。

---

## 架构

```
Vue 3 + Element Plus ──HTTP /api/**──► Spring Boot 2.3
                                         Controller  收请求 + 参数校验 + 拼统一响应
                                             │
                                         Service     业务编排（状态机 / 级联 / 查询语义）
                                             │
                                         Repository  MongoTemplate 数据访问（复杂查询 / 聚合）
                                             │
                                         Entity      @Document 持久化映射
                                         横切：AuthInterceptor（鉴权）/ TraceIdFilter（链路 id）
                                               GlobalExceptionHandler（统一异常 → 业务码）
                                                │
                        ┌───────────────────────┴────────────────────────┐
                        ▼                                                ▼
              MongoDB（18 个集合）                          采集引擎 collector/
                                                             CollectLoop + 限速 / 重试 / 解析
                                                                        │ HTTP（自定义 Header）
                                                                        ▼
                                                                  微信读书接口
```

**一次采集任务的完整链路**

```
① 建任务   POST /api/tasks          写 collect_tasks（(targetType, targetId) 唯一）
② 启动     POST /api/tasks/{id}/start
             └─ 读游标 collect_cursors 拿起点
             └─ 申请采集权（同目标互斥）
             └─ @Async 提交到采集线程池，立即返回
③ 执行     CollectLoop 分页主循环（每页）：
             限速 → 请求 → 解析 → 先 upsert 落库 → 再推进游标 → 写审计 + 进度日志
④ 观察     前端手动刷新读 collect_task_runs（进度）与 collect_task_logs（日志）
⑤ 收尾     写 run 终态 → 更新任务状态 → 释放采集权
```

关键点：**先落库、再推游标**。这样中途崩了游标也不会跑到数据前面去，
重跑最多重复写入（upsert 幂等），不会漏数据。

---

## 快速开始

### 前置要求

| 组件 | 要求 |
|---|---|
| JDK | **8**（Spring Boot 2.3 不支持更高的编译目标） |
| Maven | 3.8+ |
| Node.js | 18+（推荐 20 / 22） |
| MongoDB | 7.x（5.x / 6.x 亦可） |
| Redis | **必需** —— Sa-Token 把 token / 会话存 Redis（`sa-token-redis-jackson`）；不启动则登录失败（最坏情况静默退回内存 DAO，重启后登录态丢失） |

### 1. 准备 MongoDB 与 Redis

本地 `27017` 有 MongoDB、`6379` 有 Redis 即可。**不需要手工建库建表** —— 首次启动时应用会自动建索引、
seed 字典（188 个分类 + 7 个榜单）并播种初始管理员。
（Redis 用来存登录会话，**必须启动**，否则登录会失败；库号固定 `0`，见 `application.yml`。）

### 2. 改配置

编辑 `backend/src/main/resources/application.yml`：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `spring.data.mongodb.uri` | `mongodb://localhost:27017/bookcollector` | 数据库地址 |
| `server.port` | `8080` | 后端端口（改了要同步改前端 `vite.config.ts` 的代理） |
| `bookcollector.auth.initial-username` | — | 首次启动、库为空时播种的初始管理员登录名 |
| `bookcollector.auth.initial-password` | — | **务必改成自己的**（落库前做 MD5，库里不存明文） |
| `sa-token.timeout` | `2592000` | 登录有效期（秒），默认 30 天 |
| `bookcollector.weread.rate-limit-per-second` | `1.0` | 采集限速（页/秒），调高有被上游限流的风险 |
| `bookcollector.weread.max-retry` | `3` | 单页失败重试次数 |

> `bookcollector.auth.*` 只是**首次启动、库为空时**播种初始管理员的参数，**不是登录校验的来源** ——
> 登录校验读的是 `sys_user` 集合。库里已有同名用户时这段配置完全不生效，
> 所以**改密码直接改库即可，不用重启**。

### 3. 启动后端

```bash
cd backend
mvn spring-boot:run
```

| | |
|---|---|
| 服务地址 | <http://127.0.0.1:8080> |
| 健康检查 | <http://127.0.0.1:8080/api/ping> |
| 接口文档 | <http://127.0.0.1:8080/swagger-ui.html> |

Swagger 里先调 `POST /api/auth/login` 拿 `data.token`，再点右上角 **Authorize** ——
只粘 token 本身，Swagger UI 会自动补 `Bearer ` 前缀。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 <http://127.0.0.1:5173>，用第 2 步配置的账号密码登录。
`vite.config.ts` 已配好 `/api` → `127.0.0.1:8080` 的代理，开发期不用处理跨域。

### 5. 试一次采集

1. 打开「分类配置」，确认字典已 seed
2. 打开「任务配置」→ 新建任务 → 选一个分类 → 保存
3. 点「启动」，在详情抽屉看「概览」Tab 的进度；细节切「运行历史」/「日志」
4. 采完到「图书库」查书，到「请求审计」看每次调用的状态码与耗时

**日志**：写在启动时工作目录下的 `logs/bookcollector.log`，按天滚动、保留 30 天。
用 `mvn spring-boot:run` 时 `pom.xml` 已注入 `-DLOG_DIR`，日志稳定落在 `<项目根>/logs/`。

---

## 接口约定

所有接口统一返回 `ResultBean<T>`：

```json
{ "code": 200, "message": "", "traceId": "cb58abbb0e614501", "data": {} }
```

| code | 含义 |
|---|---|
| **200** | 成功 —— **是 200，不是 0** |
| 400 | 参数错误 |
| 404 | 资源不存在 |
| 405 | 主键重复 |
| 500 | 系统错误 |
| 4100 | 身份已失效，前端跳登录 |

### HTTP 状态码恒为 200，业务结果看 body 里的 `code`

这样前端只需要在 axios 拦截器里判断一处。

**鉴权失败返的是 HTTP 200 + `code=4100`，不是 401** —— 这一条反直觉但很重要：
后端用 `AuthInterceptor`（`HandlerInterceptor`，不是 Filter）拦 `/api/**`，
放行 `/api/auth/login` 与 `/api/ping`；校验失败时**自己往 response 写 JSON**。
（`preHandle` 返回 `false` 后 `GlobalExceptionHandler` 不会介入，所以必须自己写。）

- token **只从请求头读**，格式必须是 `Authorization: Bearer <token>`
- 前端响应拦截器**只看 body 里的 `code`，不看 HTTP 状态码**；收到 4100 → 提示重新登录 → 清 token → 跳登录页

### 其他约定

- **编辑一律「只写传了的字段」**，绝不整本 `save()` —— 避免把并发写入的字段冲掉
- **删除语义**：删任务级联删 runs + logs，**不删游标**；删字典项时有任务引用则拒绝，只有游标则顺带清掉
- **`code` / `targetType` / `targetId` 不可改** —— 用 DTO 字段缺失让「不能改」在类型层面成立
- **参数校验消息**排序 + 限 3 条 + 换成人话（Validator 不保证 `getFieldErrors()` 顺序）
- **聚合管道直接写 `Document`**，方便复制到 mongosh 单独调试

---

## 设计要点

### `books` 的语义是「历史累计的并集」，不是「当前榜单的快照」

上游分类列表按 `readingCount` 排序，**并列时 tie-break 不稳定**。
实测同一个分类的前 100 名在几分钟内就会换掉一两本，而且是**来回抖**的
（掉出去的书过一会儿还会回来）。

| 时间尺度 | 行为 |
|---|---|
| 秒级（同一轮内） | **完全稳定** —— 同范围两次请求，`bookId` 集合一模一样 |
| 分钟级（跨轮） | **会漂移** —— 边界上有书进出 |

因此：

- **不要**拿 `books` 的总数去跟接口返回的 `totalCount` 对账（一个是并集，一个是当前快照）
- 跨较长时间重采同一范围，`books` 总数可能 ±N —— **这不是 bug**
- 幂等性的准确表述是「**对同一份响应，upsert 幂等**」，而不是「集合总数恒定」

这也是「**永不删除**」这条约定的由来：掉出榜单的书几分钟后还可能回来，
若按「以最新榜单为准、删掉不在榜的书」实现，数据会被反复删建。

### 上游每个分类**最多只给 500 本（25 页）**

`/web/bookListInCategory/{id}` 到 `maxIndex=480` 那一页之后就返回空列表
（`{"books":[],"hasMore":0}`），实测文学 / 精品小说 / 计算机 / 童书行为完全一致；
榜单更短。所以：

- 一个分类任务的天然跑道是 **25 页 ≈ 29 秒**（限速 1 页/秒），把页数上限设成 60 也没用
- 界面上的「该目标共 54086 本」是**上游报的总数**，不代表能采完，不要拿它当进度分母
- **这不是 bug**：代码对空页的处理是对的（`hasMore=false` → 正常结束，不报错、不死循环）

### 幂等 / 断点续传 / 限速

- `books.bookId` 上有唯一索引，写入走 `bulkOps().upsert()` 分片批量执行。
  同 `bookId` 不重复插入、也不覆盖 `firstCollectedAt`。唯一索引是**正确性依赖**，不是性能优化
- 游标按「**目标**」持久化在 `collect_cursors`（不是按任务）→ 重建任务不丢进度；
  取消 / 失败 / 应用重启后重新启动，都从上次位置继续
- 默认 **1 页/秒**，失败按 1s → 3s 退避重试，最多 3 次；**4xx 不重试**（重试没有意义）
- 采集线程池独立于 Web 线程池，**满了直接拒绝**而不是阻塞 HTTP 线程
- 暂停用 `ReentrantLock` + `Condition` **阻塞**实现，可以原地恢复；取消是**协作式**的
- 应用关闭时唤醒所有暂停线程并取消，避免线程泄漏

### 数据模型

16 个 MongoDB 集合（库名 `bookcollector`），索引在启动时由 `MongoIndexInitializer` 显式创建，
并带「字段漂移自愈」（索引字段路径改过时先 `drop` 再建）。

| 分组 | 集合 |
|---|---|
| 采集数据 | `books`（46 个业务字段 + 采集元数据，唯一索引 `bookId`） |
| 任务与游标 | `collect_tasks` · `collect_task_runs` · `collect_task_logs` · `collect_cursors` |
| 采集目标 | `taxonomy_config`（唯一索引 `(type, code)`） |
| 审计与配置 | `api_requests` · `settings` · `sys_user` |
| 定时刷新 | `refresh_jobs`（单例） |
| AI 解读 | `book_interpretations`（候选清单 + 解读记录，**不拆表**）· `ai_publish_job`（单例） |
| AI 配置（手工维护，程序只读） | `ai_model` · `ai_yuque_token` · `ai_taxonomy_config` · `ai_sensitive_words` |

> ⚠️ `ensureIndex` 的语义是「按名字 upsert」—— 同名索引已存在时**什么都不做**，不比较字段。
> 所以字段路径写错过一次，光改代码修不好已建出的索引；而且 MongoDB 对「同名不同 key」
> 会直接报 `IndexKeySpecsConflict` 拒绝重建。

---

## 常见问题

**MongoDB 连不上 / 想用 7.0 的新特性**
Spring Boot 2.3.12 管理的驱动是 **4.0.6**，对 MongoDB 7.0 是官方 **⊛ 部分兼容** ——
能连上、能跑 CRUD，只是用不到 7.0 的新特性。要完全兼容就在 `backend/pom.xml` 里启用
`<mongodb.version>4.11.1</mongodb.version>`（4.11 仍支持 Java 8）。

**Redis 连不上 / 配置前缀写错**
前缀：Spring Boot 2.3 用 **`spring.redis.*`**，不是 Spring Boot 3 的 `spring.data.redis.*`。
另外 Redis **必须启动** —— 登录会话存在 Redis 里（见「前置要求」），连不上时登录会失败。

**采集任务启动就失败 / 一页都采不到**
按顺序排查：① 看「请求审计」页的状态码与失败原因（4xx 还是 5xx 一目了然）
② 看「任务配置 → 详情 → 日志」Tab 的具体报错 ③ 网络是否可达上游
④ 同一目标是不是已经有任务在跑（同一目标只允许一个任务）。

**前端改了代码但页面没变化**
Vite 的 HMR 对**新增文件**和**路由表改动**经常不生效（尤其 `router/index.ts` 和 `main.ts`）。
手动 `Ctrl+Shift+R` 强刷，或在 dev server 终端按 `r` 重启。改了 `vite.config.ts` 则**必须**重启。

---

## 说明

本项目仅用于个人学习与技术研究，采集的数据请勿用于商业用途。
请遵守目标站点的服务条款与相关法律法规，并自行控制采集频率。
