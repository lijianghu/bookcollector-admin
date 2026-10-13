package com.bookcollector.readinglist.repository;

import com.bookcollector.readinglist.entity.ReadingListItem;
import com.bookcollector.readinglist.vo.ReadingListStatusVO;
import com.bookcollector.util.RegexUtil;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 阅读清单的数据访问。
 *
 * <h3>与 {@code shelf.ShelfBookRepository} 是并列的两个叶子</h3>
 * 结构几乎一样（自己的表 + 从 {@code books} 取快照），但<b>互相不 import、不查询对方</b>：
 * 「收藏」与「阅读计划」是两个独立维度，后端层面也不许出现「读 A 顺手改 B」。
 *
 * <h3>两个集合名都是字符串常量，不 import 任何业务模块的实体</h3>
 * {@code reading_list} 是自己的；{@code books} 是<b>别人的</b> —— 加入时要去它那里
 * 取一份快照。只写字符串、不 import {@code book} 模块的实体：阅读清单对图书库是
 * <b>只读投影</b>，一旦 import 就把两个模块耦上了。字段名两边一致（项目零 {@code @Field}），
 * 投影直接按名字取即可。
 */
@Repository
public class ReadingListRepository {

    /** 阅读清单自己的集合 */
    private static final String COLLECTION = "reading_list";

    /** 图书库集合。加入时从这里取快照 */
    private static final String BOOKS_COLLECTION = "books";

    /** 快照字段。与 {@link ReadingListItem} 的业务字段一一对应 */
    private static final String[] SNAPSHOT_FIELDS = {
            "bookId", "title", "author", "cover", "category",
            "newRating", "newRatingCount", "readingCount", "intro"
    };

    private final MongoTemplate mongoTemplate;

    public ReadingListRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // 读
    // ==================================================================

    /**
     * 分页取书。三个筛选条件（状态 / 一级类目 / 书名 / 作者）都可为空 = 不筛。
     *
     * <p>排序 {@code statusUpdatedAt DESC} → {@code _id DESC}：主序「最近操作的先看」，
     * 后一级只为<b>确定性</b> —— 分页的正确性依赖排序是个全序，否则同一时间戳的书
     * 在翻页时会重复 / 漏掉（同 {@code ShelfBookRepository.pageBooks} 的理由）。
     *
     * @param status      阅读状态（枚举名）；{@code null} / 空白 = 不筛
     * @param topCategory 一级类目名，如 {@code "文学"}；{@code null} = 全部
     * @param title       书名关键词，包含匹配（模糊）；{@code null} / 空白 = 不筛
     * @param author      作者关键词，包含匹配（模糊）；{@code null} / 空白 = 不筛
     * @param skip        跳过多少条
     * @param limit       取多少条
     */
    public List<ReadingListItem> pageBooks(String status, String topCategory, String title,
                                           String author, int skip, int limit) {
        Query query = queryOf(status, topCategory, title, author)
                .with(Sort.by(Sort.Direction.DESC, "statusUpdatedAt")
                        .and(Sort.by(Sort.Direction.DESC, "_id")))
                .skip(skip)
                .limit(limit);
        return mongoTemplate.find(query, ReadingListItem.class, COLLECTION);
    }

    /** 与 {@link #pageBooks} 同一条件的总数 */
    public long countBooks(String status, String topCategory, String title, String author) {
        return mongoTemplate.count(queryOf(status, topCategory, title, author),
                ReadingListItem.class, COLLECTION);
    }

    /** 某个状态的条数（给 Tab 角标用）。{@code status} 传枚举名 */
    public long countByStatus(String status) {
        return mongoTemplate.count(
                Query.query(Criteria.where("status").is(status)), ReadingListItem.class, COLLECTION);
    }

    /** 清单总条数 */
    public long countAll() {
        return mongoTemplate.count(new Query(), ReadingListItem.class, COLLECTION);
    }

