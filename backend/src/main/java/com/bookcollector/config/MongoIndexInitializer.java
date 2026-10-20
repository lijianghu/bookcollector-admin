package com.bookcollector.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 索引初始化器 —— 显式创建全部索引。
 *
 * <h3>为什么不用 {@code @Indexed} 注解自动建</h3>
 * {@code application.yml} 里设了 {@code auto-index-creation: false}。原因有二：
 * <ol>
 *   <li>自动建索引的行为<b>不透明</b>——出问题时你不知道它到底建了没有、建成了什么样</li>
 *   <li>唯一索引失败（比如库里已有重复数据）时，自动建索引的报错往往被吞掉</li>
 * </ol>
 * 显式创建的好处是：建了哪些、叫什么名字、失败没有，全在启动日志里。
 *
 * <h3>顺带解决「集合不存在」的问题</h3>
 * 在一个不存在的集合上建索引，MongoDB 会<b>先把集合建出来</b>。
 * 所以这个初始化器跑完，它负责的 14 个集合就一定都存在了 —— 不需要单独写建集合的逻辑。
 * （`sys_user` 就是靠这一条建出来的：它没有别的建表入口。）
 *
 * <p>⚠️ 但<b>不是所有集合都走这里</b>：`refresh_jobs` / `ai_publish_job` 以及 4 张
 * 用户手工维护的 AI 配置表都没有建索引，它们靠首次写入（{@code loadOrCreate()} 的
 * {@code save}）或用户手工建集合来存在。所以「集合一定存在」这句话只对本类列出的那些成立。
 *
 * <h3>⚠️ R22：{@code uk_bookId} 不是性能优化</h3>
 * 它是<b>幂等写入的正确性依赖</b>。采集是「按 bookId upsert」，如果这个唯一索引缺失，
 * 并发或重试场景下同一本书会插出两条，后续所有统计、分页都会受影响。
 * 所以它必须在第一次采集之前就位 —— 这就是 S1 排在 S2 前面的原因。
 *
 * <p>执行顺序：{@link Ordered#HIGHEST_PRECEDENCE}，早于 {@link SeedRunner}，
 * 保证 seed 写入时唯一索引已经在了。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MongoIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public MongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("========== 开始初始化 MongoDB 索引 ==========");
        long start = System.currentTimeMillis();

        int created = 0;
        created += initBooks();
        created += initApiRequests();
        created += initCollectTasks();
        created += initCollectTaskRuns();
        created += initCollectTaskLogs();
        created += initCollectCursors();
        created += initTaxonomyConfig();
        created += initSettings();
        created += initSysUser();
        created += initBookInterpretations();
        created += initPublishRecord();
        created += initShelfBooks();
        created += initReadingList();
        created += initBooklists();

        log.info("========== 索引初始化完成：{} 个索引，耗时 {} ms ==========",
                created, System.currentTimeMillis() - start);
    }

    /**
     * books —— 图书主表。
     *
     * <p>7 个索引，基本都是查询/正确性刚需。唯一一个「为即将到来的查询提前建」的是
     * {@code idx_bookLists} —— 用户 2026-10-14 明确要求加上（P5 刚落地
     * {@code books.bookLists}，「按书单筛书 / 统计某书单采到多少本」是这条链路的
     * 自然下一步）。其余 6 个都是当前就有人用的。
     */
    private int initBooks() {
        IndexOperations ops = mongoTemplate.indexOps("books");

        // ★ R22：唯一索引。幂等 upsert 的正确性依赖，不是性能优化。
        ops.ensureIndex(new Index()
                .on("bookId", Sort.Direction.ASC)
                .unique()
                .named("uk_bookId"));

        // 按分类筛（「文学有哪些书」）
        ops.ensureIndex(new Index()
                .on("categories.categoryId", Sort.Direction.ASC)
                .named("idx_categories"));

        // 增量采集用（「最近一次采集是哪天」）
        ops.ensureIndex(new Index()
                .on("lastCollectedAt", Sort.Direction.ASC)
                .named("idx_lastCollectedAt"));

        // 按评分筛/排序。
        //
        // 🔴 S4 修正：字段是「顶层 newRating」（推荐值 0~1000），不是
        // newRatingDetail.newRating —— 后者不存在（newRatingDetail 只有
        // {good, fair, poor, recent, title}）。S1 建这个索引时字段路径写错了，
        // 于是索引建在一个所有文档都没有的字段上：既不报错，也完全没用。
        //
        // ⚠️ 因为索引名没变、字段变了，必须先把旧的删掉再建 ——
        // MongoDB 对同名索引的字段变更会直接报
        // 「Index with name: idx_newRating already exists with different options」。
        dropIndexIfFieldChanged(ops, "idx_newRating", "newRating");
        ops.ensureIndex(new Index()
                .on("newRating", Sort.Direction.ASC)
                .named("idx_newRating"));

        // 仪表盘用：「今日新增」「近 7 天采集量」都是按 firstCollectedAt 数新增
        // （口径见 StatsService 类注释 —— 用 firstCollectedAt 而不是 lastCollectedAt，
        //   因为后者每次 upsert 都刷新，重采同一批书会虚增）。
        // 注意这与 idx_lastCollectedAt 不是重复索引：两者服务于完全不同的查询
        // （「最近采过什么」 vs 「每天新增了多少」），字段不同，不能互相替代。
        ops.ensureIndex(new Index()
                .on("firstCollectedAt", Sort.Direction.ASC)
                .named("idx_firstCollectedAt"));

        // 「精选预览 / 全部精选」的排序：newRating↓ → newRatingCount↓ → _id↑
        // （`InterpretationRepository.CANDIDATE_SORT`，但**作用在 books 上**，
        //   所以索引要建在 books 而不是 book_interpretations）。
        //
        // 🔴 2026-10-13 加：在此之前这三个字段**没有任何复合索引**，实测 explain
        // `docsExamined = 51577`（全表扫描 + 内存排序），「精选预览」一次 ~500ms。
        // ⚠️ 与 idx_newRating **不是重复索引**：那个只服务「按推荐值筛/排」；
        // 这个必须带上 newRatingCount 与 _id 才能覆盖完整排序 —— 分页要的是**全序**，
        // 只按 newRating 排会让同分的书在翻页时重复/漏。
        // ⚠️ 方向必须与 CANDIDATE_SORT 完全一致（DESC/DESC/ASC）：
        // 复合索引只有在「完全匹配」或「完全反向」时才能免掉内存排序。
        ops.ensureIndex(new Index()
                .on("newRating", Sort.Direction.DESC)
                .on("newRatingCount", Sort.Direction.DESC)
                .on("_id", Sort.Direction.ASC)
                .named("idx_newRating_ratingCount_id"));

        // 按书单筛书（「这张书单里有哪些书」「某张书单采到多少本」）。
        //
        // 🔴 2026-10-14 加：`books.bookLists` 是 P5 新增的字段 —— 一个数组，
        //    元素形如 `{bookListId, bookListName}`，写入走 `$addToSet` 跨次累加
        //    （一本书属于多个书单时会有多个元素）。
        // ⚠️ 路径写成 `bookLists.bookListId`（数组里的字段）：MongoDB 会自动建
        //    **多键索引**（multikey），不需要任何额外声明 ——
        //    `{"bookLists.bookListId": "xxx"}` 这种查询会命中它。
        // ⚠️ 与 `idx_categories` **不是重复索引**：那个按平台分类（categoryId 是 int），
        //    这个按书单 ID（字符串），数据源和查询场景都不同。
        // ⚠️ 它也是本类唯一一个「当前没有调用方、为下一步铺路」的索引 ——
        //    所以上面类注释里单独点了名，别以为是漏删的。
        ops.ensureIndex(new Index()
                .on("bookLists.bookListId", Sort.Direction.ASC)
                .named("idx_bookLists"));

        return 7;
    }

    /**
     * 如果同名索引建在了别的字段上，先删掉它。
     *
     * <h3>为什么需要这个「自愈」动作</h3>
     * {@code ensureIndex} 的语义是「按名字 upsert」：同名索引已存在时它<b>什么都不做</b>，
     * 不会去比较字段。所以一旦字段路径写错过一次（本项目的 {@code idx_newRating}
     * 就写错过），改代码<b>不会</b>修好已经建出来的索引 —— 它会一直是个无用的索引，
     * 而且没有任何提示。加了这段之后，重启服务就自动纠正。
     *
     * <p>代价是每次启动多一次 {@code listIndexes}（毫秒级），换来的是
     * 「索引定义和代码不可能长期不一致」。
     *
     * @param expectedFirstField 期望的第一个索引字段。只有「单字段索引」才做校正，
     *                           复合索引的字段顺序语义复杂，不做自动判断
     */
    private void dropIndexIfFieldChanged(IndexOperations ops, String indexName, String expectedFirstField) {
        try {
            for (IndexInfo info : ops.getIndexInfo()) {
                if (!indexName.equals(info.getName())) {
                    continue;
                }
                List<IndexField> fields = info.getIndexFields();
                boolean sameField = fields.size() == 1
                        && expectedFirstField.equals(fields.get(0).getKey());
                if (sameField) {
                    return;
                }
                ops.dropIndex(indexName);
                log.warn("索引 {} 建在错误的字段上（实际 {}，期望 {}），已删除，将按正确字段重建",
                        indexName, describeFields(fields), expectedFirstField);
                return;
            }
        } catch (Exception e) {
            // 校正失败不该拦住启动 —— ensureIndex 后面还会跑一次，
            // 最坏情况就是维持现状（与加这段之前的行为一致）
            log.warn("检查索引 {} 的字段时出错，跳过校正：{}", indexName, e.getMessage());
        }
    }

    private String describeFields(List<IndexField> fields) {
        StringBuilder sb = new StringBuilder();
        for (IndexField f : fields) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(f.getKey());
        }
        return sb.toString();
    }

    /** api_requests —— 写入量大，索引克制，只建真正会查的两个 */
    private int initApiRequests() {
        IndexOperations ops = mongoTemplate.indexOps("api_requests");

        // 「最近有哪些请求失败」——按时间倒序翻
        ops.ensureIndex(new Index()
                .on("createdAt", Sort.Direction.DESC)
                .named("idx_createdAt"));

        // 「某次运行打了哪些请求」
        ops.ensureIndex(new Index()
                .on("runId", Sort.Direction.ASC)
                .named("idx_runId"));

        return 2;
    }

    private int initCollectTasks() {
        IndexOperations ops = mongoTemplate.indexOps("collect_tasks");

        // ★ 一个目标只能有一个任务。
        //
        // 这不是「防重复提交」那种可有可无的约束，而是游标模型的一致性要求：
        // collect_cursors 的主键是 (targetType, targetId)，即「游标是目标的属性，
        // 不是任务的属性」（见 CollectCursor 类注释）。如果允许两个任务采同一个目标，
        // 它们会共享同一个游标 —— A 采到第 100 页后，B 启动时会从第 100 页继续而不是从 0，
        // 而界面上看不出任何异常。这种「静默的意外行为」比直接报错难排查得多。
        //
        // ⚠️ S1 阶段漏建了这个索引，S3 写 TaskService.create() 时才发现
        // （单元测试「同一目标不能建两个任务」没有抛异常）。补建时若库里已有重复的
        // (targetType, targetId)，启动会失败 —— 那时先手工去重：
        //   db.collect_tasks.aggregate([{$group:{_id:{t:"$targetType",i:"$targetId"},n:{$sum:1}}},
        //                               {$match:{n:{$gt:1}}}])
        ops.ensureIndex(new Index()
                .on("targetType", Sort.Direction.ASC)
                .on("targetId", Sort.Direction.ASC)
                .unique()
                .named("uk_target"));

        ops.ensureIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .named("idx_status"));

        ops.ensureIndex(new Index()
                .on("createdAt", Sort.Direction.DESC)
                .named("idx_createdAt"));

        return 3;
    }

    private int initCollectTaskRuns() {
        IndexOperations ops = mongoTemplate.indexOps("collect_task_runs");

        // 「某任务的历史运行记录」
        ops.ensureIndex(new Index()
                .on("taskId", Sort.Direction.ASC)
                .named("idx_taskId"));

        // 「最近跑了什么」+ 「有没有卡死的任务」
        ops.ensureIndex(new Index()
                .on("startedAt", Sort.Direction.DESC)
                .named("idx_startedAt"));

        return 2;
    }

    private int initCollectTaskLogs() {
        IndexOperations ops = mongoTemplate.indexOps("collect_task_logs");

        // 日志永远按 runId 查，不按时间全表翻
        ops.ensureIndex(new Index()
                .on("runId", Sort.Direction.ASC)
                .named("idx_runId"));

        return 1;
    }

    private int initCollectCursors() {
        IndexOperations ops = mongoTemplate.indexOps("collect_cursors");

        // 一个 target 只能有一行游标，否则断点续传会读到不确定的值
        ops.ensureIndex(new Index()
                .on("targetType", Sort.Direction.ASC)
                .on("targetId", Sort.Direction.ASC)
                .unique()
                .named("uk_target"));

        return 1;
    }

    private int initTaxonomyConfig() {
        IndexOperations ops = mongoTemplate.indexOps("taxonomy_config");

        // 同一个 (type, code) 只能有一条，seed 幂等的依赖
        ops.ensureIndex(new Index()
                .on("type", Sort.Direction.ASC)
                .on("code", Sort.Direction.ASC)
                .unique()
                .named("uk_type_code"));

        // 前端下拉框按 sort 排
        ops.ensureIndex(new Index()
                .on("sort", Sort.Direction.ASC)
                .named("idx_sort"));

        return 2;
    }

    private int initSettings() {
        IndexOperations ops = mongoTemplate.indexOps("settings");

        ops.ensureIndex(new Index()
                .on("key", Sort.Direction.ASC)
                .unique()
                .named("uk_key"));

        return 1;
    }

    /**
     * sys_user —— 登录用户表（鉴权改造新增，2026-09-23）。
     *
     * <p>只建 1 个索引，但它是<b>唯一索引</b>，不是性能优化：
     * {@code username} 必须全局唯一，否则 {@code SysUserRepository.findByUsername}
     * 可能返回不确定的一条，登录就变成「看运气」了。
     *
     * <p>它同时是 {@code sys_user} 集合<b>唯一的建表入口</b> ——
     * 见类注释「顺带解决集合不存在的问题」。
     */
    private int initSysUser() {
        IndexOperations ops = mongoTemplate.indexOps("sys_user");

        ops.ensureIndex(new Index()
                .on("username", Sort.Direction.ASC)
                .unique()
                .named("uk_username"));

        return 1;
    }

    /**
     * book_interpretations —— AI 书籍解读的候选清单 + 解读记录（2026-10-09 新增）。
     *
     * <p>只建 2 个索引。
     *
     * <h3>⚠️ 为什么只碰这一张表，不碰那 4 张用户手工维护的表</h3>
     * {@code ai_model} / {@code ai_yuque_token} / {@code ai_taxonomy_config} /
     * {@code ai_sensitive_words} 由用户自己建好并灌了真实数据。给它们补唯一索引
     * （比如 {@code ai_sensitive_words.word}）看着无害，实际上<b>一旦库里已有重复值，
     * {@code ensureIndex} 会直接抛异常把应用拦在启动之外</b> ——
     * 而那几张表都是几十条以内的小集合，本来也不需要索引。
     *
     * <h3>★ {@code uk_bookId} 是正确性依赖，不是性能优化</h3>
     * 同 {@code books.uk_bookId} 的道理：初始化候选走的是「按 bookId upsert」，
     * 没有这个唯一索引，重复初始化或并发初始化会插出两条同一本书的记录，
     * 于是同一本书被解读两次、发两篇到语雀。
     *
     * <p>{@code idx_status_rating} 同时服务两个查询：挑书
     * （{@code status = PENDING} + {@code newRatingCount} 降序 + {@code _id} 兜底）
     * 和按状态计数/筛选（复合索引的最左前缀就是 {@code status}，
     * 所以不再单独建 {@code idx_status}）。
     */
    private int initBookInterpretations() {
        IndexOperations ops = mongoTemplate.indexOps("book_interpretations");

        ops.ensureIndex(new Index()
                .on("bookId", Sort.Direction.ASC)
                .unique()
                .named("uk_bookId"));

        ops.ensureIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("newRatingCount", Sort.Direction.DESC)
                .on("_id", Sort.Direction.ASC)
                .named("idx_status_rating"));

        return 2;
    }

    /**
     * publish_record —— 发布记录流水（2026-10-10 新增）。
     *
     * <p>只建 1 个索引：列表就一个查询，按 {@code publishedAt} 倒序翻页。
     *
     * <h3>⚠️ 刻意不建唯一索引</h3>
     * 同一本书可以发布多次（页面的「重置为待解读」明确提示过「重新生成会在语雀里
     * 新增一篇」），所以 {@code bookId} <b>不能</b>唯一。
     * {@code yuqueDocId} 理论上一次发布一个、可以唯一，但历史数据由用户用脚本补录
     * （见 {@code tools/backfill_publish_records.py}），
     * 一旦旧数据里有重复值，唯一索引会<b>把应用拦在启动之外</b> ——
     * 代价远大于收益（去重交给补录脚本自己做）。
     *
     * <p>它同时是 {@code publish_record} 集合的<b>唯一建表入口</b> ——
     * 见类注释「顺带解决集合不存在的问题」。
     */
    private int initPublishRecord() {
        IndexOperations ops = mongoTemplate.indexOps("publish_record");

        ops.ensureIndex(new Index()
                .on("publishedAt", Sort.Direction.DESC)
                .named("idx_publishedAt"));

        return 1;
    }

    /**
     * shelf_books —— 藏书阁（2026-10-10 新增）。
     *
     * <p>只建 2 个索引。
     *
     * <h3>★ {@code uk_bookId} 是正确性依赖，不是性能优化</h3>
     * 同一本书在藏书阁里只能有一行 —— 这是「重复添加是幂等的」的前提
     * （见 {@code ShelfServiceImpl.add}：先查在不在再插，并发下靠这个索引兜底）。
     *
     * <p>{@code idx_category_addedAt} 服务「按一级类目取书」：{@code category} 这一段
     * 用于前缀匹配（{@code ^\Q文学\E(-|$)}，正则带 {@code ^} 锚定、能用上索引）。
     *
     * <p>⚠️ 后两段 {@code addedAt} / {@code _id} <b>已不再匹配当前排序</b>：2026-10-10
     * 列表排序从「按加入时间」改成了「评分优先」（见 {@code ShelfBookRepository.pageBooks}）。
     * 因为 {@code category} 是<b>前缀范围</b>而非等值，排序本来也用不上索引的后继字段 ——
     * 实际由 MongoDB 做内存排序；集合只有几千条，这个代价可以接受。
     * <b>不为了对齐排序去改索引定义</b>：{@code ensureIndex} 不会删旧索引，
     * 改名/改字段会在库里留下一个孤儿索引。
     *
     * <p>它同时是 {@code shelf_books} 集合的<b>唯一建表入口</b> ——
     * 见类注释「顺带解决集合不存在的问题」。
     */
    private int initShelfBooks() {
        IndexOperations ops = mongoTemplate.indexOps("shelf_books");

        ops.ensureIndex(new Index()
                .on("bookId", Sort.Direction.ASC)
                .unique()
                .named("uk_bookId"));

        ops.ensureIndex(new Index()
                .on("category", Sort.Direction.ASC)
                .on("addedAt", Sort.Direction.DESC)
                .on("_id", Sort.Direction.DESC)
                .named("idx_category_addedAt"));

        return 2;
    }

    /**
     * reading_list —— 阅读清单（2026-10-12 新增）。
     *
     * <p>只建 2 个索引。
     *
     * <h3>★ {@code uk_bookId} 是正确性依赖，不是性能优化</h3>
     * 同一本书在阅读清单里只能有一行 —— 这是「重复加入是幂等的」的前提
     * （见 {@code ReadingListServiceImpl.add}：先查在不在再插，并发下靠这个索引兜底）。
     *
     * <p>⚠️ 它与 {@code shelf_books} 的 {@code uk_bookId} 是<b>两张表各自的</b>唯一键 ——
     * 「收藏」与「阅读计划」是两个独立维度：同一本书可以在两张表里各有一行，
     * 也可以只在一张里。这里不存在、也不该有跨表的唯一约束。
     *
     * <p>{@code idx_status_updated} 服务「按状态筛 + 按最近变更排序」：复合索引的
     * 最左前缀就是 {@code status}，所以不再单独建 {@code idx_status}。
     *
     * <p>它同时是 {@code reading_list} 集合的<b>唯一建表入口</b> ——
     * 见类注释「顺带解决集合不存在的问题」。
     */
    private int initReadingList() {
        IndexOperations ops = mongoTemplate.indexOps("reading_list");

        ops.ensureIndex(new Index()
                .on("bookId", Sort.Direction.ASC)
                .unique()
                .named("uk_bookId"));

        ops.ensureIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("statusUpdatedAt", Sort.Direction.DESC)
                .named("idx_status_updated"));

        return 2;
    }

    /**
     * booklists —— 书单表（2026-10-14 新增）。
     *
     * <p>只建 4 个索引。
     *
     * <h3>★ {@code uk_booklistId} 是正确性依赖，不是性能优化</h3>
     * 同一张书单会被<b>多个平台分类</b>的采集扫到（接口按分类分页返回），
     * 写入走的是「按 {@code booklistId} upsert」。没有这个唯一索引，
     * 并发或重试场景下同一张书单会被插成多行 —— 而表现只是「书单列表里多了几条重复」，
     * 极难归因（同 {@code books.uk_bookId}，R22）。
     *
     * <p>⚠️ 它是<b>新建集合</b>上的唯一索引，没有「库里已有重复值把应用拦在启动之外」
     * 的风险（{@code publish_record} 当初刻意不建唯一索引正是为了避开这个，
     * 因为那张表的历史数据是脚本补录的）。
     *
     * <h3>另外 3 个索引各服务一个已知查询</h3>
     * <ul>
     *   <li>{@code idx_platformCategories} —— 书单列表页按<b>平台分类</b>筛
     *       （{@code platformCategories.categoryId} 是数组字段，MongoDB 会自动为数组
     *       建多键索引，等值查询能用上）。</li>
     *   <li>{@code idx_collectCount} —— 书单列表页按<b>收藏人数</b>排序；
     *       这也是链路 A 的阈值字段（{@code >= 10000}）。</li>
     *   <li>{@code idx_collectStatus} —— 书单列表页按<b>采书状态</b>筛
     *       （待采 / 已采 / 失败）。</li>
     * </ul>
     *
     * <p>它同时是 {@code booklists} 集合的<b>唯一建表入口</b> ——
     * 见类注释「顺带解决集合不存在的问题」。
     */
    private int initBooklists() {
        IndexOperations ops = mongoTemplate.indexOps("booklists");

        ops.ensureIndex(new Index()
                .on("booklistId", Sort.Direction.ASC)
                .unique()
                .named("uk_booklistId"));

        ops.ensureIndex(new Index()
                .on("platformCategories.categoryId", Sort.Direction.ASC)
                .named("idx_platformCategories"));

        ops.ensureIndex(new Index()
                .on("collectCount", Sort.Direction.DESC)
                .named("idx_collectCount"));

        ops.ensureIndex(new Index()
                .on("collectStatus", Sort.Direction.ASC)
                .named("idx_collectStatus"));

        return 4;
    }
}
