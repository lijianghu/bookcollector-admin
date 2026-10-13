package com.bookcollector.stats.service.impl;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.book.entity.Book;
import com.bookcollector.common.PageResult;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.cursor.entity.CollectCursor;
import com.bookcollector.stats.service.StatsService;
import com.bookcollector.task.TaskRegistry;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.taxonomy.repository.TaxonomyRepository;
import com.bookcollector.taxonomy.entity.TaxonomyItem;
import com.bookcollector.util.PageQueryUtil;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperationContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link StatsService} 的实现。
 *
 * <h3>为什么直接写 {@code Document} 而不是用 {@code Aggregation.group(...)} 链式 API</h3>
 * 本类用到的 {@code $dateToString}、{@code $split}、{@code $arrayElemAt}、{@code $cond}
 * 这类「表达式」，用 Spring Data 的类型化 API 写出来比原生管道<b>更长、更难读</b>，
 * 而且字段名要靠字符串拼（该错还是会错）。直接用 {@code Document} 写管道，
 * 复制到 {@code mongosh} 里就能单独调试 —— 这个可验证性值很高。
 *
 * <p>统计口径（为什么用聚合管道、「今日采集数」按 {@code firstCollectedAt} 数净新增）
 * 见 {@link StatsService} 的类注释。4 张图各自的口径见下方私有方法注释。
 */
