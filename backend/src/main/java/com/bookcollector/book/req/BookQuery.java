package com.bookcollector.book.req;

import com.bookcollector.util.PublishTimeUtil;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * 图书查询条件对象。
 *
 * <p>把「前端传什么」和「Mongo 怎么查」解耦：Controller 收一个 BookQuery，
 * {@code BookRepository} 负责翻译成 {@code Criteria}。
 *
 * <h3>🔴 2026-10-10 反转：「不做模糊搜索」这条约定已废除</h3>
 * 原先这里写的是「<b>不做模糊搜索</b>（书名/作者 LIKE）」，理由是原 Python 用
 * {@code category LIKE '%x%'}，在 MongoDB 里要写正则、用不上索引。
 * <b>2026-10-10 用户明确要求「书名 / 作者都要模糊搜索」</b>，所以：
 * <ul>
 *   <li>{@link #title} 新增，包含匹配；</li>
 *   <li>{@link #author} 由<b>精确匹配</b>改为<b>包含匹配</b>。</li>
 * </ul>
 * <b>代价要说清楚</b>：未锚定的正则用不上索引，这两个条件会让查询退化成
 * 全集合扫描。本项目单机、几万条数据量，可接受；但这是<b>刻意接受的代价</b>，
 * 不是「顺手加的」—— 不要再往别的字段上扩散。
 * 转义与编译统一走 {@link com.bookcollector.util.RegexUtil#contains(String)}。
 *
 * <p><b>⚠️ FR-B2 的「字数区间」没有实现</b>：微信读书这个接口返回的
 * {@code bookInfo} 里<b>没有字数字段</b>（已用 {@code books.json} 的 20 本书样本
 * 逐个核对过 43 个键）。没有数据源就没有筛选可言，用阅读人数下限
 * （{@link #minReadingCount}）替代「热度」这个维度。要真做字数，得换接口。
 *
 * <p>所有字段都是<b>与</b>关系（AND）。多个条件之间没有 OR 语义。
 */
public class BookQuery {

    /** 每页最大条数，防止前端传 size=100000 把服务打爆 */
    public static final int MAX_SIZE = 200;

    /** 默认每页条数 */
    public static final int DEFAULT_SIZE = 20;

    /** 按平台分类 ID 精确筛（匹配 categories.categoryId，不是字符串前缀匹配） */
    private Integer categoryId;

    /** 评分下限（含）。★ 路径是<b>顶层</b> {@code newRating}，见 {@link #resolveSortPath()} 的说明 */
    private Integer minRating;

    /** 评分上限（含） */
    private Integer maxRating;

    /** 阅读人数下限（含） */
    private Integer minReadingCount;

    /** 按采集来源筛：CATEGORY / RANKING */
    private String targetType;

    /** 按采集来源筛：目标 ID */
    private String targetId;

    /**
     * 书名，<b>包含匹配</b>（模糊搜索）。
     *
     * <p>2026-10-10 新增。空/空白 = 不筛这一项。
     */
    private String title;

    /**
     * 作者，<b>包含匹配</b>（模糊搜索）。
     *
     * <p>⚠️ 2026-10-10 之前这里是<b>精确匹配</b>。改成模糊之后，
     * 搜「余华」能搜到「余华 / 著」这类带后缀的作者串。
     */
    private String author;

    /**
     * 出版时间下限，形如 {@code "2020"} 或 {@code "2020-06"}。
     *
     * <p>用<b>字符串比较</b>而不是日期解析，因为 {@code publishTime} 在库里就是
     * 字符串（{@code "2022-08-01 00:00:00"}，见 {@code Book.publishTime}），
     * 且它的格式是定长零填充的，字典序恰好等于时间序 —— 可以直接用
     * {@code $gte} / {@code $lte} 做区间。把字符串转成 Date 反而会引入
     * 「有的记录没有秒、有的格式不规范」这类解析失败，收益为零。
     */
    private String minPublishTime;

    /** 出版时间上限，形如 {@code "2023-12"}。传 {@code "2023"} 时语义是「2023 年全年」，见 resolveMaxPublishTime() */
    private String maxPublishTime;

    /**
     * 采集时间下限，形如 {@code "2026-10-01"}（**本地日期**，年月日）。
     *
     * <p>2026-10-10 新增。匹配的库字段是 {@code lastCollectedAt}（<b>Date</b>，
     * 不是字符串）—— 所以这条筛选与出版时间那条走的是<b>两种不同的比较方式</b>：
     * 出版时间是字符串字典序，这里是真正的日期区间。
     *
     * <p>语义是「含当天」：{@code "2026-10-01"} → {@code 2026-10-01 00:00:00.000}。
     */
    private String minCollectedAt;

    /**
     * 采集时间上限，形如 {@code "2026-10-10"}（本地日期，年月日）。
     *
     * <p>⚠️ 语义是「<b>含当天整天</b>」：{@code "2026-10-10"} → {@code 2026-10-10 23:59:59.999}，
     * 而不是当天零点。否则用户选「到 10-10」会漏掉 10 号采集的全部数据 ——
     * 而这种少数据是<b>静默</b>的，看不出来。见 {@link #resolveMaxCollectedAt()}。
     */
    private String maxCollectedAt;

    /** 排序字段。可选值见 {@link #resolveSortPath()}，非法值回退到评分 */
    private String sortField = "newRating";

    /** 是否升序。默认降序（高分/多人的在前） */
    private boolean asc = false;

    /** 页码，从 1 开始 */
    private int page = 1;

    /** 每页条数 */
    private int size = DEFAULT_SIZE;

    public BookQuery() {
    }

    // ------------------------------------------------------------------
    // 归一化：外部传什么脏值都不能让查询出错
    // ------------------------------------------------------------------

    /** 页码至少为 1 */
    public int getSafePage() {
        return page < 1 ? 1 : page;
    }

    /** 每页条数夹在 [1, MAX_SIZE] */
    public int getSafeSize() {
        if (size < 1) {
            return DEFAULT_SIZE;
        }
        return size > MAX_SIZE ? MAX_SIZE : size;
    }

    /**
     * 把前端的排序字段名翻译成 Mongo 的字段路径。
     *
     * <p><b>白名单机制</b>：不合法的一律回退到评分排序。绝不能把用户传的字符串
     * 直接拼进 Mongo 路径 —— 那是注入面。
     *
     * <h3>🔴 S4 修正：{@code newRating} 的路径是<b>顶层</b> {@code newRating}</h3>
     * 这里原来写的是 {@code "newRatingDetail.newRating"}，是错的。
     * {@code newRatingDetail} 的实际结构是 {@code {good, fair, poor, recent, title}}
     * —— 它里面<b>没有</b> {@code newRating} 这个键（已用 {@code books.json} 的
     * 20 本书样本逐字段核对，并对着库里 140 条真实数据验证）。
     * 推荐值 0~1000 是<b>顶层</b>的 {@link com.bookcollector.book.entity.Book#newRating}。
     *
     * <p>后果是静默的：排序指向不存在的字段不会报错，只是「排了跟没排一样」；
     * 而筛选（{@link #minRating} / {@link #maxRating}）会<b>永远返回 0 条</b>。
     * 页面不报错、只是空列表 —— 这种缺陷在 S1/S2 的测试里照不出来，
     * 因为那些测试验的是「解析对不对」「索引建没建」，没人验过「筛选真的筛得动吗」。
     * S4 做仪表盘评分分布时，发现柱图全是 0 才牵出来。
     */
    public String resolveSortPath() {
        if (sortField == null) {
            return "newRating";
        }
        switch (sortField) {
            case "readingCount":
                return "readingCount";
            case "newRatingCount":
                return "newRatingCount";
            case "lastCollectedAt":
                return "lastCollectedAt";
            case "publishTime":
                return "publishTime";
            case "newRating":
            default:
                return "newRating";
        }
    }

    /** 采集来源的复合键，形如 "category:300000"。两者缺一就返回 null（表示不筛来源） */
    public String resolveSourceKey() {
        if (targetType == null || targetType.trim().isEmpty()
                || targetId == null || targetId.trim().isEmpty()) {
            return null;
        }
        return targetType.trim().toLowerCase() + ":" + targetId.trim();
    }

    /** 书名关键词。空白串归一成 null（「没填」而不是「找书名是空字符串的书」） */
    public String resolveTitle() {
        return blankToNull(title);
    }

    /** 作者关键词。空白串归一成 null */
    public String resolveAuthor() {
        return blankToNull(author);
    }

    /** 出版时间下限。空白串归一成 null */
    public String resolveMinPublishTime() {
        return blankToNull(minPublishTime);
    }

    /**
     * 出版时间上限，把「粗粒度」的输入补齐成可直接做字符串比较的形式。
     *
     * <p>补位规则本身在 {@link com.bookcollector.util.PublishTimeUtil#padUpperBound(String)}
     * —— 候选清单（发布配置页）也要用同一条规则，抽出去避免两处口径漂移。
     *
     * <p>为什么要补：用户填 {@code "2023"} 想表达的是「2023 年全年」，
     * 但 {@code "2023"} 做 {@code $lte} 会把 {@code "2023-08-01 00:00:00"}
     * 排除掉（因为 {@code "2023" < "2023-08-01"}）。补齐后
     * {@code "2023-12-31 23:59:59"} 就正确覆盖了全年。
     *
     * <table border="1">
     *   <tr><th>输入</th><th>补成</th><th>含义</th></tr>
     *   <tr><td>{@code 2023}</td><td>{@code 2023-12-31 23:59:59}</td><td>2023 全年</td></tr>
     *   <tr><td>{@code 2023-06}</td><td>{@code 2023-06-31 23:59:59}</td><td>2023 年 6 月</td></tr>
     *   <tr><td>其它</td><td>原样</td><td>已经是完整时间，不再猜</td></tr>
     * </table>
     *
     * <p>注意 {@code 2023-06-31} 是个不存在的日期 —— 但这没关系，我们做的是
     * <b>字符串</b>比较不是日期比较，{@code "-31 23:59:59"} 只起「该月最大值」
     * 的作用，不会被解析成日期。
     */
    public String resolveMaxPublishTime() {
        return PublishTimeUtil.padUpperBound(maxPublishTime);
    }

    /**
     * 采集时间下限 → 当天 <b>00:00:00.000</b>（本地时区）。
     *
     * <p>为什么要转成 {@link Date}：{@code lastCollectedAt} 在库里是 BSON date，
     * 拿字符串去比会永远不匹配（<b>类型敏感，且不报错</b>）——
     * 这正是本项目在 {@code categories.categoryId} 上踩过的同类坑。
     *
     * <p>时区取 {@link ZoneId#systemDefault()}：本机单机跑，用户看到的日期就是本地日期。
     */
    public Date resolveMinCollectedAt() {
        LocalDate d = parseLocalDate(minCollectedAt);
        if (d == null) {
            return null;
        }
        return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 采集时间上限 → 当天 <b>23:59:59.999999999</b>（本地时区）。
     *
     * <p>⚠️ 补到当天最后一刻，不是当天零点。用户填「到 2026-10-10」想表达的是
     * 「包含 10 号一整天」；补零点会把 10 号采到的书全漏掉，而且<b>不报错</b>。
     * 同一条规则在出版时间上叫 {@code padUpperBound}，只是那边是字符串补位。
     */
    public Date resolveMaxCollectedAt() {
        LocalDate d = parseLocalDate(maxCollectedAt);
        if (d == null) {
            return null;
        }
        return Date.from(d.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 解析 {@code yyyy-MM-dd}（ISO_LOCAL_DATE，前端日期组件固定按这个格式传）。
     *
     * <p><b>解析不了就返回 null</b>（= 不筛这一项），不抛异常 —— 见类注释
     * 「外部传什么脏值都不能让查询出错」。值只可能来自本项目自己的日期组件，
     * 出现脏值基本意味着有人手改 URL，为它把整个查询打成 500 不值得。
     */
    private LocalDate parseLocalDate(String s) {
        String v = blankToNull(s);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String blankToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }

    // ------------------------------------------------------------------
    // getter / setter
    // ------------------------------------------------------------------

    public Integer getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Integer categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getMinRating() {
        return minRating;
    }

    public void setMinRating(Integer minRating) {
        this.minRating = minRating;
    }

    public Integer getMaxRating() {
        return maxRating;
    }

    public void setMaxRating(Integer maxRating) {
        this.maxRating = maxRating;
    }

    public Integer getMinReadingCount() {
        return minReadingCount;
    }

    public void setMinReadingCount(Integer minReadingCount) {
        this.minReadingCount = minReadingCount;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getMinPublishTime() {
        return minPublishTime;
    }

    public void setMinPublishTime(String minPublishTime) {
        this.minPublishTime = minPublishTime;
    }

    public String getMaxPublishTime() {
        return maxPublishTime;
    }

    public void setMaxPublishTime(String maxPublishTime) {
        this.maxPublishTime = maxPublishTime;
    }

    public String getMinCollectedAt() {
        return minCollectedAt;
    }

    public void setMinCollectedAt(String minCollectedAt) {
        this.minCollectedAt = minCollectedAt;
    }

    public String getMaxCollectedAt() {
        return maxCollectedAt;
    }

    public void setMaxCollectedAt(String maxCollectedAt) {
        this.maxCollectedAt = maxCollectedAt;
    }

    public String getSortField() {
        return sortField;
    }

    public void setSortField(String sortField) {
        this.sortField = sortField;
    }

    public boolean isAsc() {
        return asc;
    }

    public void setAsc(boolean asc) {
        this.asc = asc;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
