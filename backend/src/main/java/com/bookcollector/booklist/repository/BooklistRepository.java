package com.bookcollector.booklist.repository;

import com.bookcollector.book.dto.UpsertStat;
import com.bookcollector.booklist.entity.Booklist;
import com.bookcollector.booklist.entity.BooklistCollectStatus;
import com.bookcollector.booklist.req.BooklistQuery;
import com.bookcollector.booklist.vo.BooklistOptionVO;
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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 书单数据访问层（MongoTemplate 封装）。
 *
 * <h3>为什么不用 {@code MongoRepository<Booklist, String>}</h3>
 * 同 {@code BookRepository} 的两个理由，这里一条不少：
 * <ol>
 *   <li><b>批量 upsert</b>。一次同步要写几百张书单，逐条 {@code save()} 就是几百次往返。</li>
 *   <li><b>幂等语义</b>。要的是「按 {@code booklistId} 有则更新、无则插入」（upsert），
 *       而不是 {@code save()} 的「有 _id 就整份替换」。</li>
 * </ol>
 *
 * <h3>🔴 写入语义：只覆盖「接口给的」字段</h3>
 * 书单表有<b>两个写入者</b>，各写各的字段：
 * <table border="1">
 *   <tr><th>写入者</th><th>负责字段</th></tr>
 *   <tr><td>链路 A（发现书单，本类）</td>
 *       <td>{@code name} / {@code description} / {@code author*} / 各计数 / 各时间 /
 *           {@code platformCategories} / {@code lastSeenAt}</td></tr>
 *   <tr><td>链路 B（采书，Service 层）</td>
 *       <td>{@code collectStatus} / {@code bookCountCollected} / {@code lastCollectedAt}</td></tr>
 * </table>
 * 所以本类的 upsert <b>绝不能</b>把整份实体摊平进 {@code $set} ——
 * 那样每次同步书单都会把「已采」状态冲回 {@code PENDING}，表现为
 * 「同步一下，所有书单都变成待采」（而书其实已经采过了）。
 *
 * <p>做法：<b>靠「谁没赋值谁就不进 {@code $set}」</b>。
 * {@code MappingMongoConverter} <b>不写 null 字段</b>，所以链路 A 构造实体时
 * 不碰 {@code collectStatus} / {@code bookCountCollected} / {@code lastCollectedAt}，
 * 它们自然就不会出现在 {@code $set} 里。这条「靠 null 不上屏」的约定很隐晦，
 * 所以 {@code BooklistParser} 里<b>必须</b>保持「不设置这些字段」——
 * 加字段时也要照这条办。
 *
 * <p>⚠️ 唯一需要手工从 {@code $set} 里摘出来的是 {@code platformCategories}：
 * 它同时出现在 {@code $set}（实体摊平）和 {@code $addToSet}（累加）里，
 * MongoDB 会直接报
 * 「Updating the path 'platformCategories' would create a conflict at ...」。
 * 这跟 {@code BookRepository.buildUpsertUpdate} 摘 {@code collectSource} 是同一回事。
 *
 * @see com.bookcollector.config.MongoIndexInitializer#initBooklists()
 */
@Repository
public class BooklistRepository {

    private static final Logger log = LoggerFactory.getLogger(BooklistRepository.class);

    /** 本模块自己的集合 */
    private static final String COLLECTION = "booklists";

    private final MongoTemplate mongoTemplate;

    public BooklistRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // 写
    // ==================================================================

