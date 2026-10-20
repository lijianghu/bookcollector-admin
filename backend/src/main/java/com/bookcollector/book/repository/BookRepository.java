package com.bookcollector.book.repository;

import com.bookcollector.book.dto.UpsertStat;
import com.bookcollector.book.entity.Book;
import com.bookcollector.book.req.BookQuery;
import com.bookcollector.common.PageResult;
import com.bookcollector.util.PageQueryUtil;
import com.bookcollector.util.RegexUtil;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 图书数据访问层（MongoTemplate 封装）。
 *
 * <h3>为什么不用 {@code MongoRepository<Book, String>}</h3>
 * 两个刚需 Spring Data 的 Repository 接口给不了：
 * <ol>
 *   <li><b>批量 upsert</b>。采集一页 20 本、一次任务上千本，逐条 {@code save()}
 *       就是上千次往返。{@code bulkOps} 可以合并成一次网络调用。</li>
 *   <li><b>幂等语义</b>。{@code save()} 是「有 _id 就替换、没有就插入」，
 *       而我们要的是「按 bookId 有则更新、无则插入」—— 这是 upsert，不是 save。</li>
 * </ol>
 *
 * @see com.bookcollector.config.MongoIndexInitializer
 */
@Repository
public class BookRepository {

    private static final Logger log = LoggerFactory.getLogger(BookRepository.class);

    /** {@code $in} 查询一次最多带多少个 bookId，避免 BSON 文档超过 16MB 上限 */
    private static final int IN_QUERY_CHUNK = 1000;

    private final MongoTemplate mongoTemplate;

    public BookRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // 写
    // ==================================================================

    /**
     * 按 {@code bookId} 幂等批量 upsert。
     *
     * <p>这是整个采集链路的<b>唯一写入点</b>。语义：
     * <ul>
     *   <li>{@code bookId} 已存在 → 用新数据覆盖全部业务字段（以接口为真相），
     *       {@code firstCollectedAt} <b>保留原值</b>；{@code intro} 缺失时
     *       <b>连 {@code $set} 都不进</b>（用户 2026-10-15 要求「不要覆盖成空」，
     *       见 {@link #buildUpsertUpdate}）</li>
     *   <li>{@code bookId} 不存在 → 插入，并写入 {@code firstCollectedAt}</li>
     *   <li>{@code collectSource} 用 {@code $addToSet} 累加，同一来源不会重复</li>
     *   <li>{@code bookLists} 同样用 {@code $addToSet} 累加 —— 一本书属于多个书单时全部保留，
     *       且<b>不会</b>因为「后来被别的链路采到」而丢掉已有的书单引用</li>
     * </ul>
     *
     * <p><b>⚠️ 这里为什么不靠 {@code @CreatedDate} / {@code @LastModifiedDate} 审计</b>：
     * {@code bulkOps().upsert(query, Update)} 操作的是 {@code Update} 对象而不是实体，
     * 没有实体就没有字段可让审计框架填。所以这两个时间字段在这里手工写。
     * 详见 {@link com.bookcollector.config.MongoConfig} 的说明。
     *
     * <p><b>幂等性依赖</b>：{@code uk_bookId} 唯一索引。没有它，并发或重试时
     * 同一本书会被插成两条（R22）。
     *
     * @param books     待写入的图书。{@code bookId} 为空/null 的会被跳过
     * @param sourceKey 采集来源，形如 {@code "category:300000"}。为 null 时不累加来源
     * @return 更新数 / 新增数的拆分（{@link UpsertStat#total()} 即「写入/更新」总数）。
     *         两个数都取自 {@code BulkWriteResult}，<b>没有多做任何一次查询</b>
     */
    public UpsertStat upsertMany(List<Book> books, String sourceKey) {
        if (books == null || books.isEmpty()) {
            return UpsertStat.zero();
        }

        Date now = new Date();
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Book.class);

        int queued = 0;
        int skipped = 0;
        for (Book book : books) {
            if (book == null || book.getBookId() == null || book.getBookId().trim().isEmpty()) {
                skipped++;
                continue;
            }
            Query query = Query.query(Criteria.where("bookId").is(book.getBookId().trim()));
            bulk.upsert(query, buildUpsertUpdate(book, now, sourceKey));
            queued++;
        }

