package com.bookcollector.shelf.repository;

import com.bookcollector.shelf.entity.ShelfBook;
import com.bookcollector.shelf.vo.ShelfCategoryVO;
import com.bookcollector.util.RegexUtil;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperationContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 藏书阁的数据访问。
 *
 * <h3>2026-10-10：从「只读视图」改成「自己的一张表」</h3>
 * 第一版直接投影 {@code book_interpretations}（候选清单）里的书，本类只有两个查询。
 * 现在藏书阁有自己的集合 {@code shelf_books}（见 {@link ShelfBook}），
 * 所以这里同时管<b>读</b>（分页 / 类目）与<b>写</b>（加入 / 移除）。
 *
 * <h3>两个集合名都是字符串常量，不 import 任何业务模块的实体</h3>
 * {@code shelf_books} 是自己的；{@code books} 是<b>别人的</b> —— 加藏书时要去它那里
 * 取一份快照。之所以只写字符串、不 import {@code book} 模块的实体：
 * 藏书阁对图书库是<b>只读投影</b>，一旦 import 就把两个模块耦上了
 * （本项目 {@code shelf} 一直是不依赖任何业务模块的单向叶子）。
 * 字段名两边一致（项目零 {@code @Field}），投影直接按名字取即可。
 */
@Repository
public class ShelfBookRepository {

    /** 藏书阁自己的集合 */
    private static final String COLLECTION = "shelf_books";

    /** 图书库集合。加藏书时从这里取快照 */
    private static final String BOOKS_COLLECTION = "books";

    /** 快照字段。与 {@link ShelfBook} 的业务字段一一对应 */
    private static final String[] SNAPSHOT_FIELDS = {
            "bookId", "title", "author", "cover", "category",
            "newRating", "newRatingCount", "readingCount", "intro"
    };

    private final MongoTemplate mongoTemplate;

    public ShelfBookRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // 读
    // ==================================================================