@Service
public class StatsServiceImpl implements StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsServiceImpl.class);

    /** 全链路时区（对齐 2.5 非功能需求） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 近 N 天 */
    private static final int RECENT_DAYS = 7;

    /** 评分分桶：每 100 分一个桶，0~1000 共 10 桶 */
    private static final int RATING_BUCKET_SIZE = 100;

    private static final int RATING_BUCKET_COUNT = 10;

    /** 分类分布最多返回多少个扇区。饼图超过 30 片就没法看了 */
    private static final int DISTRIBUTION_LIMIT = 30;

    private final MongoTemplate mongoTemplate;
    private final TaxonomyRepository taxonomyRepository;
    private final TaskRegistry taskRegistry;

    public StatsServiceImpl(MongoTemplate mongoTemplate,
                            TaxonomyRepository taxonomyRepository,
                            TaskRegistry taskRegistry) {
        this.mongoTemplate = mongoTemplate;
        this.taxonomyRepository = taxonomyRepository;
        this.taskRegistry = taskRegistry;
    }

    // ==================================================================
    // FR-F1：4 个指标卡
    // ==================================================================

    /**
     * 指标卡。字段口径见 {@link StatsService#overview()}。
     */
    @Override
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();

        // --- 4 个主指标 ---
        data.put("bookTotal", mongoTemplate.count(new Query(), Book.class));
        // 分类数只算「一级分类」。库里现在还有 173 个二级分类，全算进来这个卡片会从
        // 22 跳到 195；而仪表盘要回答的是「我采了哪几大类」，二级只是采集目标的细分粒度。
        // 二级清单去「分类配置」页看。
        // （口径：库里 CATEGORY 共 195 条 = 一级 22 + 二级 173。`SeedRunner` 的字典口径是
        //  22 + 166 = 188，库里多出的 7 条是历史遗留的二级分类。）
        data.put("categoryTotal", countTopCategories(false));
        data.put("taskTotal", mongoTemplate.count(new Query(), CollectTask.class));
        data.put("todayCollected", countCollectedSince(startOfToday()));

        // --- 上下文 ---
        data.put("categoryEnabled", countTopCategories(true));
        data.put("rankingTotal", taxonomyRepository.countByType(TargetType.RANKING));
        data.put("rankingEnabled", taxonomyRepository.countByTypeAndEnabled(TargetType.RANKING, true));
        data.put("taskRunning", countTasksByStatus(true));
        data.put("activeRuns", taskRegistry.activeCount());
        data.put("cursorTotal", mongoTemplate.count(new Query(), CollectCursor.class));
        data.put("requestTotal", mongoTemplate.count(new Query(), ApiRequest.class));
        data.put("last7DaysCollected", countCollectedSince(startOfDaysAgo(RECENT_DAYS - 1)));
        data.put("lastCollectedAt", maxLastCollectedAt());
        return data;
    }

    // ==================================================================
    // FR-F2：4 张图
    // ==================================================================

    @Override
    public Map<String, Object> charts() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("categoryDistribution", categoryDistribution());
        data.put("ratingDistribution", ratingDistribution());
        data.put("publishYearTrend", publishYearTrend());
        data.put("recentCollected", recentCollected());
        return data;
    }

    // ==================================================================
    // 「今日新增」明细列表（点指标卡弹出的弹窗）
    // ==================================================================

    /**
     * {@inheritDoc}
     *
     * <p><b>为什么不建新表</b>：{@code books.firstCollectedAt} 就是「这本书第一次进库的时间」，
     * 指标卡数的正是它。另立一张「新增记录表」只会多出一份需要同步的状态；而且 upsert 是幂等的 ——
     * 同一本书重采不会产生第二条记录，新表反而要把这个语义自己再维护一遍。
     *
     * <p>排序补 {@code _id} 兜底：批量 upsert 时同一批书的 {@code firstCollectedAt} 是同一个
     * 时间戳，只按它排的话同值记录在翻页时可能重复或漏掉（{@link PageQueryUtil} 的既有约定）。
     */
    @Override
    public PageResult<Book> todayCollectedBooks(Integer pageNum, Integer pageSize) {
        Criteria criteria = Criteria.where("firstCollectedAt").gte(startOfToday());
        Sort sort = Sort.by(Sort.Order.desc("firstCollectedAt"), Sort.Order.asc("_id"));
        return PageQueryUtil.getPageResult(mongoTemplate, pageNum, pageSize,
                criteria, Book.class, sort);
    }

    /**
     * 分类分布（饼图）。
     *
     * <h3>口径说明：按「采集目标」分，不按「平台分类」分</h3>
     * 每本书的 {@code collectSource} 是 {@code ["category:300000", "ranking:rising"]}
     * 这样的来源列表 —— 展开后按来源计数，就是「各采集目标分别贡献了多少本书」。
     *
     * <p>为什么不用平台给的 {@code category}（如「精品小说-社会小说」）：
     * 那个字段的取值有几百个，饼图会碎成一片糊；而且它反映的是<b>微信读书</b>的分类体系，
     * 不是「我这个采集器采了什么」。本系统的核心叙事就是「我采了哪些目标、各多少本」，
     * 所以按采集目标分才是这张图该回答的问题。
     *
     * <h3>二级分类的来源要并到它的一级上</h3>
     * 二级分类（如 {@code category:200003}「历史-历史读物」）现在也是合法采集目标。
     * 原样展示的话，同一个大类会被拆成十几个扇区；更糟的是 {@link #DISTRIBUTION_LIMIT}
     * 只有 30，二级会<b>把一级挤出榜单</b>。所以这里按 {@code parentCode} 把二级的计数
     * 累加到它的一级上 —— 图上看到的始终是「22 个大类 + 7 个榜单」。
     * 二级各自的采集量去「任务配置」按任务看。
     *
     * <p>一个副作用是：同一本书如果既在分类里又在榜单里，会被<b>计两次</b>
     * （各来源一次）。这是对的 —— 两个扇区的和会大于图书总数，因为维度是
     * 「来源贡献量」而不是「图书不重复归属」。
     */
    private List<Map<String, Object>> categoryDistribution() {
        // 🔴 $limit 不能留在管道里：它会在「二级归并到一级之前」截断。若前 30 名恰好被
        // 二级分类占满，一个「只采了一级、没采它的二级」的大类，会因为它自己那条
        // category:xxxxxx 排在第 30 名开外而被整个丢掉 —— 图上凭空少一个扇区。
        // 归并之后的扇区数上限只有 21 + 7 ≈ 28，所以把截断挪到归并之后做，
        // 实际上几乎不会触发，但不会丢数据。
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("collectSource",
                        new Document("$exists", true).append("$ne", new ArrayList<String>()))),
                new Document("$unwind", "$collectSource"),
                new Document("$group", new Document("_id", "$collectSource")
                        .append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("count", -1))
        );
        List<Document> rows = aggregate(pipeline, "books");

        // 把 "category:300000" 翻成「文学」。字典全量一次查出来做内存映射（现在 195 条），
        // 比在管道里 $lookup 一次更省事，也更容易排查（映射不上的会落到 code 上）
        Map<String, String> nameByKey = taxonomyNameMap();
        Map<String, String> parentOf = categoryParentMap();

        // 先按「归并后的 sourceKey」累加。管道已按 count 降序排过，用 LinkedHashMap
        // 记首次出现顺序 ≈ 沿用原排序；但归并会让顺序错乱（两个二级来源可能夹在中间），
        // 所以最后再显式排一次
        final Map<String, Long> countByKey = new LinkedHashMap<String, Long>();
        for (Document row : rows) {
            String sourceKey = row.getString("_id");
            String[] parts = splitSourceKey(sourceKey);
            String merged = rollUpToTopCategory(sourceKey, parts, parentOf);
            Long prev = countByKey.get(merged);
            countByKey.put(merged, (prev == null ? 0L : prev) + toLong(row.get("count")));
        }

        List<String> keys = new ArrayList<String>(countByKey.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return countByKey.get(b).compareTo(countByKey.get(a));
            }
        });

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (String sourceKey : keys) {
            String[] parts = splitSourceKey(sourceKey);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("sourceKey", sourceKey);
            item.put("type", parts[0]);
            item.put("targetId", parts[1]);
            // 字典里查不到就用 code 本身兜底 —— 不隐藏异常数据，让人能看见它
            item.put("name", nameByKey.getOrDefault(parts[0] + "|" + parts[1], sourceKey));
            item.put("count", countByKey.get(sourceKey));
            result.add(item);
        }
        // 归并之后再截断（见方法开头关于 $limit 的说明）
        if (result.size() > DISTRIBUTION_LIMIT) {
            return new ArrayList<Map<String, Object>>(result.subList(0, DISTRIBUTION_LIMIT));
        }
        return result;
    }

    /**
     * 评分分布（柱图）。
     *
     * <p><b>🔴 S4 修正</b>：用<b>顶层</b> {@code newRating}（推荐值 0~1000），
     * 不是 {@code newRatingDetail.newRating} —— 后者<b>不存在</b>
     * （{@code newRatingDetail} 只有 {@code {good, fair, poor, recent, title}}）。
     * 一开始这里写的是 {@code newRatingDetail.newRating}，柱图出来全是 0，
     * 顺着查才发现 {@code BookQuery} 的评分筛选和默认排序也踩了同一个坑
     * （详见 {@code BookQuery#resolveSortPath()} 的说明）。
     *
     * <p>这个坑之所以能藏到 S4，是因为它<b>不报错</b>：{@code $match} 匹配不到任何
     * 文档，管道正常返回空结果，聚合也不抛异常。
     *
     * <p>分桶用 {@code $cond} + {@code $floor} 而不是 {@code $bucket}：
     * {@code $bucket} 的边界数组必须严格递增、且恰好等于上边界的值会落到下一个桶
     * （1000 会掉出去），要额外加一个 1001 的边界才能覆盖。{@code $cond} 一眼就能看出
     * 「≥1000 归到最后一桶」，不用去查 {@code $bucket} 的边界语义。
     */
    private List<Map<String, Object>> ratingDistribution() {
        Document floorDiv = new Document("$floor", new Document("$divide",
                Arrays.asList("$newRating", RATING_BUCKET_SIZE)));
        Document bucketExpr = new Document("$cond", Arrays.asList(
                new Document("$gte", Arrays.asList("$newRating",
                        RATING_BUCKET_SIZE * RATING_BUCKET_COUNT)),
                RATING_BUCKET_COUNT - 1,
                floorDiv));

        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("newRating",
                        new Document("$gte", 0))),
                new Document("$project", new Document("bucket", bucketExpr)),
                new Document("$group", new Document("_id", "$bucket")
                        .append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("_id", 1))
        );
        List<Document> rows = aggregate(pipeline, "books");

        Map<Integer, Long> countByBucket = new HashMap<Integer, Long>();
        for (Document row : rows) {
            Object id = row.get("_id");
            if (id instanceof Number) {
                countByBucket.put(((Number) id).intValue(), toLong(row.get("count")));
            }
        }

        // 补齐空桶：没有书的评分区间也要出现在横轴上，否则柱图的 X 轴会「缺格」，
        // 让人误以为分布是连续的
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < RATING_BUCKET_COUNT; i++) {
            int min = i * RATING_BUCKET_SIZE;
            int max = min + RATING_BUCKET_SIZE;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("label", min + "-" + max);
            item.put("min", min);
            item.put("max", max);
            item.put("count", countByBucket.getOrDefault(i, 0L));
            result.add(item);
        }
        return result;
    }

    /**
     * 出版年份趋势（折线图）。
     *
     * <h3>为什么用 {@code $split} 取年份而不是 {@code $substrBytes}</h3>
     * {@code publishTime} 是字符串（{@code "2022-08-01 00:00:00"}）。取前 4 位做年份，
     * 直觉上该用 {@code $substrBytes: ["$publishTime", 0, 4]} —— 但它在字符串
     * 短于 4 个字符时的行为依赖版本（有的报错、有的补空），而库里确实存在
     * {@code "2022"} 这种只有年份的脏值。{@code $split} + {@code $arrayElemAt}
     * 对任意长度都安全，而且语义就是「取第一段」。
     *
     * <p>年份范围限制在 1900–2100：库里存在 {@code "0000-00-00 00:00:00"}
     * 这类「未知时间」的占位值，不过滤的话折线图上会出现一个「0000 年」的尖峰。
     */
    private List<Map<String, Object>> publishYearTrend() {
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("publishTime",
                        new Document("$type", "string"))),
                new Document("$project", new Document("year",
                        new Document("$arrayElemAt",
                                Arrays.asList(new Document("$split", Arrays.asList("$publishTime", "-")), 0)))),
                new Document("$match", new Document("$and", Arrays.asList(
                        new Document("year", new Document("$regex", "^[0-9]{4}$")),
                        new Document("year", new Document("$gte", "1900").append("$lte", "2100"))))),
                new Document("$group", new Document("_id", "$year")
                        .append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("_id", 1))
        );
        List<Document> rows = aggregate(pipeline, "books");

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Document row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("year", row.getString("_id"));
            item.put("count", toLong(row.get("count")));
            result.add(item);
        }
        return result;
    }

    /**
     * 近 7 天采集量（柱图）。口径与指标卡 {@code todayCollected} 一致：按
     * {@code firstCollectedAt} 数「新增」。
     *
     * <p><b>必须补齐没有数据的日期</b>：管道只会返回「有书的日子」。
     * 如果直接把它交给 ECharts，一张 7 天的柱图可能只有 3 根柱子 ——
     * 横轴不是时间轴而是「有数据的日子」，视觉上会让人以为那几天之间没有间隔。
     */
    private List<Map<String, Object>> recentCollected() {
        Date since = startOfDaysAgo(RECENT_DAYS - 1);
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("firstCollectedAt",
                        new Document("$gte", since))),
                new Document("$group", new Document("_id",
                        new Document("$dateToString", new Document("format", "%Y-%m-%d")
                                .append("date", "$firstCollectedAt")
                                .append("timezone", ZONE.getId())))
                        .append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("_id", 1))
        );
        List<Document> rows = aggregate(pipeline, "books");

        Map<String, Long> countByDay = new HashMap<String, Long>();
        for (Document row : rows) {
            countByDay.put(row.getString("_id"), toLong(row.get("count")));
        }

        LocalDate today = LocalDate.now(ZONE);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (int i = RECENT_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            String key = day.format(DAY_FMT);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("date", key);
            item.put("count", countByDay.getOrDefault(key, 0L));
            result.add(item);
        }
        return result;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 执行管道。输出用 {@code Document} 而不是实体 —— 结果是「聚合出来的形状」，没有对应实体 */
    private List<Document> aggregate(List<Document> pipeline, String collection) {
        AggregationOperation[] ops = new AggregationOperation[pipeline.size()];
        for (int i = 0; i < pipeline.size(); i++) {
            ops[i] = raw(pipeline.get(i));
        }
        try {
            return mongoTemplate.aggregate(Aggregation.newAggregation(ops), collection, Document.class)
                    .getMappedResults();
        } catch (RuntimeException e) {
            // 统计失败不该让仪表盘整个 500 —— 交给上层返回空数组，页面显示 0 比报错好
            log.error("聚合查询失败（collection={}）：{}", collection, e.getMessage());
            return new ArrayList<Document>();
        }
    }

    /**
     * 把原生 {@code Document} 包成 {@code AggregationOperation}。
     *
     * <p>直接返回原文档，不走 Spring Data 的字段名映射 —— 我们的字段名就是
     * Mongo 里的字段名（实体上没有 {@code @Field} 别名），映射只会多一层
     * 可能出错的转换。
     */
    private AggregationOperation raw(final Document doc) {
        return new AggregationOperation() {
            @Override
            public Document toDocument(AggregationOperationContext context) {
                return doc;
            }
        };
    }

    /** "category:300000" → ["CATEGORY", "300000"]；解析不出来时原样放回 */
    private String[] splitSourceKey(String sourceKey) {
        if (sourceKey == null) {
            return new String[]{"UNKNOWN", ""};
        }
        int idx = sourceKey.indexOf(':');
        if (idx <= 0 || idx == sourceKey.length() - 1) {
            return new String[]{"UNKNOWN", sourceKey};
        }
        String type = sourceKey.substring(0, idx).trim().toUpperCase();
        String id = sourceKey.substring(idx + 1).trim();
        return new String[]{type, id};
    }

    /** {@code "CATEGORY|300000" → "文学"} */
    private Map<String, String> taxonomyNameMap() {
        Map<String, String> map = new HashMap<String, String>();
        for (TaxonomyItem item : taxonomyRepository.findAll()) {
            if (item.getType() != null && item.getCode() != null && item.getName() != null) {
                map.put(item.getType().toUpperCase() + "|" + item.getCode(), item.getName());
            }
        }
        return map;
    }

    /**
     * 二级分类 code → 一级分类 code。只有二级才进这个表。
     * 用途见 {@link #categoryDistribution()} 的「二级分类的来源要并到它的一级上」。
     */
    private Map<String, String> categoryParentMap() {
        Map<String, String> map = new HashMap<String, String>();
        for (TaxonomyItem item : taxonomyRepository.findAll()) {
            if (item.getCode() != null && item.getParentCode() != null) {
                map.put(item.getCode(), item.getParentCode());
            }
        }
        return map;
    }

    /**
     * 把来源键归并到「一级分类」。
     *
     * <p>非 CATEGORY 的来源（榜单）原样返回；CATEGORY 里只有二级才需要换 code，
     * 一级自己（{@code parentCode} 为 null）也原样返回。解析不出来的 sourceKey
     * 同样原样返回 —— 不在统计里悄悄改写异常数据。
     *
     * <p><b>🔴 归并后必须保留原前缀的大小写。</b> {@code CollectCommand#sourceKey()}
     * 生成的是小写（{@code category:200003}），而 {@code splitSourceKey()} 会把类型段
     * {@code toUpperCase()}。所以这里不能拿 {@code parts[0]} 拼前缀 —— 否则
     * {@code category:200003} 会变成 {@code CATEGORY:200000}，与 {@code category:200000}
     * 不是同一个键：**同一个大类会裂成两个扇区，两个都叫「历史」**。
     */
    private String rollUpToTopCategory(String sourceKey, String[] parts, Map<String, String> parentOf) {
        if (!"CATEGORY".equals(parts[0])) {
            return sourceKey;
        }
        String parent = parentOf.get(parts[1]);
        if (parent == null) {
            return sourceKey;
        }
        // 用 sourceKey 里**原来那段前缀**，不要用 parts[0]（它已被 toUpperCase）
        int idx = sourceKey.indexOf(':');
        return sourceKey.substring(0, idx) + ":" + parent;
    }

    /**
     * 一级分类数（{@code parentCode == null}）。
     *
     * <p>Mongo 的 {@code {parentCode: null}} 同时匹配「字段值为 null」与「字段不存在」——
     * 后者正好覆盖了 {@code parentCode} 字段上线前就已播种的那 21 条，不需要回填数据。
     */
    private long countTopCategories(boolean enabledOnly) {
        Criteria criteria = Criteria.where("type").is(TargetType.CATEGORY.name())
                .and("parentCode").is(null);
        if (enabledOnly) {
            criteria = criteria.and("enabled").is(Boolean.TRUE);
        }
        return mongoTemplate.count(Query.query(criteria), TaxonomyItem.class);
    }

    private long countCollectedSince(Date since) {
        return mongoTemplate.count(
                Query.query(Criteria.where("firstCollectedAt").gte(since)), Book.class);
    }

    private long countTasksByStatus(boolean active) {
        if (active) {
            return mongoTemplate.count(Query.query(Criteria.where("status")
                            .in(Arrays.asList("RUNNING", "PAUSED"))),
                    CollectTask.class);
        }
        return mongoTemplate.count(new Query(), CollectTask.class);
    }

    /** 最近一次采集时间。用聚合的 $max 而不是「排序取第一条」，少反序列化一整份文档 */
    private Date maxLastCollectedAt() {
        List<Document> rows = aggregate(Arrays.asList(
                new Document("$group", new Document("_id", null)
                        .append("max", new Document("$max", "$lastCollectedAt")))
        ), "books");
        if (rows.isEmpty()) {
            return null;
        }
        Object max = rows.get(0).get("max");
        return max instanceof Date ? (Date) max : null;
    }

    private Date startOfToday() {
        return startOfDaysAgo(0);
    }

    /** N 天前的 00:00:00（Asia/Shanghai）。N=0 就是今天零点 */
    private Date startOfDaysAgo(int days) {
        LocalDate day = LocalDate.now(ZONE).minusDays(days);
        return Date.from(day.atStartOfDay(ZONE).toInstant());
    }

    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }
}
