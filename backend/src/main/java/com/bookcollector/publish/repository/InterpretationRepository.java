package com.bookcollector.publish.repository;

import com.bookcollector.common.PageResult;
import com.bookcollector.publish.entity.Interpretation;
import com.bookcollector.publish.entity.PublishStatus;
import com.bookcollector.publish.req.CandidateBuildRequest;
import com.bookcollector.publish.req.InterpretationQuery;
import com.bookcollector.util.PageQueryUtil;
import com.bookcollector.util.RegexUtil;

import com.mongodb.bulk.BulkWriteResult;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 候选清单 + 解读记录（{@code book_interpretations}）的 Mongo 访问。
 *
 * <h3>它承担两件事</h3>
 * <ol>
 *   <li><b>初始化候选</b>：从 {@code books} 里按条件捞书，<b>原样快照</b>进来
 *       （用户 2026-10-09 要求「快照所有字段，除了 {@code _class}」）；</li>
 *   <li><b>挑书与状态流转</b>：挑一条 {@code PENDING}，依次推进到 PUBLISHED / FAILED。</li>
 * </ol>
 *
 * <h3>🔴 为什么初始化用 {@code Document} 而不是实体</h3>
 * 「快照所有字段」只有走<b>原始 BSON</b> 才做得到 —— 用实体序列化的话，
 * 实体没声明的字段就丢了，而且 {@code books} 将来加字段也不会自动跟随。
 * 所以这里是：{@code stream(..., Document.class, "books")} 拿原始文档 →
 * {@code remove("_class")} → 加状态字段 → {@code $setOnInsert} 批量 upsert。
 *
 * <p>⚠️ {@code books} 里的文档<b>确实带 {@code _class}</b>
 * （{@code BookRepository.buildUpsertUpdate} 用 {@code converter.write()} 摊平实体时会写入），
 * 所以那句 {@code remove} 不是可选项。
 *
 * <h3>为什么 {@code $setOnInsert} 而不是 {@code $set}</h3>
 * 用户要求「已有就忽略插入，跳过」。{@code $setOnInsert} 恰好是这个语义：
 * 命中已有文档时<b>整个 update 都不生效</b> —— 所以已经解读过（甚至失败过）的书
 * 不会被重新初始化冲掉。同时它让 {@code upsertedCount} 直接等于「本次真正插入数」，
 * 零额外查询。
 */
@Repository
public class InterpretationRepository {

    private static final Logger log = LoggerFactory.getLogger(InterpretationRepository.class);

    /** 候选来源集合。写死字面量：跨模块引用 {@code book} 模块的常量会形成不必要的耦合 */
    private static final String BOOKS_COLLECTION = "books";

    /** 一次 bulkWrite 的批大小。与 {@code BookRepository.IN_QUERY_CHUNK} 同量级 */
    private static final int BATCH_SIZE = 500;

    /** {@code $in} 查询一次最多带多少个 bookId，避免 BSON 文档超过 16MB 上限 */
    private static final int IN_QUERY_CHUNK = 1000;

    /**
     * 挑书时先捞的 {@code _id} 池大小（只投影 {@code _id}），见 {@link #pickOnePending()}。
     * 池子越大越随机、查询越贵；100 条是纯索引扫描，开销可忽略。
     */
    private static final int PICK_POOL_SIZE = 100;

    /**
     * 精选预览的排序：推荐值降序 → 评价人数降序 → {@code _id} 升序。
     *
     * <p>前两项决定「好书排前面」；最后一项是<b>必须</b>的兜底 ——
     * 同分的一大批书（榜单里很常见）只按前两项排序时，翻页会出现重复行 / 漏行。
     *
     * <p>写法与 {@link #page(InterpretationQuery)} 里的 {@code and(...)} 链一致：
     * 两级不同方向的排序必须能表达，所以不用单字段重载。
     */
    private static final Sort CANDIDATE_SORT = Sort.by(Sort.Direction.DESC, "newRating")
            .and(Sort.by(Sort.Direction.DESC, "newRatingCount"))
            .and(Sort.by(Sort.Direction.ASC, "_id"));