    /**
     * 按 {@code booklistId} 幂等批量 upsert（链路 A 的唯一写入点）。
     *
     * <p>三件同时下发的事：
     * <ul>
     *   <li>{@code $set} —— 接口给的字段全量覆盖（以接口为真相）</li>
     *   <li>{@code $setOnInsert} —— {@code firstSeenAt}（只写一次）+ 初始采书状态
     *       {@code PENDING} + {@code bookCountCollected = 0}</li>
     *   <li>{@code $addToSet} —— {@code platformCategories} 累加，同一分类不重复</li>
     * </ul>
     *
     * <p><b>为什么采书状态用 {@code $setOnInsert} 而不是 {@code $set}</b>：
     * 更新已有文档时<b>必须保留</b>它原来的状态。用 {@code $set} 的话，
     * 每同步一次就把 {@code COLLECTED} 打回 {@code PENDING}（见类注释）。
     * 用 {@code $setOnInsert} 则「只在新建时给初值，之后谁写谁负责」。
     *
     * @param booklists      待写入的书单。{@code booklistId} 为空/null 的会被跳过
     * @param sourceCategory 本次请求所属的平台分类（{@code categoryId} + {@code title}）。
     *                       为 {@code null} 时不累加分类
     * @return 更新数 / 新增数的拆分（{@link UpsertStat#total()} 即「写入/更新」总数）。
     *         ⚠️ 这里的「本」是<b>张书单</b>
     */
    public UpsertStat upsertMany(List<Booklist> booklists, Booklist.PlatformCategory sourceCategory) {
        if (booklists == null || booklists.isEmpty()) {
            return UpsertStat.zero();
        }

        Date now = new Date();
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Booklist.class);

        int queued = 0;
        int skipped = 0;
        for (Booklist booklist : booklists) {
            if (booklist == null || isBlank(booklist.getBooklistId())) {
                skipped++;
                continue;
            }
            Query query = Query.query(
                    Criteria.where("booklistId").is(booklist.getBooklistId().trim()));
            bulk.upsert(query, buildUpsertUpdate(booklist, now, sourceCategory));
            queued++;
        }

        if (skipped > 0) {
            log.warn("booklist upsertMany 跳过 {} 条没有 booklistId 的记录", skipped);
        }
        if (queued == 0) {
            return UpsertStat.zero();
        }