    /**
     * 把四个条件合成一个 {@code Query}。
     *
     * <p>用 {@code andOperator} 组合，而不是连续 {@code query.addCriteria(...)} ——
     * 后者在同一字段上叠加条件时会被覆盖（同 {@code ShelfBookRepository.queryOf} 的写法）。
     *
     * <p>书名 / 作者走 {@link RegexUtil#contains}：包含匹配 + 大小写不敏感 + 元字符已转义。
     * ⚠️ 未锚定的正则<b>用不上索引</b> = 全集合扫描；阅读清单是用户自己加的书，
     * 规模远小于图书库，代价可接受。
     *
     * <p>四个条件都为空时返回<b>裸 Query</b>，不拼一个空的 {@code $and: []}。
     */
    private Query queryOf(String status, String topCategory, String title, String author) {
        List<Criteria> conds = new ArrayList<Criteria>();
        if (status != null && !status.trim().isEmpty()) {
            conds.add(Criteria.where("status").is(status.trim()));
        }
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
     * <p>前缀匹配 {@code 文学-散文杂著}、也接受不带二级的裸值 {@code 文学}；
     * {@link Pattern#quote} 转义 —— 类目名是用户输入，不转义的话 {@code ".*"} 能查出全表。
     */
    private Pattern topCategoryPattern(String topCategory) {
        return Pattern.compile("^" + Pattern.quote(topCategory) + "(-|$)");
    }

    // ==================================================================
    // 写
    // ==================================================================

    /** 这本书是否已在阅读清单 */
    public boolean exists(String bookId) {
        return mongoTemplate.exists(
                Query.query(Criteria.where("bookId").is(bookId)), COLLECTION);
    }

    /** 写入一条阅读清单记录。唯一索引 {@code uk_bookId} 保证不会重复 */
    public void insert(ReadingListItem item) {
        mongoTemplate.insert(item, COLLECTION);
    }

    /** 移除一条。返回删除条数（0 = 本来就不在） */
    public long remove(String bookId) {
        return mongoTemplate.remove(
                Query.query(Criteria.where("bookId").is(bookId)), COLLECTION).getDeletedCount();
    }

    /**
     * 改阅读状态。
     *
     * @return <b>匹配到的</b>条数（不是「实际修改」条数）——
     *         目标状态与当前相同时 MongoDB 的 modifiedCount 会是 0，
     *         但「这本书现在确实处于目标状态」仍然成立。用 matchedCount
     *         才能把「幂等地设成同一个值」和「这本书根本不在清单里」区分开：
     *         前者 1、后者 0。
     */
    public long updateStatus(String bookId, String status, Date now) {
        Update update = new Update()
                .set("status", status)
                .set("statusUpdatedAt", now);
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("bookId").is(bookId)), update, COLLECTION)
                .getMatchedCount();
    }

    /**
     * 批量查「哪些已在阅读清单、分别什么状态」。
     *
     * @param bookIds 待查的图书 ID（一般是当前页）。空集合直接返回空，不发查询
     */
    public List<ReadingListStatusVO> findStatuses(Collection<String> bookIds) {
        List<ReadingListStatusVO> result = new ArrayList<ReadingListStatusVO>();
        if (bookIds == null || bookIds.isEmpty()) {
            return result;
        }
        Query query = Query.query(Criteria.where("bookId").in(bookIds));
        query.fields().include("bookId").include("status");
        for (ReadingListItem row : mongoTemplate.find(query, ReadingListItem.class, COLLECTION)) {
            if (row.getBookId() != null) {
                result.add(new ReadingListStatusVO(row.getBookId(), row.getStatus()));
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
     * <p>不投影 {@code content} / {@code rawContent} 等正文。取不到（图书已被删）
     * 返回 {@code null}，由 Service 决定怎么报错。
     */
    public ReadingListItem readBookSnapshot(String bookId) {
        Query query = Query.query(Criteria.where("bookId").is(bookId));
        for (String field : SNAPSHOT_FIELDS) {
            query.fields().include(field);
        }
        return mongoTemplate.findOne(query, ReadingListItem.class, BOOKS_COLLECTION);
    }
}