    private final MongoTemplate mongoTemplate;

    public InterpretationRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // 初始化候选
    // ==================================================================

    /**
     * 流式扫描 {@code books} 里符合条件的书，返回<b>原始 BSON 文档</b>。
     *
     * <h3>为什么是 stream 而不是一次 find</h3>
     * 命中几千本时，一次性 find 到内存里会有几 MB 的 {@code intro} 文本；
     * 而且那样必须等全部查完才能开始写，进度条会长时间卡在 0。
     * 游标式遍历可以「边读边分批写」，进度实时可见 —— 这正是用户要的「不要卡住一直等待」。
     *
     * <p>⚠️ 调用方<b>必须</b>在 finally 里关闭返回的迭代器。
     *
     * <p>条件（三个都是用户 2026-10-09 指定的）：
     * <ul>
     *   <li>{@code categories.categoryId ∈ 白名单}（走 {@code idx_categories} 索引）</li>
     *   <li>{@code newRatingTitle ∈ [神作, 好评如潮, 神作潜力, 脍炙人口, 值得一读]}</li>
     *   <li>{@code newRatingCount > 500}</li>
     * </ul>
     * ⚠️ 条件写的是<b>顶层路径</b> {@code newRatingTitle} / {@code newRatingCount}，
     * 不是 {@code newRatingDetail.*}。本项目踩过一次同类坑（{@code BookRepository} 的注释有记录）：
     * 路径写错会静默返回 0 条且不报错。
     *
     * <h3>🔴 参数为什么是 {@code Integer} 而不是 {@code String}</h3>
     * {@code books.categories[].categoryId} 在库里是 <b>int</b>
     * （实测：{@code categories[0].categoryId = 300000}，不是 {@code "300000"}），
     * 而 {@code ai_taxonomy_config.code} 是 <b>String</b>。
     * <b>MongoDB 的等值/`$in` 匹配是类型敏感的</b> —— 拿 {@code ["300000"]} 去匹配 int 的
     * {@code 300000} <b>恒不命中，而且不报错</b>。
     *
     * <p>实测对比（2026-10-09，同一批条件）：
     * <pre>
     *   String 列表 → 命中 0 条     ← 静默失败
     *   Integer 列表 → 命中 4533 条
     * </pre>
     * 所以「code → Integer」的转换由调用方负责（它才知道来源是那张表的字符串列），
     * 这里只接受已经转好的 Integer。
     * 参考：{@code BookRepository.countByCategory(Integer)} 与
     * {@code BookQuery.categoryId} 都是 Integer —— 全项目这个字段一律用整数。
     */
    public CloseableIterator<Document> streamCandidates(Collection<Integer> categoryIds,
                                                       Collection<String> ratingTitles,
                                                       int minRatingCount) {
        Criteria c = new Criteria().andOperator(
                Criteria.where("categories.categoryId").in(categoryIds),
                Criteria.where("newRatingTitle").in(ratingTitles),
                Criteria.where("newRatingCount").gt(minRatingCount));
        return mongoTemplate.stream(new Query(c), Document.class, BOOKS_COLLECTION);
    }

