package com.bookcollector.publish.repository;

import com.bookcollector.common.PageResult;
import com.bookcollector.publish.entity.Interpretation;
import com.bookcollector.publish.entity.PublishStatus;
import com.bookcollector.util.PageQueryUtil;

import com.mongodb.bulk.BulkWriteResult;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * 挑书时先捞的 {@code _id} 池大小（只投影 {@code _id}），见 {@link #pickOnePending()}。
     * 池子越大越随机、查询越贵；100 条是纯索引扫描，开销可忽略。
     */
    private static final int PICK_POOL_SIZE = 100;

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
     * @return 清单为空时返回 {@code null}，由 Service 给出「请先初始化候选」的提示
     */
    public Interpretation pickOnePending() {
        Query q = Query.query(Criteria.where("status").is(PublishStatus.PENDING.name()))
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
        Update u = new Update()
                .set("status", PublishStatus.PENDING.name())
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
     * @param status {@code null} / 空 = 全部；否则按状态精确匹配（大小写不敏感）
     */
    public PageResult<Interpretation> page(String status, Integer page, Integer size) {
        Criteria criteria = (status == null || status.trim().isEmpty())
                ? new Criteria()
                : Criteria.where("status").is(status.trim().toUpperCase());
        Sort sort = Sort.by(Sort.Direction.DESC, "newRatingCount")
                .and(Sort.by(Sort.Direction.ASC, "_id"));
        return PageQueryUtil.getPageResult(mongoTemplate, page, size,
                criteria, Interpretation.class, sort);
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
     * <p>⚠️ 当前实现<b>不用</b>它做去重（去重交给 {@code $setOnInsert} + {@code uk_bookId}），
     * 留着是给「初始化前的预检」这类场景用的。只取 {@code bookId} 一个字段，
     * 几千条也只有几十 KB。
     */
    public List<String> listExistingBookIds() {
        Query q = new Query().with(Sort.by(Sort.Direction.ASC, "bookId"));
        q.fields().include("bookId");
        List<Interpretation> list = mongoTemplate.find(q, Interpretation.class);
        List<String> ids = new ArrayList<String>();
        for (Interpretation it : list) {
            if (it.getBookId() != null) {
                ids.add(it.getBookId());
            }
        }
        return ids;
    }
}