        if (skipped > 0) {
            log.warn("upsertMany 跳过 {} 条没有 bookId 的记录", skipped);
        }
        if (queued == 0) {
            return UpsertStat.zero();
        }

        int updated;
        int inserted;
        try {
            com.mongodb.bulk.BulkWriteResult result = bulk.execute();
            // matchedCount 只统计「命中已有文档」的那些；没命中而被插入的算在 upserts 里。
            // 所以 matchedCount 恰好就是「更新数」，upserts.size() 就是「新增数」。
            updated = result.getMatchedCount();
            inserted = result.getUpserts().size();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一索引冲突：说明同一批里出现了重复 bookId，或者有并发写入。
            // 这是数据问题不是程序 bug，抛出去让上层记日志 + 标 FAILED，不要吞。
            log.error("upsertMany 触发唯一索引冲突（bookId 重复）：{}", e.getMessage());
            throw e;
        }
        return new UpsertStat(updated, inserted);
    }

    /**
     * 构造 upsert 的 {@code Update}。
     *
     * <p>用「原始 Document」而不是 {@code Update.set(...)} 链式调用的原因：
     * 需要同时下发 {@code $set} + {@code $setOnInsert} + {@code $addToSet} 三个操作符，
     * 而且 {@code $set} 的内容是「实体全字段」—— 逐个字段手写 50 行
     * 既啰嗦又会在加字段时漏掉。直接用转换器把实体摊平成 Document 最省事。
     */
    private Update buildUpsertUpdate(Book book, Date now, String sourceKey) {
        Document setDoc = new Document();
        mongoTemplate.getConverter().write(book, setDoc);

        // _id 不能出现在 $set 里（MongoDB 会报 "Performing an update on the path '_id' would modify the immutable field '_id'"）
        setDoc.remove("_id");
        // 这四个字段不能用 $set，单独处理：
        //   firstCollectedAt → 只在插入时写，更新时必须保留原值
        //   lastCollectedAt  → 每次都要覆盖
        //   collectSource    → 用 $addToSet 累加，和 $set 冲突
        //   bookLists        → 同上：一本书属于多个书单，跨次累加，不能被 $set 覆盖
        setDoc.remove("firstCollectedAt");
        setDoc.remove("lastCollectedAt");
        setDoc.remove("collectSource");
        setDoc.remove("bookLists");
        setDoc.put("lastCollectedAt", now);

        // 🔴 intro 缺失 = 从 $set 里摘掉，不写库、更不覆盖已有值
        // （2026-10-15 晚用户实测要求：「没有 intro 字段，这个字段没有的话不设置，
        // 可为空；如果已有值，注意不要覆盖成空」）。
        // 书单响应（拍平形态）里 intro 整个字段不存在，BookParser 解析成 null；
        // 转换器会把 null 也写进 Document，于是 $set 带着 intro:null 把库里
        // 已有的简介清成 null。接口没说「这本没有简介」，只是没给这个字段 ——
        // 缺失时不该动库里那份。新插入的文档则没有这个键，读出来就是空简介。
        // ⚠️ 刻意只摘 intro：「以接口为真相」的全量覆盖语义是设计的一部分，
        //    别的字段照旧；将来哪个字段也要同样待遇，加一行即可，别改成通用逻辑。
        if (setDoc.get("intro") == null) {
            setDoc.remove("intro");
        }

        Document updateDoc = new Document("$set", setDoc);
        updateDoc.put("$setOnInsert", new Document("firstCollectedAt", now));

        // $addToSet 的两个字段合并成一次（同一个操作符只能出现一次）
        Document addToSet = new Document();
        if (sourceKey != null && !sourceKey.trim().isEmpty()) {
            addToSet.put("collectSource",
                    new Document("$each", Collections.singletonList(sourceKey.trim())));
        }
        List<Document> bookListRefDocs = toBookListRefDocs(book.getBookLists());
        if (!bookListRefDocs.isEmpty()) {
            addToSet.put("bookLists", new Document("$each", bookListRefDocs));
        }
        if (!addToSet.isEmpty()) {
            updateDoc.put("$addToSet", addToSet);
        }

        return Update.fromDocument(updateDoc);
    }

    /**
     * 「付费精选」专用写入（2026-10-20）：<b>不覆盖已有书籍的字段</b>。
     *
     * <ul>
     *   <li>书<b>不存在</b> → 用解析出的全字段插入，并写 {@code paidCollect=1}；</li>
     *   <li>书<b>已存在</b> → 只 {@code $addToSet collectSource} + {@code $set paidCollect=1}，
     *       其它字段一律不动（含 {@code lastCollectedAt}）。</li>
     * </ul>
     */
    public UpsertStat upsertPaidMany(List<Book> books, String sourceKey) {
        if (books == null || books.isEmpty()) {
            return UpsertStat.zero();
        }
        Date now = new Date();
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Book.class);
        int queued = 0;
        int skipped = 0;
        for (Book book : books) {
            if (book == null || book.getBookId() == null || book.getBookId().trim().isEmpty()) {
                skipped++;
                continue;
            }
            Query query = Query.query(Criteria.where("bookId").is(book.getBookId().trim()));
            bulk.upsert(query, buildPaidUpsertUpdate(book, now, sourceKey));
            queued++;
        }
        if (skipped > 0) {
            log.warn("upsertPaidMany 跳过 {} 条没有 bookId 的记录", skipped);
        }
        if (queued == 0) {
            return UpsertStat.zero();
        }
        com.mongodb.bulk.BulkWriteResult result = bulk.execute();
        return new UpsertStat(result.getMatchedCount(), result.getUpserts().size());
    }

    /**
     * 付费精选的 upsert：{@code $setOnInsert} 写全字段，{@code $set} 只写 paidCollect，
     * {@code $addToSet} 累加 collectSource。
     *
     * <p>⚠️ {@code paidCollect} <b>不能</b>同时出现在 {@code $set} 与 {@code $setOnInsert}
     * （Mongo 会报路径冲突），所以只放在 {@code $set} 里 —— 插入时 {@code $set} 同样生效。
     */
    private Update buildPaidUpsertUpdate(Book book, Date now, String sourceKey) {
        Document insertDoc = new Document();
        mongoTemplate.getConverter().write(book, insertDoc);
        insertDoc.remove("_id");
        insertDoc.remove("collectSource");
        insertDoc.remove("bookLists");
        insertDoc.remove("firstCollectedAt");
        insertDoc.remove("lastCollectedAt");
        insertDoc.remove("paidCollect");
        insertDoc.put("firstCollectedAt", now);
        insertDoc.put("lastCollectedAt", now);

        Document updateDoc = new Document("$setOnInsert", insertDoc);
        updateDoc.put("$set", new Document("paidCollect", 1));
        if (sourceKey != null && !sourceKey.trim().isEmpty()) {
            updateDoc.put("$addToSet", new Document("collectSource", sourceKey.trim()));
        }
        return Update.fromDocument(updateDoc);
    }

    /**
     * {@code Book.bookLists} → {@code $addToSet} 需要的 {@link Document} 列表。
     *
     * <h3>🔴 为什么必须手工转 Document，而不是让转换器去写</h3>
     * <ol>
     *   <li>{@code $addToSet} 是按<b>整个子文档</b>比较去重的，<b>键顺序也参与比较</b>。
     *       所以这里显式按 {@code bookListId} → {@code bookListName} 的顺序 append
     *       （{@code Document} 继承 {@code LinkedHashMap}，顺序保留），
     *       不能指望「转换器碰巧也按这个顺序写」。</li>
     *   <li>Mongo 驱动没有自定义 POJO 的 codec，直接塞 POJO 会在运行期抛
     *       {@code CodecConfigurationException}（同一个坑见 {@code BooklistRepository}）。</li>
     * </ol>
     *
     * <h3>两个兜底</h3>
     * <ul>
     *   <li><b>没有 ID 的元素丢弃</b>：没有 ID 的引用既不能按书单筛书也无法去重。</li>
     *   <li><b>名称为空时用 ID 兜底</b>：{@code BookParser#buildBookListRefs} 已经兜过一次，
     *       这里再兜一次是因为<b>写入侧才是「存进库里长什么样」的权威</b> ——
     *       手工构造的 {@code Book} 也必须落到同一个形状，否则同一个书单会去重失败、
     *       在数组里留下两条（{@code {id, null}} 和 {@code {id, "真名"}}）。</li>
     * </ul>
     */
    private List<Document> toBookListRefDocs(List<Book.BookListRef> refs) {
        List<Document> docs = new ArrayList<Document>();
        if (refs == null || refs.isEmpty()) {
            return docs;
        }
        for (Book.BookListRef ref : refs) {
            if (ref == null || ref.getBookListId() == null
                    || ref.getBookListId().trim().isEmpty()) {
                continue;
            }
            String id = ref.getBookListId().trim();
            String name = ref.getBookListName();
            boolean nameMissing = name == null || name.trim().isEmpty();
            docs.add(new Document("bookListId", id)
                    .append("bookListName", nameMissing ? id : name.trim()));
        }
        return docs;
    }

    // ==================================================================
    // 读
    // ==================================================================

    /** 图书总数 */
    public long count() {
        return mongoTemplate.count(new Query(), Book.class);
    }

    /** 按平台分类统计 */
    public long countByCategory(Integer categoryId) {
        if (categoryId == null) {
            return 0L;
        }
        return mongoTemplate.count(
                Query.query(Criteria.where("categories.categoryId").is(categoryId)), Book.class);
    }

    /** 按采集来源统计，sourceKey 形如 "category:300000" */
    public long countBySource(String sourceKey) {
        if (sourceKey == null || sourceKey.trim().isEmpty()) {
            return 0L;
        }
        return mongoTemplate.count(
                Query.query(Criteria.where("collectSource").is(sourceKey.trim())), Book.class);
    }

    /** 按 bookId 查一本 */
    public Book findByBookId(String bookId) {
        if (bookId == null || bookId.trim().isEmpty()) {
            return null;
        }
        return mongoTemplate.findOne(
                Query.query(Criteria.where("bookId").is(bookId.trim())), Book.class);
    }

    /** 按 bookId 批量查。分片查询，避免单次 $in 过大 */
    public List<Book> findByBookIds(Collection<String> bookIds) {
        List<Book> result = new ArrayList<Book>();
        if (bookIds == null || bookIds.isEmpty()) {
            return result;
        }
        List<String> all = new ArrayList<String>(bookIds);
        for (int i = 0; i < all.size(); i += IN_QUERY_CHUNK) {
            List<String> chunk = all.subList(i, Math.min(i + IN_QUERY_CHUNK, all.size()));
            Query query = Query.query(Criteria.where("bookId").in(chunk));
            result.addAll(mongoTemplate.find(query, Book.class));
        }
        return result;
    }

    /**
     * 分页查询。
     *
     * <p>先 count 再 find（两次往返）。这是第一期能接受的方案 —— 单用户本地跑，
     * 数据量几万级，count 很快。如果将来数据量上到百万，再换成
     * 「只给 hasNext、不给 total」的游标分页。
     *
     * <p>分页样板（count 短路 / skip+limit）统一走 {@link PageQueryUtil}；
     * 本类只负责「条件怎么拼」（{@link #buildCriteria}）和「按什么排」。
     */
    public PageResult<Book> pageQuery(BookQuery q) {
        BookQuery query = q == null ? new BookQuery() : q;

        // 主排序字段与方向都由 BookQuery 决定（字段走白名单校验，见 resolveSortPath()）
        Sort sort = Sort.by(query.isAsc() ? Sort.Direction.ASC : Sort.Direction.DESC,
                query.resolveSortPath());
        // 加个 _id 兜底排序，保证同分记录的分页顺序稳定（否则翻页可能重复/漏）
        sort = sort.and(Sort.by(Sort.Direction.ASC, "_id"));

        return PageQueryUtil.getPageResult(mongoTemplate, query.getSafePage(), query.getSafeSize(),
                buildCriteria(query), Book.class, sort);
    }

    /**
     * 按<b>书单 ID</b> 分页查这本书单里的书（书单详情弹窗用，2026-10-16 新增）。
     *
     * <p>匹配路径 {@code bookLists.bookListId} —— {@link Book#getBookLists()} 是
     * {@code $addToSet} 累加的子文档数组，一本书属于多张书单时每个书单一条引用
     * （同 {@link #upsertMany} 的写入语义）。MongoDB 对 {@code array.field: value}
     * 的语义正是「数组里有任一元素的该字段等于 value」，与本需求精确对应，
     * 不需要 {@code $elemMatch}。
     *
     * <p><b>为什么不再借道 {@code collectSource}</b>：那个字段记的是「本次被谁采到」
     * （形如 {@code booklist:xxx}），而书单引用 {@code bookLists} 才是
     * 「这本书属于哪张书单」的业务事实。语义上以后者为准。
     *
     * <p>排序口径与图书库列表默认一致（{@link BookQuery} 默认 {@code sortField=newRating}）：
     * 评分降序 + {@code _id} 升序兜底，保证翻页不重不漏。
     *
     * <p>{@code booklistId} 空/空白时返回<b>空页</b>而不是抛异常 —— 读取侧放宽口径
     * （同 {@code BookQuery}「非法值筛 0 条」），前端 URL 参数写错也不该变 500。
     */
    public PageResult<Book> pageByBooklistId(String booklistId, Integer page, Integer size) {
        if (booklistId == null || booklistId.trim().isEmpty()) {
            return PageResult.empty(PageQueryUtil.normalizePage(page), PageQueryUtil.normalizeSize(size));
        }
        Sort sort = Sort.by(Sort.Direction.DESC, "newRating")
                .and(Sort.by(Sort.Direction.ASC, "_id"));
        return PageQueryUtil.getPageResult(mongoTemplate,
                PageQueryUtil.normalizePage(page), PageQueryUtil.normalizeSize(size),
                Criteria.where("bookLists.bookListId").is(booklistId.trim()),
                Book.class, sort);
    }

    /**
     * 把 BookQuery 翻译成 Mongo 的 {@code Criteria}。
     *
     * <p>多个条件用 {@code andOperator} 组合，而不是连续 {@code query.addCriteria(...)} ——
     * 后者在同一字段上叠加条件时会被覆盖，前者永远安全。
     */
    private Criteria buildCriteria(BookQuery q) {
        List<Criteria> conds = new ArrayList<Criteria>();

        if (q.getCategoryId() != null) {
            conds.add(Criteria.where("categories.categoryId").is(q.getCategoryId()));
        }
        // 二级分类（2026-10-12 新增）：与上面 categoryId 是 categories[] 里的**两个不同的键**。
        // 前端级联框选一级时只发 categoryId、选到二级时只发 subCategoryId，两者不会同时非空。
        // ⚠️ 别把它并到上面那条里 —— 那样二级 code 会恒不匹配、静默返回 0 条。
        if (q.getSubCategoryId() != null) {
            conds.add(Criteria.where("categories.subCategoryId").is(q.getSubCategoryId()));
        }
        if (q.getMinRating() != null) {
            // ★ 路径是顶层 newRating（推荐值 0~1000）。
            // 这里原来写成 newRatingDetail.newRating —— 那个字段不存在，
            // 导致评分筛选永远返回 0 条且不报错。详见 BookQuery#resolveSortPath() 的说明。
            conds.add(Criteria.where("newRating").gte(q.getMinRating()));
        }
        if (q.getMaxRating() != null) {
            conds.add(Criteria.where("newRating").lte(q.getMaxRating()));
        }
        if (q.getMinReadingCount() != null) {
            conds.add(Criteria.where("readingCount").gte(q.getMinReadingCount()));
        }
        // 评价标题（2026-10-12 新增）：按**档位文案**精确筛，与上面 minRating / maxRating
        // 的**数值**区间是两回事（那两个筛 newRating 0~1000，这个筛 newRatingTitle）。
        String ratingTitle = q.resolveNewRatingTitle();
        if (ratingTitle != null) {
            if (BookQuery.TITLE_NONE.equals(ratingTitle)) {
                // 「暂无评级」。🔴 MongoDB 里 `{field: null}` **同时**匹配「值是 null」
                // 和「整个字段不存在」两种形态，所以这一条就够了，不需要再 or 一个 $exists:false。
                // （同一个 idiom 见 `StatsServiceImpl.countTopCategories` 的 `{parentCode: null}`。
                //   2026-10-12 实测：`{newRatingTitle: null}` 与
                //   `$or:[null, {$exists:false}]` 命中的是**同一批 23678 本**。）
                conds.add(Criteria.where("newRatingTitle").is(null));
            } else {
                conds.add(Criteria.where("newRatingTitle").is(ratingTitle));
            }
        }
        String title = q.resolveTitle();
        if (title != null) {
            // 包含匹配（模糊搜索），正则已转义。见 BookQuery 类注释「2026-10-10 反转」
            conds.add(Criteria.where("title").regex(RegexUtil.contains(title)));
        }
        String author = q.resolveAuthor();
        if (author != null) {
            // ⚠️ 2026-10-10 由精确匹配改为包含匹配（用户要求作者也要模糊搜索）
            conds.add(Criteria.where("author").regex(RegexUtil.contains(author)));
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
        // 采集时间（2026-10-10 新增）：lastCollectedAt 是**真正的 Date**，不是字符串 ——
        // 所以这里传 Date 对象，与上面 publishTime 的字符串字典序比较是两回事。
        // 拿字符串去比 BSON date 会**恒不匹配且不报错**（本项目在 categoryId 上踩过同类坑）。
        // 上限已在 BookQuery 里补到当天 23:59:59.999。
        Date minCollected = q.resolveMinCollectedAt();
        if (minCollected != null) {
            conds.add(Criteria.where("lastCollectedAt").gte(minCollected));
        }
        Date maxCollected = q.resolveMaxCollectedAt();
        if (maxCollected != null) {
            conds.add(Criteria.where("lastCollectedAt").lte(maxCollected));
        }
        String sourceKey = q.resolveSourceKey();
        if (sourceKey != null) {
            conds.add(Criteria.where("collectSource").is(sourceKey));
        }
        // 书单筛选（2026-10-16 新增）：bookLists 是 $addToSet 累加的子文档数组，
        // MongoDB 对 `array.field: value` 的语义即「任一元素命中」，不需要 $elemMatch
        // （同下面 pageByBooklistId 的写法）。
        String booklistId = q.resolveBooklistId();
        if (booklistId != null) {
            conds.add(Criteria.where("bookLists.bookListId").is(booklistId));
        }
        // 字数下限（2026-10-16 新增）：totalWords 是整数，来自 /book/info（补过的书才有）
        if (q.getMinTotalWords() != null) {
            conds.add(Criteria.where("totalWords").gte(q.getMinTotalWords()));
        }
        // 补齐状态（2026-10-16 新增）：enrichedAt 存在 = 被「完善书籍信息」碰过。
        // ⚠️ `{field: null}` 同时匹配「值是 null」与「字段不存在」，所以
        //    is(null) = 从没补过；`$ne: null` = 存在且非 null = 补过。
        if (Boolean.TRUE.equals(q.getEnriched())) {
            conds.add(Criteria.where("enrichedAt").ne(null));
        } else if (Boolean.FALSE.equals(q.getEnriched())) {
            conds.add(Criteria.where("enrichedAt").is(null));
        }
        // 付费精选（2026-10-20）：paidCollect = 1 表示来自付费精选采集。
        // true = 只要付费精选；false = 排除（$ne 1 同时匹配 null / 字段不存在）。
        if (Boolean.TRUE.equals(q.getPaidCollect())) {
            conds.add(Criteria.where("paidCollect").is(1));
        } else if (Boolean.FALSE.equals(q.getPaidCollect())) {
            conds.add(Criteria.where("paidCollect").ne(1));
        }
        // 缺失字段筛选（2026-10-16 新增）：CATEGORY / INTRO / BOTH。
        // 🔴 MongoDB 里 `{field: null}` **同时**匹配「值是 null」和「整个字段不存在」
        // 两种形态（同上面 newRatingTitle 的「暂无评级」，2026-10-12 已实测是同一批文档）。
        // 空串另加一条：cleanValue 入库时会把空串转成 null，但人工编辑可能留下 ""。
        String missing = q.resolveMissing();
        if (missing != null) {
            if ("CATEGORY".equals(missing) || "BOTH".equals(missing)) {
                conds.add(new Criteria().orOperator(
                        Criteria.where("category").is(null),
                        Criteria.where("category").is("")));
            }
            if ("INTRO".equals(missing) || "BOTH".equals(missing)) {
                conds.add(new Criteria().orOperator(
                        Criteria.where("intro").is(null),
                        Criteria.where("intro").is("")));
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
    // 维护
    // ==================================================================

    /**
     * 按 {@code bookId} 局部更新（人工编辑，FR-B5）。
     *
     * <h3>为什么是「局部更新」而不是整本 {@code save()}</h3>
     * 界面上的编辑抽屉只让改十来个字段（书名/作者/简介/分类归属…）。
     * 如果走整本 {@code save()}，前端就得把全部字段一个不落地回传 ——
     * 少传一个，那个字段就被写成 null。这是「隐式清空」，是最难发现的一类数据损坏。
     * 局部更新只碰调用方明确给到的字段。
     *
     * <h3>⚠️ 刻意不动采集元数据</h3>
     * {@code lastCollectedAt} / {@code firstCollectedAt} / {@code collectSource}
     * 一律不改：它们记录的是「采集这件事发生过」，人工编辑不该伪造这个事实。
     * 否则「最近采集时间」会变成一个既不是采集时间也不是编辑时间的东西。
     *
     * @param sets 字段名 → 新值。空 Map 直接返回 false（不产生无意义的写）
     * @return 是否真的改到了文档
     */
    public boolean updateFields(String bookId, Map<String, Object> sets) {
        if (bookId == null || bookId.trim().isEmpty() || sets == null || sets.isEmpty()) {
            return false;
        }
        Update update = new Update();
        for (Map.Entry<String, Object> entry : sets.entrySet()) {
            update.set(entry.getKey(), entry.getValue());
        }
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("bookId").is(bookId.trim())), update, Book.class)
                .getMatchedCount() == 1L;
    }

    /**
     * 按 {@code bookId} 删除一本。
     *
     * <p><b>⚠️ 删了就不会再回来</b>：这本书下次采集时会被重新 upsert 进来
     * （如果它还在榜单/分类里）。所以「删除」的真实语义是
     * 「把这本不想要的书从库里清掉，直到下次采到它为止」，不是永久拉黑。
     * 要永久排除得靠「停用该目标」或改采集范围。
     *
     * @return 删除条数（0 或 1）
     */
    public long deleteByBookId(String bookId) {
        if (bookId == null || bookId.trim().isEmpty()) {
            return 0L;
        }
        return mongoTemplate.remove(
                Query.query(Criteria.where("bookId").is(bookId.trim())), Book.class)
                .getDeletedCount();
    }

    /**
     * 按 {@code bookId} 批量删除（FR-B6）。
     *
     * <p>分片执行，避免单次 {@code $in} 过大（同 {@link #findByBookIds}）。
     * 用 {@code $in} 一次删一批而不是循环单删：200 个勾选框就是 200 次往返，
     * 而 {@code $in} 只要 1 次。
     *
     * @return 实际删除条数
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
            Query query = Query.query(Criteria.where("bookId").in(chunk));
            deleted += mongoTemplate.remove(query, Book.class).getDeletedCount();
        }
        log.info("批量删除图书：请求 {} 条，实际删除 {} 条", all.size(), deleted);
        return deleted;
    }

    /**
     * 清空图书集合。
     *
     * <p>⚠️ <b>只给「重采全量」这个明确场景用</b>，且必须在接口层做二次确认。
     * 采集链路本身<b>不需要</b>清库 —— upsert 天然幂等。
     *
     * @return 删除条数
     */
    public long deleteAll() {
        long before = count();
        mongoTemplate.remove(new Query(), Book.class);
        log.warn("已清空 books 集合，删除 {} 条", before);
        return before;
    }
}