    /**
     * 把一批快照文档 upsert 进清单。已有 {@code bookId} 的记录<b>一律跳过</b>。
     *
     * @param docs 已去掉 {@code _class}、已补上状态字段的文档
     * @return <b>本次真正插入</b>的条数（取 {@code BulkWriteResult.getUpserts().size()}，零额外查询）
     */
    public int upsertSnapshot(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        BulkOperations bulk = mongoTemplate.bulkOps(
                BulkOperations.BulkMode.UNORDERED, Interpretation.class);

        int queued = 0;
        for (Document doc : docs) {
            if (doc == null) {
                continue;
            }
            Object raw = doc.get("bookId");
            String bookId = raw == null ? null : String.valueOf(raw).trim();
            if (bookId == null || bookId.isEmpty()) {
                continue;
            }

            // 🔴 _id 走「查询条件」而不是 $setOnInsert —— 这是 MongoDB 官方文档里
            //    $setOnInsert 的标准用法（`{_id: 1}` + `$setOnInsert: {...}`），
            //    插入时 _id 由查询的等值条件带进去。
            //    不这么做的话就要把 _id 写进 $setOnInsert，而「$setOnInsert 能否设置 _id」
            //    官方文档没有给示例、也没有明确背书 —— 不去赌没写进文档的行为。
            //    两种写法的**结果文档完全一样**（_id + 全部快照字段）。
            Document insert = new Document(doc);
            insert.remove("_id");

            Criteria match = Criteria.where("bookId").is(bookId);
            Object rawId = doc.get("_id");
            if (rawId != null) {
                match = match.and("_id").is(rawId);
            }

            bulk.upsert(Query.query(match),
                    Update.fromDocument(new Document("$setOnInsert", insert)));
            queued++;
        }
        if (queued == 0) {
            return 0;
        }
        try {
            BulkWriteResult result = bulk.execute();
            return result.getUpserts().size();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_bookId 冲突：同一批里出现了重复 bookId。属数据问题，不吞
            log.error("初始化清单触发唯一索引冲突（bookId 重复）：{}", e.getMessage());
            throw e;
        }
    }

    /** 一次 bulkWrite 的批大小，供 Service 分批用 */
    public int batchSize() {
        return BATCH_SIZE;
    }

    // ==================================================================
    // 精选配置：从图书库按条件挑书（2026-10-12 新增）
    // ==================================================================

    /**
     * 精选预览分页：按「精选配置」的条件从 {@code books} 里查，<b>只读不写</b>。
     *
     * <h3>为什么不用 {@code PageQueryUtil}</h3>
     * 那个工具走的是 {@code mongoTemplate.find(query, clazz)} —— 集合名由实体上的
     * {@code @Document} 推导。这里的目标是 {@code books} 集合配 {@code Document.class}，
     * 推导出来会是 {@code document} 集合（错），所以必须显式带集合名。
     * 归一化口径仍复用它的 {@code normalizePage} / {@code normalizeSize}，
     * 保证与全项目的分页口径一致。
     *
     * <p>⚠️ 条件里 {@code title} / {@code author} 是<b>未锚定正则</b>，
     * 用不上索引（同 {@code BookQuery} 的口径，代价已知并接受）。
     *
     * @return 元素是 {@code books} 的<b>原始 BSON 文档</b>，由 Service 转
     *         {@code CandidateBookVO}（那里只取需要的 8 个字段）
     */
    public PageResult<Document> pageCandidateBooks(CandidateBuildRequest req, Integer pageNum, Integer pageSize) {
        int page = PageQueryUtil.normalizePage(pageNum);
        int size = PageQueryUtil.normalizeSize(pageSize);

        Query q = new Query(buildCandidateCriteria(req));
        // 先 count（此时还没加 skip/limit），为 0 直接返回空页，省一次 find
        long total = mongoTemplate.count(q, BOOKS_COLLECTION);
        if (total == 0L) {
            return PageResult.empty(page, size);
        }
        q.with(CANDIDATE_SORT).with(PageRequest.of(page - 1, size));
        List<Document> list = mongoTemplate.find(q, Document.class, BOOKS_COLLECTION);
        return PageResult.of(list, total, page, size);
    }

    /**
     * 流式扫描「全部精选」命中的书，返回原始 BSON 文档。
     *
     * <p>与 {@link #streamCandidates(Collection, Collection, int)} 同样的取舍：
     * 命中几千本时一次性 find 到内存会有几 MB 的 {@code intro} 文本，
     * 而且必须等全部查完才能开始写、进度条会长时间卡在 0。
     *
     * <p>⚠️ 调用方<b>必须</b>在 finally 里关闭返回的迭代器。
     */
    public CloseableIterator<Document> streamCandidateBooks(CandidateBuildRequest req) {
        return mongoTemplate.stream(new Query(buildCandidateCriteria(req)),
                Document.class, BOOKS_COLLECTION);
    }

