# publish 模块

> `com.bookcollector.publish` · 内容产出侧：挑书 → AI 写解读 → 存 Markdown → 发语雀。

## 职责

从 `book_interpretations` 挑一本 `PENDING` → AI 生成 → 存 `{outputDir}/{yyyy-MM-dd}_《书名》.md` → 发语雀；一次一本；cron + 手动。
不依赖 `collector` / `task` / `refresh`；只从 `books` 快照候选。

## 关键类

| 类 | 职责 |
|---|---|
| `PublishController` / `PublishRecordController` | 10 + 1 个端点 |
| `PublishService(Impl)` | 编排 + 两个单线程池 + 两个 `AtomicBoolean` 互斥 + 内存进度 |
| `InterpretationRepository` | 候选 upsert / 挑书 / 状态流转 / 分页 / 精选配置查 `books` |
| `AiClient` | OpenAI 兼容 `POST {baseUrl}/chat/completions` |
| `YuqueClient` | 建文档 + 挂目录 |
| `Interpretation` / `PublishJob` / `PublishRecord` | 候选 + 解读 / 单例配置 / 发布流水 |
| `AiModel` / `AiYuqueToken` / `AiTaxonomyItem` / `SensitiveWord` | 用户手工维护，程序只读 |

## 数据

- `book_interpretations`：`uk_bookId` 唯一（候选 upsert 的正确性依赖）、`idx_status_rating`（status + `newRatingCount`↓ + `_id`↑）。
- `ai_publish_job`：`_id="default"` 单例。
- `publish_record`：只插不改。
- 4 张 `ai_*` 配置表：用户手工维护，`MongoIndexInitializer` 刻意不碰。

## 精选配置

- `CandidateBuildRequest`：`categoryIds` / `booklistId` / `ratingTitles` / `minRating` / `title` / `author` / `excludeCollected` / `all` / `bookIds`。
- **书单模式**：`booklistId` 有值时忽略分类 / 标签 / 推荐值 / 书名作者，只查 `bookLists.bookListId`。
- 预览与精选共用一份请求体（`previewCandidates` / `collectCandidates`），保证「预览到什么就精选什么」。
- 推荐值前端百分制，后端 ×10 换算。

## 不变量

1. 一次只做一本；两个单线程池（init / gen）不共用。
2. 候选 upsert 按 `bookId`；`uk_bookId` 是正确性依赖。
3. 挑书两段式：`status=PENDING` 按 `newRatingCount` 取前 100（只投影 `_id`）→ shuffle → 取一条点查。
4. `publish_record` 只插不改；发布记录不暴露新增接口。
5. 敏感词按词长降序替换；`sensitiveHits` 记原词。
6. `AiConfigRepository` 里永远不许出现 key / token 内容；日志只打长度或前缀。
7. 生成 / 发布失败也要落 `rawContent`（AI 调用已花掉）。