    /**
     * 一级类目 + 数量，按数量降序。
     *
     * <p>{@code category} 的值形如 {@code "文学-散文杂著"}，一级类目就是第一个
     * {@code "-"} 之前的部分。用聚合算而不是「查回来在 Java 里数」——
     * 数据量大了聚合只回传十几行。
     *
     * <p>⚠️ 先 {@code $match} 掉非字符串 / 空串的 {@code category}：
     * {@code $split} 对非字符串会直接报错，一条脏数据就能让整个分类导航 500。
     */
    public List<ShelfCategoryVO> listTopCategories() {
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("category",
                        new Document("$type", "string").append("$ne", ""))),
                new Document("$project", new Document("top",
                        new Document("$arrayElemAt", Arrays.asList(
                                new Document("$split", Arrays.asList("$category", "-")), 0)))),
                new Document("$group", new Document("_id", "$top")
                        .append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("count", -1))
        );

        List<ShelfCategoryVO> result = new ArrayList<ShelfCategoryVO>();
        for (Document row : aggregate(pipeline)) {
            String name = row.getString("_id");
            if (name == null || name.isEmpty()) {
                continue;
            }
            Object count = row.get("count");
            long n = count instanceof Number ? ((Number) count).longValue() : 0L;
            result.add(new ShelfCategoryVO(name, n));
        }
        return result;
    }

    /**
     * 分页取书。{@code topCategory} 为 {@code null} 表示不按类目筛（取全部）。
     *
     * <p>排序 {@code newRating DESC} → {@code newRatingCount DESC} → {@code _id DESC}。
     * 主序沿用旧版书架（「评分最高的先看」）；后两级是为了<b>确定性</b> ——
     * 分页的正确性依赖排序是个全序，否则同分的书在翻页时会重复 / 漏掉。
     *
     * @param topCategory 一级类目名，如 {@code "文学"}；{@code null} = 全部
     * @param title       书名关键词，包含匹配（模糊）；{@code null} / 空白 = 不筛
     * @param author      作者关键词，包含匹配（模糊）；{@code null} / 空白 = 不筛
     * @param skip        跳过多少条
     * @param limit       取多少条
     */
    public List<ShelfBook> pageBooks(String topCategory, String title, String author,
                                     int skip, int limit) {
        Query query = queryOf(topCategory, title, author)
                .with(Sort.by(Sort.Direction.DESC, "newRating")
                        .and(Sort.by(Sort.Direction.DESC, "newRatingCount"))
                        .and(Sort.by(Sort.Direction.DESC, "_id")))
                .skip(skip)
                .limit(limit);
        return mongoTemplate.find(query, ShelfBook.class, COLLECTION);
    }

    /** 与 {@link #pageBooks} 同一条件的总数 */
    public long countBooks(String topCategory, String title, String author) {
        return mongoTemplate.count(queryOf(topCategory, title, author), ShelfBook.class, COLLECTION);
    }

    /**
     * 把三个条件合成一个 {@code Query}：类目（前缀正则）+ 书名（包含）+ 作者（包含）。
     *
     * <p>用 {@code andOperator} 组合，而不是连续 {@code query.addCriteria(...)} ——
     * 后者在同一字段上叠加条件时会被覆盖（同 {@code BookRepository.buildCriteria} 的写法）。
     *
     * <p>书名 / 作者走 {@link RegexUtil#contains}：**包含匹配 + 大小写不敏感**，
     * 关键词里的正则元字符已被 {@code Pattern.quote} 转义。与图书库 / 候选清单
     * 共用同一份转义逻辑 —— 用户输入 {@code "("} 时若没转义，正则编译会抛异常（500），
     * 而不是「查不到」，那是更难排查的一类问题。
     *
     * <p>⚠️ 未锚定的正则<b>用不上索引</b> = 全集合扫描。这是用户 2026-10-12 明确要的，
     * 藏书阁只有几千条，代价可接受；<b>不要再往其他字段上扩散</b>。
     *
     * <p>三个条件都为空时返回**裸 Query**，不拼一个空的 {@code $and: []}。
     */
    private Query queryOf(String topCategory, String title, String author) {
        List<Criteria> conds = new ArrayList<Criteria>();
        if (topCategory != null && !topCategory.isEmpty()) {
            conds.add(Criteria.where("category").regex(topCategoryPattern(topCategory)));
        }
        Pattern titlePattern = RegexUtil.contains(title);
        if (titlePattern != null) {
            conds.add(Criteria.where("title").regex(titlePattern));
        }
        Pattern authorPattern = RegexUtil.contains(author);
        if (authorPattern != null) {
            conds.add(Criteria.where("author").regex(authorPattern));
        }
        if (conds.isEmpty()) {
            return new Query();
        }
        return new Query(new Criteria().andOperator(conds.toArray(new Criteria[0])));
    }

    /**
     * 「一级类目」的匹配正则：{@code ^\Q文学\E(-|$)}。
     *
     * <p>三件事：
     * <ol>
     *   <li><b>前缀匹配</b> {@code 文学-散文杂著} —— 类目名字段是「一级-二级」拼起来的；</li>
     *   <li>也接受<b>不带二级</b>的裸值 {@code 文学}；</li>
     *   <li>{@link Pattern#quote} 转义 —— 类目名是<b>用户输入</b>，
     *       不转义的话 {@code ".*"} 能查出全表，这是注入面。</li>
     * </ol>
     */
    private Pattern topCategoryPattern(String topCategory) {
        return Pattern.compile("^" + Pattern.quote(topCategory) + "(-|$)");
    }

    // ==================================================================
    // 写
    // ==================================================================

    /** 这本书是否已在藏书阁 */
    public boolean exists(String bookId) {
        return mongoTemplate.exists(
                Query.query(Criteria.where("bookId").is(bookId)), COLLECTION);
    }

    /** 写入一条藏书。唯一索引 {@code uk_bookId} 保证不会重复 */
    public void insert(ShelfBook book) {
        mongoTemplate.insert(book, COLLECTION);
    }

    /** 移除一本藏书。返回删除条数（0 = 本来就不在） */
    public long remove(String bookId) {
        return mongoTemplate.remove(
                Query.query(Criteria.where("bookId").is(bookId)), COLLECTION).getDeletedCount();
    }

    /**
     * 批量查「哪些已在藏书阁」。图书库列表用它决定每行显示「添加藏书」还是「移除藏书」。
     *
     * @param bookIds 待查的图书 ID（一般是当前页）。空集合直接返回空，不发查询
     */
    public Set<String> findOnShelf(Collection<String> bookIds) {
        Set<String> result = new HashSet<String>();
        if (bookIds == null || bookIds.isEmpty()) {
            return result;
        }
        Query query = Query.query(Criteria.where("bookId").in(bookIds));
        query.fields().include("bookId");
        for (ShelfBook row : mongoTemplate.find(query, ShelfBook.class, COLLECTION)) {
            if (row.getBookId() != null) {
                result.add(row.getBookId());
            }
        }
        return result;
    }

    // ==================================================================
    // 从图书库取快照
    // ==================================================================

    /**
     * 从 {@code books} 里按 bookId 取一份展示快照（9 个字段）。
     *
     * <p>不投影 {@code content} / {@code rawContent} 等正文 —— 藏书阁用不上，
     * 白读几 KB。取不到（图书已被删）返回 {@code null}，由 Service 决定怎么报错。
     */
    public ShelfBook readBookSnapshot(String bookId) {
        Query query = Query.query(Criteria.where("bookId").is(bookId));
        for (String field : SNAPSHOT_FIELDS) {
            query.fields().include(field);
        }
        return mongoTemplate.findOne(query, ShelfBook.class, BOOKS_COLLECTION);
    }

    // ==================================================================
    // 聚合辅助
    // ==================================================================

    /** 执行管道。输出用 {@code Document} —— 结果是「聚合出来的形状」，没有对应实体 */
    private List<Document> aggregate(List<Document> pipeline) {
        AggregationOperation[] ops = new AggregationOperation[pipeline.size()];
        for (int i = 0; i < pipeline.size(); i++) {
            ops[i] = raw(pipeline.get(i));
        }
        return mongoTemplate.aggregate(Aggregation.newAggregation(ops), COLLECTION, Document.class)
                .getMappedResults();
    }

    /**
     * 把原生 {@code Document} 包成 {@code AggregationOperation}。
     *
     * <p>直接返回原文档，不走 Spring Data 的字段名映射 —— 我们的字段名就是 Mongo 里的
     * 字段名（项目零 {@code @Field}），映射只会多一层可能出错的转换。
     */
    private AggregationOperation raw(final Document doc) {
        return new AggregationOperation() {
            @Override
            public Document toDocument(AggregationOperationContext context) {
                return doc;
            }
        };
    }
}