    /**
     * 按 {@code bookId} 批量取图书库原始文档（「批量精选」用）。
     *
     * <p>勾选的是<b>当页</b>的行，数量在一页之内（≤200），但这里仍按
     * {@link #IN_QUERY_CHUNK} 分片 —— 与 {@link #deleteByBookIds(Collection)} 同形，
     * 免得以后「跨页全选」把它撑大。
     *
     * @return 命中的原始文档。请求里有 bookId 已不在图书库时，返回条数会少于请求条数
     */
    public List<Document> findBooksByIds(Collection<String> bookIds) {
        List<Document> out = new ArrayList<Document>();
        if (bookIds == null || bookIds.isEmpty()) {
            return out;
        }
        List<String> all = new ArrayList<String>();
        for (String id : bookIds) {
            if (id != null && !id.trim().isEmpty()) {
                all.add(id.trim());
            }
        }
        if (all.isEmpty()) {
            return out;
        }
        for (int i = 0; i < all.size(); i += IN_QUERY_CHUNK) {
            List<String> chunk = all.subList(i, Math.min(i + IN_QUERY_CHUNK, all.size()));
            out.addAll(mongoTemplate.find(Query.query(Criteria.where("bookId").in(chunk)),
                    Document.class, BOOKS_COLLECTION));
        }
        return out;
    }

    /**
     * 把「精选配置」的条件翻译成 {@code books} 上的 {@code Criteria}。
     *
     * <p>同 {@link #buildCriteria(InterpretationQuery)}：用 {@code andOperator} 组合
     * 而不是连续 {@code query.addCriteria(...)} —— 后者在同一字段上叠加条件时会被覆盖。
     *
     * <h3>🔴 两个类型敏感的字段</h3>
     * <ul>
     *   <li>{@code categories.categoryId} 在库里是 <b>int</b>，所以入参必须是
     *       {@code List<Integer>}（字符串会恒不匹配、静默返回 0 条，见
     *       {@link #streamCandidates} 的注释）；</li>
     *   <li>{@code newRating} 库里是 <b>0~1000</b>，而 {@link CandidateBuildRequest}
     *       收的是百分制 —— 换算在请求体里做（{@code resolveMinRatingValue()}），
     *       这里拿到的已经是库值。</li>
     * </ul>
     *
     * <h3>「隐藏已精选」的实现</h3>
     * 走 {@code bookId $nin 已有 bookId 列表}，即<b>应用层关联</b>而不是
     * {@code $lookup} 聚合。取舍：
     * <ul>
     *   <li>好处是「预览分页」和「全部精选的流式扫描」可以共用同一个 {@code Criteria}，
     *       不用为聚合再写一套分页 + count；</li>
     *   <li>代价是每次查询都要先把清单里的 bookId 全捞一遍。</li>
     * </ul>
     *
     * <p>⚠️ <b>2026-10-13 实测（清单已 11766 条）：<u>不要</u>改成 {@code $lookup}。</b>
     * 同一组条件（一级分类全选 + 推荐值 ≥ 800）下：
     * <ul>
     *   <li>{@code $nin}：count <b>266 ms</b> + find20 <b>225 ms</b>（另加捞 bookId 约 88 ms）；</li>
     *   <li>{@code $lookup}（先 {@code $match} 再 join，已是较优写法）：count <b>3627 ms</b>
     *       —— 约 <b>7 倍慢</b>。原因：{@code $lookup} 要按驱动集合逐条 join，
     *       而且<b>挡住了索引排序</b>，只能在内存里排 1.8 万条。</li>
     * </ul>
     * 而 {@code $nin} 的数组 11766 个 bookId ≈ <b>227 KB</b>（命令上限 16 MB，
     * Mongo 对 {@code $nin} 会建哈希集）—— 它<b>不是</b>瓶颈。
     * 真正的瓶颈是<b>全表扫描 + 内存排序</b>（{@link #CANDIDATE_SORT} 没有对应复合索引，
     * explain 显示 docsExamined = 51577）。
     * 要优化请往「加 {@code {newRating:-1, newRatingCount:-1, _id:1}} 复合索引」与
     * 「{@link #listExistingBookIds()} 换成 {@code distinct}」两个方向走。
     */
    private Criteria buildCandidateCriteria(CandidateBuildRequest req) {
        List<Criteria> conds = new ArrayList<Criteria>();
        if (req == null) {
            return new Criteria();
        }

        List<Integer> categoryIds = req.resolveCategoryIds();
        if (!categoryIds.isEmpty()) {
            conds.add(Criteria.where("categories.categoryId").in(categoryIds));
        }
        List<String> ratingTitles = req.resolveRatingTitles();
        if (!ratingTitles.isEmpty()) {
            conds.add(Criteria.where("newRatingTitle").in(ratingTitles));
        }
        Integer minRating = req.resolveMinRatingValue();
        if (minRating != null) {
            conds.add(Criteria.where("newRating").gte(minRating));
        }
        Pattern title = RegexUtil.contains(req.resolveTitle());
        if (title != null) {
            conds.add(Criteria.where("title").regex(title));
        }
        Pattern author = RegexUtil.contains(req.resolveAuthor());
        if (author != null) {
            conds.add(Criteria.where("author").regex(author));
        }
        if (req.excludeCollected()) {
            List<String> collected = listExistingBookIds();
            if (!collected.isEmpty()) {
                conds.add(Criteria.where("bookId").nin(collected));
            }
        }

        if (conds.isEmpty()) {
            return new Criteria();
        }
        if (conds.size() == 1) {
            return conds.get(0);
        }
        return new Criteria().andOperator(conds.toArray(new Criteria[0]));
    }