        int updated;
        int inserted;
        try {
            com.mongodb.bulk.BulkWriteResult result = bulk.execute();
            updated = result.getMatchedCount();
            inserted = result.getUpserts().size();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一索引冲突：同一批里出现重复 booklistId，或有并发写入。
            // 数据问题不是程序 bug —— 抛出去让上层记日志 + 标失败，不要吞。
            log.error("booklist upsertMany 触发唯一索引冲突（booklistId 重复）：{}", e.getMessage());
            throw e;
        }
        return new UpsertStat(updated, inserted);
    }

    /**
     * 构造 upsert 的 {@code Update}。细节见类注释。
     */
    private Update buildUpsertUpdate(Booklist booklist, Date now,
                                     Booklist.PlatformCategory sourceCategory) {
        Document setDoc = new Document();
        mongoTemplate.getConverter().write(booklist, setDoc);

        // _id 不能出现在 $set 里（MongoDB 会报不可变字段）
        setDoc.remove("_id");
        // lastSeenAt 由本方法统一下发，不信调用方传进来的值
        setDoc.remove("lastSeenAt");
        setDoc.put("lastSeenAt", now);
        // platformCategories 改走 $addToSet，必须从 $set 里摘掉（否则路径冲突报错）
        setDoc.remove("platformCategories");

        Document updateDoc = new Document("$set", setDoc);

        Document onInsert = new Document("firstSeenAt", now);
        // 只在新建时给采书状态的初值 —— 更新时保留原状态（见 upsertMany 的说明）
        onInsert.put("collectStatus", BooklistCollectStatus.DEFAULT.name());
        onInsert.put("bookCountCollected", 0);
        updateDoc.put("$setOnInsert", onInsert);

        if (sourceCategory != null && !isBlank(sourceCategory.getCategoryId())) {
            updateDoc.put("$addToSet", new Document("platformCategories",
                    new Document("$each", Collections.singletonList(toDocument(sourceCategory)))));
        }

        return Update.fromDocument(updateDoc);
    }

    /**
     * {@code PlatformCategory} → 原始 {@code Document}。
     *
     * <h3>🔴 为什么必须手工转，不能直接把 POJO 塞进 {@code Document}</h3>
     * {@code Update.fromDocument(...)} 里的内容最终由 <b>Mongo 驱动</b>编码，
     * 而驱动的 {@code DocumentCodec} 只认 BSON 标准类型 —— 遇到项目自定义的 POJO
     * 会抛 {@code CodecConfigurationException: Can't find a codec for class ...}。
     * 这是<b>运行期</b>才炸的，编译期一点提示都没有。
     * （{@code BookRepository} 那边的 {@code $addToSet} 塞的是 {@code String}，
     * 本来就在标准类型里，所以没这个问题。）
     *
     * <h3>⚠️ 两个键<b>永远都写</b>，顺序固定</h3>
     * {@code $addToSet} 是按「整个子文档是否完全相同」去重的，键的顺序也参与比较。
     * 所以这里固定写 {@code categoryId} → {@code title} 两个键（{@code title} 为 null 也写），
     * 保证同一分类重复累加时能真正去重。
     * <p>代价：平台把分类改名后，数组里会<b>同时留着新旧两个条目</b>
     * （旧的那个不会自动消失）。分类名是平台侧维护的、基本不变，
     * 所以不做「先 $pull 再 $addToSet」那套；真出现了再处理。
     */
    private Document toDocument(Booklist.PlatformCategory category) {
        Document doc = new Document();
        doc.put("categoryId", category.getCategoryId());
        doc.put("title", category.getTitle());
        return doc;
    }

    /**
     * 只更新 {@code name} —— 书单名称<b>回填</b>（链路 B 启动任务前的动作）。
     *
     * <h3>为什么单开一个方法，不复用 {@link #upsertMany}</h3>
     * 回填时手里只有「书单 ID + 名称」这一条信息，没有描述 / 作者 / 各计数。
     * 走 {@code upsertMany} 会把实体摊平进 {@code $set}，于是那些字段被一起
     * 覆盖成 {@code null}（{@code MappingMongoConverter} 不写 null，所以是「不写」——
     * 但一旦哪天有人给实体补了默认值，就会变成真覆盖）。而且 {@code upsertMany}
     * 还要一个平台分类参数，回填场景根本给不出来。
     *
     * <p>所以这里<b>只碰一个字段</b>，与 {@code BookRepository.updateFields} 同一个思路：
     * 局部更新只改调用方明确给到的字段，绝不隐式清空别的字段。
     *
     * @param booklistId 书单 ID
     * @param name       新名称。空白串视为「没回填成功」，直接返回 false
     * @return 是否真的改到了文档
     */
    public boolean updateName(String booklistId, String name) {
        if (isBlank(booklistId) || isBlank(name)) {
            return false;
        }
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("booklistId").is(booklistId.trim())),
                new Update().set("name", name.trim()),
                Booklist.class, COLLECTION).getMatchedCount() == 1L;
    }

    /**
     * 回填书单元数据（2026-10-20）：名称 / 简介 / 作者 / 计数 / 时间。
     *
     * <p>只写解析出来<b>非空</b>的字段，绝不隐式清空；不碰 {@code collectStatus} /
     * {@code bookCountCollected} / {@code platformCategories} / 首末次发现时间。
     *
     * @return 是否真的改到了文档
     */
    public boolean updateMetadata(String booklistId, Booklist parsed) {
        if (isBlank(booklistId) || parsed == null) {
            return false;
        }
        Update u = new Update();
        boolean has = false;
        if (!isBlank(parsed.getName())) {
            u.set("name", parsed.getName().trim());
            has = true;
        }
        if (!isBlank(parsed.getDescription())) {
            u.set("description", parsed.getDescription().trim());
            has = true;
        }
        if (parsed.getUserVid() != null) {
            u.set("userVid", parsed.getUserVid());
            has = true;
        }
        if (!isBlank(parsed.getAuthorName())) {
            u.set("authorName", parsed.getAuthorName().trim());
            has = true;
        }
        if (!isBlank(parsed.getAuthorAvatar())) {
            u.set("authorAvatar", parsed.getAuthorAvatar().trim());
            has = true;
        }
        if (parsed.getBookCount() != null) {
            u.set("bookCount", parsed.getBookCount());
            has = true;
        }
        if (parsed.getCollectCount() != null) {
            u.set("collectCount", parsed.getCollectCount());
            has = true;
        }
        if (parsed.getLikedCount() != null) {
            u.set("likedCount", parsed.getLikedCount());
            has = true;
        }
        if (parsed.getShareCount() != null) {
            u.set("shareCount", parsed.getShareCount());
            has = true;
        }
        if (parsed.getCreateTime() != null) {
            u.set("createTime", parsed.getCreateTime());
            has = true;
        }
        if (parsed.getUpdateTime() != null) {
            u.set("updateTime", parsed.getUpdateTime());
            has = true;
        }
        if (parsed.getLastReadTime() != null) {
            u.set("lastReadTime", parsed.getLastReadTime());
            has = true;
        }
        if (!has) {
            return false;
        }
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("booklistId").is(booklistId.trim())),
                u, Booklist.class, COLLECTION).getMatchedCount() == 1L;
    }

    // ==================================================================
    // 读
    // ==================================================================

    /** 书单总数 */
    public long count() {
        return mongoTemplate.count(new Query(), Booklist.class, COLLECTION);
    }

    /** 新增书单（手工「新增书单」用）。重复由 Service 层先查重，这里不做 upsert */
    public Booklist save(Booklist booklist) {
        return mongoTemplate.save(booklist, COLLECTION);
    }

    /**
     * 全部书单的 ID（2026-10-20 一键完善用）。
     *
     * <p>只投影 {@code booklistId}，避免把简介、平台分类等大字段全拉回来。
     * 用「全部」而不是「只缺的」：用户要求已有数据也要覆盖更新。
     */
    public List<Booklist> findAllIds() {
        Query q = new Query();
        q.fields().include("booklistId");
        return mongoTemplate.find(q, Booklist.class, COLLECTION);
    }

    /**
     * 全量书单的「ID + 名称」清单，供下拉框用（2026-10-16）。
     *
     * <p>只投影 {@code booklistId} 与 {@code name} 两个字段 —— 书单文档很大
     * （简介、作者头像、平台分类、各种计数…），228 条全字段拉回来能到 1MB 级，
     * 只为了填一个下拉不值。
     *
     * <p>按名称升序排；名称为 null 的（还没回填）会排在最前，前端用 ID 兜底显示。
     */
    public List<BooklistOptionVO> findOptions() {
        Query query = new Query();
        query.fields().include("booklistId").include("name");
        query.with(Sort.by(Sort.Direction.ASC, "name"));

        List<Booklist> list = mongoTemplate.find(query, Booklist.class, COLLECTION);
        List<BooklistOptionVO> options = new ArrayList<BooklistOptionVO>(list.size());
        for (Booklist b : list) {
            options.add(BooklistOptionVO.builder()
                    .booklistId(b.getBooklistId())
                    .name(b.getName())
                    .build());
        }
        return options;
    }

    /** 按 {@code booklistId} 查一张。{@code targetId} 就是它 */
    public Booklist findByBooklistId(String booklistId) {
        if (isBlank(booklistId)) {
            return null;
        }
        return mongoTemplate.findOne(
                Query.query(Criteria.where("booklistId").is(booklistId.trim())),
                Booklist.class, COLLECTION);
    }

    /**
     * 分页查询（书单配置页的列表）。
     *
     * <p>先 count 再 find（两次往返）—— 同 {@code BookRepository#pageQuery}，
     * 单机、书单量级几千，count 很快。分页样板统一走 {@link PageQueryUtil}，
     * 本类只负责「条件怎么拼」（{@link #buildCriteria}）与「按什么排」。
     *
     * <p>⚠️ 这里<b>必须</b>显式传 {@code Booklist.class}（不能只传集合名）：
     * {@link PageQueryUtil} 内部用 {@code mongoTemplate.count(query, clazz)}，
     * 靠 {@code @Document(collection = "booklists")} 解析集合。
     */
    public PageResult<Booklist> pageQuery(BooklistQuery q) {
        BooklistQuery query = q == null ? new BooklistQuery() : q;

        // 主排序字段与方向都由 BooklistQuery 决定（字段走白名单，见 resolveSortPath()）
        Sort sort = Sort.by(query.isAsc() ? Sort.Direction.ASC : Sort.Direction.DESC,
                query.resolveSortPath());
        // 加个 _id 兜底排序，保证同值记录的分页顺序稳定（否则翻页可能重复/漏）
        sort = sort.and(Sort.by(Sort.Direction.ASC, "_id"));

        return PageQueryUtil.getPageResult(mongoTemplate, query.getSafePage(), query.getSafeSize(),
                buildCriteria(query), Booklist.class, sort);
    }

    /**
     * 把 {@link BooklistQuery} 翻译成 Mongo 的 {@code Criteria}。
     *
     * <p>多个条件用 {@code andOperator} 组合，而不是连续 {@code query.addCriteria(...)} ——
     * 后者在同一字段上叠加条件时会被覆盖，前者永远安全。
     */
    private Criteria buildCriteria(BooklistQuery q) {
        List<Criteria> conds = new ArrayList<Criteria>();

        String categoryId = q.resolveCategoryId();
        if (categoryId != null) {
            // ★ 路径是 platformCategories[].categoryId，值是**字符串**。
            //   拿它去和 books.categories[].categoryId（int）比会恒不匹配且不报错 ——
            //   本项目在「字符串 vs int」上踩过这个坑，见 BooklistQuery 的类注释。
            conds.add(Criteria.where("platformCategories.categoryId").is(categoryId));
        }

        String collectStatus = q.resolveCollectStatus();
        if (collectStatus != null) {
            conds.add(Criteria.where("collectStatus").is(collectStatus));
        }

        if (q.getMinCollectCount() != null) {
            conds.add(Criteria.where("collectCount").gte(q.getMinCollectCount()));
        }
        if (q.getMaxCollectCount() != null) {
            conds.add(Criteria.where("collectCount").lte(q.getMaxCollectCount()));
        }
        if (q.getMinBookCount() != null) {
            conds.add(Criteria.where("bookCount").gte(q.getMinBookCount()));
        }
        if (q.getMaxBookCount() != null) {
            conds.add(Criteria.where("bookCount").lte(q.getMaxBookCount()));
        }

        // 「只看待回填名称」。⚠️ `{name: null}` 在 MongoDB 里**同时**匹配
        // 「值是 null」和「整个字段不存在」两种形态，但匹配不到空串 ——
        // 而链路 A 的解析器会把接口给的 `""` 原样存下来，所以空串要单独 OR 一条。
        // （同 BookRepository 里 `{newRatingTitle: null}` 的 idiom，区别只在于这里多一个空串。）
        if (q.isNameMissing()) {
            conds.add(new Criteria().orOperator(
                    Criteria.where("name").is(null),
                    Criteria.where("name").is("")));
        }

        String keyword = q.resolveKeyword();
        if (keyword != null) {
            // 包含匹配（模糊搜索），正则已转义（见 RegexUtil 的性能口径）。
            // 名称 **或** ID 命中即算命中 —— 理由见 BooklistQuery#keyword 的注释。
            Pattern pattern = RegexUtil.contains(keyword);
            conds.add(new Criteria().orOperator(
                    Criteria.where("name").regex(pattern),
                    Criteria.where("booklistId").regex(pattern)));
        }

        if (conds.isEmpty()) {
            return new Criteria();
        }
        if (conds.size() == 1) {
            return conds.get(0);
        }
        return new Criteria().andOperator(conds.toArray(new Criteria[0]));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