    // ==================================================================
    // 挑书
    // ==================================================================

    /**
     * 挑一条待解读的（{@code status = PENDING}）——
     * <b>在「评价人数最多的前 {@value #PICK_POOL_SIZE} 本」里随机挑一本</b>。
     *
     * <h3>两段式挑书（2026-10-09 用户指定）</h3>
     * <ol>
     *   <li>按 {@code status} + 排序取前 {@value #PICK_POOL_SIZE} 条，<b>只投影 {@code _id}</b>；</li>
     *   <li>把池子<b>打乱</b>，取其中一条的 {@code _id}，再按 {@code _id} 查整条。</li>
     * </ol>
     *
     * <p><b>为什么拆两步</b>：挑书只用到 {@code status} 与排序键，用不上正文。
     * 第一步的投影字段（{@code status} / {@code newRatingCount} / {@code _id}）
     * 正好全在 {@code idx_status_rating} 里，MongoDB 靠索引就能出结果、<b>不必回表</b>；
     * 第二步是 {@code _id} 点查，只取<b>一条</b>。
     * 于是「扫描阶段」永远不会碰到 {@code content} / {@code rawContent} / {@code intro}
     * 这几个几千字的字段。
     *
     * <p><b>为什么要打乱</b>：排序仍按 {@code newRatingCount} 降序 —— 它决定<b>谁进池子</b>，
     * 但<b>不再决定谁先被解读</b>。否则每次都从最高分那本开始，
     * 一本卡住（反复失败、或被重置回 PENDING）就会一直挡住后面 99 本。
     *
     * <p>⚠️ 随机的范围是<b>池内</b>，不是全库：池子永远是「评价人数最多的前
     * {@value #PICK_POOL_SIZE} 本 PENDING」。
     *
     * <p>🔴 2026-10-19：新增 {@code categoryIds} 过滤 —— 只从「发布范围」
     * （{@code pick_taxonomy_config} 里 enabled=true 的一级分类）里挑。
     * 传空集合 = 没有可发布范围，直接返回 {@code null}（由 Service 给出提示）。
     *
     * @param categoryIds 发布范围的一级分类 id（{@code categories[].categoryId}）
     * @return 清单为空 / 范围为空时返回 {@code null}，由 Service 给出提示
     */
    public Interpretation pickOnePending(List<Integer> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return null;
        }
        Query q = Query.query(Criteria.where("status").is(PublishStatus.PENDING.name())
                        .and("categories.categoryId").in(categoryIds))
                .with(Sort.by(Sort.Direction.DESC, "newRatingCount")
                        .and(Sort.by(Sort.Direction.ASC, "_id")))
                .limit(PICK_POOL_SIZE);
        q.fields().include("_id");

        List<Interpretation> pool = mongoTemplate.find(q, Interpretation.class);
        if (pool.isEmpty()) {
            return null;
        }
        // 打乱后取一条：池内顺序不再等于评分顺序（池里只有 _id，其余字段是 null，
        // 所以不能直接返回，必须按 _id 再查一次拿整条）
        Collections.shuffle(pool);
        return mongoTemplate.findOne(
                Query.query(Criteria.where("_id").is(pool.get(0).getId())), Interpretation.class);
    }

    public Interpretation findByBookId(String bookId) {
        if (bookId == null || bookId.trim().isEmpty()) {
            return null;
        }
        return mongoTemplate.findOne(
                Query.query(Criteria.where("bookId").is(bookId.trim())), Interpretation.class);
    }

    // ==================================================================
    // 状态流转
    // ==================================================================

    /**
     * 局部更新若干字段。{@code value} 传 {@code null} 时写成的就是 {@code null}
     * （用来「清掉上一次的失败原因」）。
     *
     * @return 是否真的改到了文档
     */
    public boolean updateFields(String bookId, Map<String, Object> sets) {
        if (bookId == null || bookId.trim().isEmpty() || sets == null || sets.isEmpty()) {
            return false;
        }
        Update update = new Update();
        for (Map.Entry<String, Object> e : sets.entrySet()) {
            update.set(e.getKey(), e.getValue());
        }
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("bookId").is(bookId.trim())), update, Interpretation.class)
                .getMatchedCount() > 0L;
    }

    /**
     * 把某条记录重置回「待解读」。
     *
     * <p>用户 2026-10-09 要求行内提供这个按钮。用途：
     * <ul>
     *   <li>把卡住的 {@code GENERATING} 捞回来（进程被杀会留下这种残留，
     *       而用户明确要求启动时<b>不做</b>自动清理）；</li>
     *   <li>把 {@code FAILED} 的重新排进队列（「失败不重试」是不<b>自动</b>重试）。</li>
     * </ul>
     */
    public boolean resetToPending(String bookId) {
        return resetStatus(bookId, PublishStatus.PENDING);
    }

    /**
     * 把某条记录的状态改成指定值（2026-10-16 由「重置」按钮演化成「重置为」：
     * 除待解读外，还允许手工标记成已发布）。
     *
     * <p>🔴 只允许 {@link PublishStatus#PENDING} / {@link PublishStatus#PUBLISHED}
     * 两个取值 —— 白名单在 {@code PublishService#resetTo} 里校验，这里是最后一道闸。
     * 不给 {@code GENERATING} 是因为「生成中」必须由挑书流程设置（它有 handle、
     * 会被 {@code job} 轮询推进），手设即成假死；不给 {@code FAILED} 是因为失败
     * 必须带 {@code errorMsg}，否则页面上「失败原因」一列讲不出故事。
     *
     * <p>{@code errorMsg} 无条件清空：两个目标状态都没有「失败原因」这个说法 ——
     * 尤其 {@code FAILED → PUBLISHED} 时，留着失败原因会让行里同时显示
     * 「已发布」和一条错误，自相矛盾。
     *
     * <p>⚠️ 不碰时间字段（{@code finishedAt} / {@code publishedAt} 等）：
     * 标记成「已发布」不等于真的发过一篇 —— 那只是清单状态的修正，
     * 写一个假时间进去会污染「最近发布」这类统计。
     */
    public boolean resetStatus(String bookId, PublishStatus status) {
        Update u = new Update()
                .set("status", status.name())
                .set("errorMsg", null)
                .set("updatedAt", new java.util.Date());
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("bookId").is(bookId)), u, Interpretation.class)
                .getMatchedCount() > 0L;
    }

    /**
     * 清空「未解读」的候选，<b>不碰</b>已发布 / 失败 / 生成中的记录。
     *
     * <p>用于换分类之后重置清单。
     */
    public long clearPending() {
        long n = mongoTemplate.remove(
                Query.query(Criteria.where("status").is(PublishStatus.PENDING.name())),
                Interpretation.class).getDeletedCount();
        log.info("已清空未解读候选 {} 条", n);
        return n;
    }

    // ==================================================================
    // 查询 / 统计
    // ==================================================================

    /**
     * 清单分页。<b>不含正文</b> —— 正文由 Service 转 {@code InterpretationVO} 时自然被丢掉。
     *
     * <h3>排序：由查询条件指定，{@code _id} 兜底</h3>
     * 用户 2026-10-13 要求 6 个字段都能正反序排（推荐值 / 评价人数 / 阅读人数 /
     * 好评数 / 中评数 / 差评数），<b>默认推荐值降序</b>。
     * 字段白名单与方向解析在 {@link InterpretationQuery#resolveSortPath()} /
     * {@link InterpretationQuery#resolveAsc()}（那里保证了「永远返回一个可用路径」）。
     *
     * <p>⚠️ {@code _id} 升序兜底是<b>必须</b>的：同值的一大批书（榜单里很常见）
     * 只按主字段排序时，翻页会出现重复行 / 漏行。
     *
     * <p>⚠️ 这已经<b>不再</b>与挑书时的池子排序（{@code newRatingCount} 降序）一致 ——
     * 那是有意的：清单是给人看的，用户要「先看好书」；挑书是给机器用的，
     * 要「先从评价人数多的里挑」。两者不必同向。
     *
     * <p>⚠️ 筛选条件里 {@code title} / {@code author} 是<b>未锚定正则</b>，
     * 用不上索引（见 {@link InterpretationQuery} 类注释）。清单只有几千条，可接受。
     */
    public PageResult<Interpretation> page(InterpretationQuery q) {
        InterpretationQuery query = q == null ? new InterpretationQuery() : q;
        Sort sort = Sort.by(query.resolveAsc() ? Sort.Direction.ASC : Sort.Direction.DESC,
                        query.resolveSortPath())
                .and(Sort.by(Sort.Direction.ASC, "_id"));
        return PageQueryUtil.getPageResult(mongoTemplate, query.getSafePage(), query.getSafeSize(),
                buildCriteria(query), Interpretation.class, sort);
    }

    /**
     * 把 {@link InterpretationQuery} 翻译成 Mongo 的 {@code Criteria}。
     *
     * <p>同 {@code BookRepository.buildCriteria}：用 {@code andOperator} 组合而不是
     * 连续 {@code query.addCriteria(...)} —— 后者在同一字段上叠加条件时会被覆盖。
     *
     * <p>⚠️ {@code categories.categoryId} / {@code categories.subCategoryId} /
     * {@code publishTime} 这些字段<b>没有</b>声明在 {@code Interpretation} 实体里
     * （实体只声明程序要用的字段），但它们是快照文档里真实存在的键，
     * 所以直接按原始路径查是对的。（{@code readingCount} 2026-10-13 已加进实体 —— 列表要展示它。）
     */
    private Criteria buildCriteria(InterpretationQuery q) {
        List<Criteria> conds = new ArrayList<Criteria>();

        String status = q.resolveStatus();
        if (status != null) {
            conds.add(Criteria.where("status").is(status));
        }
        Pattern title = q.titlePattern();
        if (title != null) {
            conds.add(Criteria.where("title").regex(title));
        }
        Pattern author = q.authorPattern();
        if (author != null) {
            conds.add(Criteria.where("author").regex(author));
        }
        if (q.getCategoryId() != null) {
            conds.add(Criteria.where("categories.categoryId").is(q.getCategoryId()));
        }
        // 二级分类（2026-10-13）。与一级**互斥** —— 级联框只会发其中一个（同图书库）
        if (q.getSubCategoryId() != null) {
            conds.add(Criteria.where("categories.subCategoryId").is(q.getSubCategoryId()));
        }
        if (q.getMinRating() != null) {
            conds.add(Criteria.where("newRating").gte(q.getMinRating()));
        }
        if (q.getMaxRating() != null) {
            conds.add(Criteria.where("newRating").lte(q.getMaxRating()));
        }
        if (q.getMinReadingCount() != null) {
            conds.add(Criteria.where("readingCount").gte(q.getMinReadingCount()));
        }
        String minPublish = q.resolveMinPublishTime();
        if (minPublish != null) {
            // 字符串比较：publishTime 是定长零填充的 "yyyy-MM-dd HH:mm:ss"，字典序 == 时间序
            conds.add(Criteria.where("publishTime").gte(minPublish));
        }
        String maxPublish = q.resolveMaxPublishTime();
        if (maxPublish != null) {
            conds.add(Criteria.where("publishTime").lte(maxPublish));
        }

        if (conds.isEmpty()) {
            return new Criteria();
        }
        if (conds.size() == 1) {
            return conds.get(0);
        }
        return new Criteria().andOperator(conds.toArray(new Criteria[0]));
    }

    /**
     * 按 {@code bookId} 批量删除清单条目（2026-10-10 新增）。
     *
     * <h3>🔴 不限状态 —— 已发布到语雀的也能删</h3>
     * 用户 2026-10-10 明确要求。删的是<b>候选清单里的这一行</b>，
     * 不是语雀上的文章（那个得去语雀删），也不影响 {@code publish_record} 里的历史流水。
     *
     * <p>分片执行，避免单次 {@code $in} 过大（同 {@code BookRepository.deleteByBookIds}）。
     *
     * @return 实际删除条数（可能与请求条数不同：有的 bookId 已经不在了）
     */
    public long deleteByBookIds(Collection<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return 0L;
        }
        List<String> all = new ArrayList<String>();
        for (String id : bookIds) {
            if (id != null && !id.trim().isEmpty()) {
                all.add(id.trim());
            }
        }
        if (all.isEmpty()) {
            return 0L;
        }

        long deleted = 0L;
        for (int i = 0; i < all.size(); i += IN_QUERY_CHUNK) {
            List<String> chunk = all.subList(i, Math.min(i + IN_QUERY_CHUNK, all.size()));
            deleted += mongoTemplate.remove(
                    Query.query(Criteria.where("bookId").in(chunk)), Interpretation.class)
                    .getDeletedCount();
        }
        log.info("批量删除候选清单：请求 {} 条，实际删除 {} 条", all.size(), deleted);
        return deleted;
    }

    public long countByStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return 0L;
        }
        return mongoTemplate.count(
                Query.query(Criteria.where("status").is(status.trim().toUpperCase())),
                Interpretation.class);
    }

    public long countAll() {
        return mongoTemplate.count(new Query(), Interpretation.class);
    }

    /**
     * 清单里现有的全部 {@code bookId}。
     *
     * <p>用途：「隐藏已精选」把它拼成 {@code bookId $nin ...}（见
     * {@link #buildCandidateCriteria}）。⚠️ 它<b>不是</b>用来去重的 ——
     * 去重交给 {@code $setOnInsert} + {@code uk_bookId}。
     *
     * <h3>🔴 为什么用 {@code distinct} 而不是 {@code find} + 投影（2026-10-13 改）</h3>
     * 旧实现是 {@code mongoTemplate.find(q, Interpretation.class)} 再逐个取 {@code bookId}：
     * 清单 1.1 万条时，Spring Data 会把 1.1 万个文档**映射成实体对象**（绝大部分字段是 null），
     * 只为了最后扔掉、只留一个字符串。{@code distinct} 在服务端直接出值，
     * 不经过实体映射这一层。
     *
     * <p>⚠️ 顺带把旧的 {@code Sort.by(bookId ASC)} 去掉了 —— {@code $nin} 的语义与顺序无关，
     * 排序纯属白花代价。**不要为了「以后可能要排序」把它加回来。**
     *
     * <p>⚠️ 返回前仍过滤掉 null / 空串：{@code distinct} 会把 {@code bookId} 缺失的
     * 脏数据也算成一个 {@code null} 值，直接塞进 {@code $nin} 没有意义。
     */
    public List<String> listExistingBookIds() {
        List<String> distinct = mongoTemplate.findDistinct(
                new Query(), "bookId", Interpretation.class, String.class);
        List<String> ids = new ArrayList<String>();
        if (distinct != null) {
            for (String id : distinct) {
                if (id != null && !id.trim().isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
