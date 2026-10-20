package com.bookcollector.book.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 图书文档（原 MySQL 的 {@code books} + {@code book_categories} + {@code book_rating_details} 三表合一）。
 *
 * <p><b>为什么合并</b>：原 Python 保存一本书要 5~6 次非原子写（books 插/更新、
 * book_categories 先删后插、book_rating_details 先删后插）。合并成单文档后变成
 * <b>1 次原子写入</b>，天然幂等，也不会出现「图书存了但分类没存」的中间态。
 *
 * <p><b>字段口径</b>：下面 <b>46 个业务字段</b>与 {@code book_api_client.py} 的 {@code BookInfo}
 * 一一对应，命名按 Java 驼峰（原 SQL 的下划线命名见 {@code database_schema.sql}）。
 * 特别注意 3 个取自「外层记录」而不是 {@code bookInfo} 的字段：
 * <ul>
 *   <li>{@link #searchIdx} ← 外层 {@code searchIdx} —— 分页游标就靠它</li>
 *   <li>{@link #typeInfo} ← 外层 {@code type} —— <b>不是</b> bookInfo 里的 type</li>
 *   <li>{@link #readingCount} ← 外层 {@code readingCount}</li>
 * </ul>
 * 另有 2 个字段是从嵌套对象「拍平」出来的：{@code paperBook.skuId} → {@link #paperBookSkuId}，
 * {@code newRatingDetail.title} → {@link #newRatingTitle}。
 *
 * <p><b>数值口径不做换算</b>：{@code newRating} 阅读平台用 0~1000 的推荐值（913 = 91.3%），
 * 原 Python 直接存 913，这里同样存 913。展示层要显示百分比时自己除 10。
 *
 * <p><b>索引不在本类声明</b>：{@code application.yml} 里 {@code auto-index-creation: false}，
 * 所有索引由 {@code com.bookcollector.config.MongoIndexInitializer} 显式创建。
 * 唯一索引不是性能优化，是幂等写入的正确性依赖（风险 R22）。
 *
 * @see com.bookcollector.config.MongoIndexInitializer
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "books")
public class Book implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Mongo 主键（ObjectId 的字符串形式） */
    @Id
    private String id;

    // ==================================================================
    // ↓↓↓ 46 个业务字段 —— 与 BookInfo 一一对应 ↓↓↓
    // ==================================================================

    /** 图书 ID。★ 唯一索引 uk_bookId 建在这个字段上 */
    private String bookId;

    /** 书名 */
    private String title;

    /** 作者 */
    private String author;

    /** 译者。可选字段，实测约 7/20 的记录才有 */
    private String translator;

    /** 封面 URL */
    private String cover;

    /** 版本号。原 SQL 是 BIGINT，用 Long 防止将来变成毫秒时间戳时溢出 */
    private Long version;

    /** 格式，如 epub */
    private String format;

    /** 类型（bookInfo.type） */
    private Integer type;

    /** 价格（元） */
    private Integer price;

    /** 原价（元） */
    private Integer originalPrice;

    /** 是否售罄 */
    private Integer soldout;

    /** 图书状态 */
    private Integer bookStatus;

    /** 付费状态 */
    private Integer payingStatus;

    /** 付费类型（位掩码，如 1048577）。原 SQL 是 BIGINT */
    private Long payType;

    /** 图书简介。原文较长（含 [1] 这类脚注标记），保持原样 */
    private String intro;

    /** 价格（分），如 3500 对应 price=35 */
    private Integer centPrice;

    /** 是否完结 */
    private Integer finished;

    /** 最大免费章节序号 */
    private Integer maxFreeChapter;

    /** 是否免费 */
    private Integer free;

    /** 会员卡折扣 */
    private Integer mcardDiscount;

    /** 是否出版 */
    private Integer ispub;

    /** 额外类型 */
    private Integer extraType;

    /** CP ID，可为负数 */
    private Long cpid;

    /** 出版时间，形如 "2022-08-01 00:00:00"（保持字符串，不做 Date 解析） */
    private String publishTime;

    /** 分类的字符串形式，如 "精品小说-社会小说"。等价于 categories[0].title */
    private String category;

    /** 平台给这本书打的分类标签（原 book_categories 表）。注意：这是「平台分类」，不是我们的采集来源 */
    private List<BookCategory> categories;

    /** 是否有讲座 */
    private Integer hasLecture;

    /** 最后章节索引 */
    private Integer lastChapterIdx;

    /** 纸质书 SKU ID（由 paperBook.skuId 拍平而来） */
    private String paperBookSkuId;

    /** 是否阻止保存图片 */
    private Integer blockSaveImg;

    /** 语言，如 "zh-wr" */
    private String language;

    /** 是否繁体中文。API 返回真布尔值，不是 0/1 */
    private Boolean isTraditionalChinese;

    /** 是否隐藏更新时间。API 返回真布尔值 */
    private Boolean hideUpdateTime;

    /** 是否 EPUB 漫画 */
    private Integer isEpubComics;

    /** 是否竖排布局 */
    private Integer isVerticalLayout;

    /** 是否显示 TTS */
    private Integer isShowTts;

    /** 网页图书控制 */
    private Integer webBookControl;

    /** 自制激励。API 返回真布尔值 */
    private Boolean selfProduceIncentive;

    /** 是否自动下载 */
    private Integer isAutoDownload;

    /** 推荐值 0~1000（913 = 91.3%），不做换算 */
    private Integer newRating;

    /** 评价人数 */
    private Integer newRatingCount;

    /** 评价标题，如 "神作"（由 newRatingDetail.title 拍平而来） */
    private String newRatingTitle;

    /** ★ 分页游标来源：外层 searchIdx。下一页 maxIndex 取本页最后一条的 searchIdx */
    private Integer searchIdx;

    /** 外层 type。★ 别和 {@link #type}（bookInfo.type）搞混 */
    private Integer typeInfo;

    /** 外层 readingCount（阅读人数） */
    private Integer readingCount;

    /** 评分分布（原 book_rating_details 表） */
    private RatingDetail newRatingDetail;

    // ==================================================================
    // ↓↓↓ 以下为「API 会返回、但原 Python 未映射」的字段 ↓↓↓
    // 原 BookInfo 里没有这 4 个，等于白白丢弃了。
    // Mongo 无 schema，存下来的边际成本≈0；而一旦将来要用，
    // 重采一次的代价是 ~40 分钟/分类 × 21 个分类。所以这里保留。
    // ==================================================================

    /** 免费章节详情（原 API 的 maxFreeInfo） */
    private MaxFreeInfo maxFreeInfo;

    /** 有版权限制的章节 UID 列表 */
    private List<Integer> copyrightChapterUids;

    /** 推荐语（推广文案），实测仅 3/20 记录有 */
    private String lPushName;

    /** 作者 ID，实测仅 1/20 记录有 */
    private String authorVids;

    // ==================================================================
    // ↓↓↓ 以下字段来自 book/info 接口（2026-10-16「完善书籍信息」新增） ↓↓↓
    //
    // 背景：书单链路（booklist/single 的**拍平形态**）不返回 intro / category，
    // 需要事后用 GET /book/info?bookId=x 补齐。该接口返回约 90 个顶层字段，
    // 这里把 Book 原来**未建模**的那些全部补上 —— 口径同上面那 4 个扩展字段：
    // Mongo 无 schema，存下来的边际成本≈0；将来要用时重采一次的代价很大。
    //
    // ⚠️ 命名规则与既有字段一致：接口的 isEPUBComics / isShowTTS 在 Java 里叫
    //    isEpubComics / isShowTts（只把全大写缩写首字母大写）。本节的
    //    isHideTTSButton → isHideTtsButton、shouldHideTTS → shouldHideTts、
    //    askAIBook → askAiBook、AISummary → aiSummary 同理。
    // ⚠️ 库字段名 == Java 字段名（零 @Field），改名即改库结构。
    // ==================================================================

    // ---- 出版与体量 ----
    /** ISBN，如 "9787208163249" */
    private String isbn;
    /** 出版社，如 "上海人民出版社" */
    private String publisher;
    /** 总字数，如 207897 */
    private Integer totalWords;
    /** 纸质版定价，接口给 0 表示没有 */
    private Integer publishPrice;
    /** 书籍体积（字节），如 1042288 */
    private Integer bookSize;
    /** 章节数，如 44 */
    private Integer chapterSize;
    private String encodeId;
    private String deepLink;
    /** 书城 ID，接口给 "" 表示没有 */
    private String inStoreBookId;

    // ---- 时间戳（秒级，接口原样，不转 Date）----
    /** 更新时间戳（秒），如 1764316605 */
    private Long updateTime;
    /** 上架时间戳（秒），如 1595583271 */
    private Long onTime;

    // ---- 价格 / 市场 ----
    /** 单价（元），如 0.05 —— 有小数，用 Double */
    private Double unitPrice;
    /** 市场类型，如 0 */
    private Integer marketType;

    // ---- 评分补充（⚠️ 与上面的 newRating* 是不同口径）----
    /** 星级，如 77 */
    private Integer star;
    /** 评分人数，如 159 —— 与 {@link #newRatingCount} 不是一个口径 */
    private Integer ratingCount;
    /** 深度推荐值，如 862 */
    private Integer deepVRating;
    /** 是否显示深度推荐值标签，如 1 */
    private Integer showDeepVRatingLabel;
    /**
     * 评分分布（接口的 {@code ratingDetail}：one/two/three/four/five/recent）。
     *
     * <p>⚠️ 与 {@link #newRatingDetail}（good/fair/poor/recent/title）**不是**同一个对象 ——
     * 前者是五档星级分布，后者是三档好中差 + 档位文案。
     */
    private ScoreDetail ratingDetail;

    // ---- 展示 / 控制开关 ----
    /** 是否有关键点。接口返回真布尔值 */
    private Boolean hasKeyPoint;
    /** 是否推荐，如 0 */
    private Integer recommended;
    /** 讲座推荐，如 0 */
    private Integer lectureRecommended;
    /** 是否显示讲座按钮，如 1 */
    private Integer showLectureButton;
    /** 是否隐藏 TTS 按钮（接口 {@code isHideTTSButton}），如 0 */
    private Integer isHideTtsButton;
    /** 是否应隐藏 TTS（接口 {@code shouldHideTTS}），如 0 */
    private Integer shouldHideTts;
    /** 平台 TTS 开关，如 1 */
    private Integer wxtts;
    /** 问 AI 入口（接口 {@code askAIBook}），如 1 */
    private Integer askAiBook;
    /** AI 按钮类型，如 1 */
    private Integer aiBookButtonType;
    /** 自动求书，如 0 */
    private Integer autoBookRequest;
    /** 最新书评密模式，如 -1 */
    private Integer latestReviewSecretMode;

    // ---- 账号个人状态（⚠️ 是**采集那一刻该账号**的状态，换账号后不再成立）----
    /** 是否已关注，如 0 */
    private Integer follow;
    /** 是否私密，如 0 */
    private Integer secret;
    /** 是否下架，如 0 */
    private Integer offline;
    /** 讲座是否下架，如 0 */
    private Integer lectureOffline;
    /** 是否读完，如 0 */
    private Integer finishReading;
    /** 是否隐藏书评，如 0 */
    private Integer hideReview;
    /** 是否隐藏好友想法，如 0 */
    private Integer hideFriendMark;
    /** 是否被拉黑，如 0 */
    private Integer blacked;
    /** 是否有主题，如 0 */
    private Integer hasSubject;
    /** 是否自动付费，如 0 */
    private Integer isAutoPay;
    /** 可用状态，如 0 */
    private Integer availables;
    /** 是否已购，如 0 */
    private Integer paid;
    /** 章节是否付费，如 0 */
    private Integer isChapterPaid;

    // ---- 文本 ----
    /** AI 摘要（接口 {@code AISummary}），长文本 */
    private String aiSummary;

    // ---- 嵌套对象 / 数组 ----
    /** 版权方信息（接口 {@code copyrightInfo}） */
    private CopyrightInfo copyrightInfo;
    /** 作者分段（含高亮），如 {@code [{words:"虞云国", highlight:1, authorId:"252218"}]} */
    private List<AuthorSeg> authorSeg;
    /** 译者分段，结构同 {@link #authorSeg} */
    private List<AuthorSeg> translatorSeg;
    /** 封面盒信息（含调色板） */
    private CoverBoxInfo coverBoxInfo;
    /** 小程序跳转信息 */
    private SkuInfo skuInfo;
    /** 短时阅读开关 */
    private ShortTimeRead shortTimeRead;
    /** 榜单信息。接口实测给空对象 {@code {}}，保留原样 */
    private Map<String, Object> ranklist;

    // ==================================================================
    // ↓↓↓ 采集元数据（原 MySQL 没有，是本次改造新增的） ↓↓↓
    // ==================================================================

    /** 首次采集时间。批量 upsert 时用 $setOnInsert 写入，之后不再改 */
    private Date firstCollectedAt;

    /** 最近一次采集时间。每次 upsert 都用 $set 覆盖 */
    private Date lastCollectedAt;

    /**
     * 最近一次「完善书籍信息」<b>尝试</b>的时间。{@code null} = 从没补过（2026-10-16 新增）。
     *
     * <p>🔴 记的是「<b>试过</b>」而不是「补到了」—— 有的书平台本来就没有分类/简介，
     * 补也补不出来。只有把「试过」记下来，用户才能把这类书从待补清单里排除掉；
     * 否则它们会永远留在「缺失信息」筛选结果里，分不清是「还没补」还是「补不出来」。
     *
     * <p>⚠️ 它不是采集时间，所以 {@code BookRepository#buildUpsertUpdate} 的
     * 「采集元数据」处理与它无关 —— 采集链路不碰这个字段（{@code BookParser} 不设它，
     * 而转换器不写 null），补一次就会一直留着。
     */
    private Date enrichedAt;

    /**
     * 采集来源列表，形如 {@code ["category:300000", "ranking:rising"]}。
     * 同一本书可能既属于某分类又在某榜单里，用 {@code $addToSet} 累加，不会重复。
     */
    private List<String> collectSource;

    /**
     * 是否来自「付费精选」采集（2026-10-20）：{@code 1} = 是；默认空（null）。
     *
     * <p>付费精选采集<b>不覆盖</b>已有书籍的其它字段：书已存在时只累加
     * {@link #collectSource} 并把本字段置 1（见 {@code BookRepository#upsertPaidMany}）。
     */
    private Integer paidCollect;

    /**
     * 这本书所属的<b>书单</b>列表。同一本书可能存在于多个书单里，全部保留。
     *
     * <pre>
     * [ { "bookListId": "391152476_7sKNjWqRN", "bookListName": "豆瓣读书 ‖ Top250" },
     *   { "bookListId": "47038819_7uR1AkeoO", "bookListName": "文学书单" } ]
     * </pre>
     * 用户 2026-10-14 明确要求数组里同时带 ID 和名称（示例见需求文档第 4 节）。
     *
     * <h3>🔴 用 {@code $addToSet} 累加，不走 {@code $set}</h3>
     * 与 {@link #collectSource} 同一个理由：一本被分类/榜单采到的书，之后又被书单采到，
     * 走 {@code $set} 会把之前的书单引用整段覆盖掉（静默丢数据）。
     * 见 {@code BookRepository#buildUpsertUpdate}。
     *
     * <p><b>⚠️ 去重是按「整个子文档」比较的</b>，键顺序也参与 ——
     * 所以写入时显式按 {@code bookListId} → {@code bookListName} 的顺序拼 Document。
     * 另外：名称缺失时用 {@code bookListId} 兜底（同「新建任务时没有名称就用 ID 当名称」的口径），
     * 免得同一个书单先存成 {@code {id, null}}、后存成 {@code {id, "真名"}} 变成两条。
     */
    private List<BookListRef> bookLists;

    // ==================================================================
    // 嵌套结构
    // ==================================================================

    /** 平台分类标签。对应 API 的 {@code bookInfo.categories[]} */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookCategory implements Serializable {

        private static final long serialVersionUID = 1L;

        private Integer categoryId;
        private Integer subCategoryId;
        private Integer categoryType;
        /** 形如 "文学-经典作品" */
        private String title;
    }

    /** 评分分布。对应 API 的 {@code bookInfo.newRatingDetail} */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RatingDetail implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 好评数 */
        private Integer good;
        /** 一般数 */
        private Integer fair;
        /** 差评数 */
        private Integer poor;
        /** 近期评价数 */
        private Integer recent;
        /** 评价档位文案，如 "神作" */
        private String title;
    }

    /** 免费章节详情。对应 API 的 {@code bookInfo.maxFreeInfo} */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaxFreeInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        private Integer maxFreeChapterIdx;
        private Integer maxFreeChapterUid;
        /** 免费比例（百分数），如 79 */
        private Integer maxFreeChapterRatio;
    }

    /**
     * 书单引用（{@link Book#getBookLists()} 的元素）。
     *
     * <p>字段名与需求文档给的示例 JSON 逐字一致（{@code bookListId} / {@code bookListName}）——
     * 本项目 Mongo 字段名 == Java 字段名（零 {@code @Field}），改名就是改库结构。
     *
     * <p>⚠️ 这两个名字<b>不踩 Jackson 命名陷阱</b>（对比 {@code lPushName} → {@code lpushName}）：
     * Lombok 生成 {@code getBookListId()}，JavaBeans 去首字母大写得到 {@code bookListId}，
     * 与 Java 字段名一致。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookListRef implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 书单 ID，形如 {@code 391152476_7sKNjWqRN} */
        private String bookListId;

        /** 书单名称。取不到时用 {@link #bookListId} 兜底，不会为 null */
        private String bookListName;
    }

    /**
     * 接口 {@code ratingDetail} —— 评分分布（五档星级 + 近期）。
     *
     * <p>⚠️ 与 {@link RatingDetail}（{@code newRatingDetail}，三档 good/fair/poor + title）
     * <b>不是</b>同一个东西，两者字段名完全不同。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreDetail implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 一星数 */
        private Integer one;
        /** 二星数 */
        private Integer two;
        /** 三星数 */
        private Integer three;
        /** 四星数 */
        private Integer four;
        /** 五星数 */
        private Integer five;
        /** 近期评价数 */
        private Integer recent;
    }

    /** 接口 {@code copyrightInfo} —— 版权方 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CopyrightInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;
        private String name;
        private Long userVid;
        private Integer role;
        private String avatar;
        private Integer cpType;
    }

    /** 接口 {@code authorSeg[]} / {@code translatorSeg[]} 的元素 —— 带高亮的作者名分段 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthorSeg implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 分段文本 */
        private String words;
        /** 是否高亮，如 1 */
        private Integer highlight;
        /** 作者 ID，如 "252218" */
        private String authorId;
    }

    /** 接口 {@code coverBoxInfo} —— 封面盒（调色板等） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoverBoxInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 模糊占位图 */
        private String blurhash;
        /** 调色板 */
        private List<CoverColor> colors;
        /** 主色调（接口字段名 {@code dominate_color}） */
        private DominateColor dominateColor;
        /** 定制封面（接口字段名 {@code custom_cover}） */
        private String customCover;
        /** 定制推荐封面（接口字段名 {@code custom_rec_cover}） */
        private String customRecCover;
        /** 纯色列表 */
        private List<String> colorsPure;
    }

    /** {@link CoverBoxInfo#getColors()} 的元素 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoverColor implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 形如 "1/4" */
        private String key;
        /** 形如 "#2e1800" */
        private String hex;
    }

    /** {@link CoverBoxInfo#getDominateColor()} —— 主色调 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DominateColor implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 形如 "#f6efe0" */
        private String hex;
        /** HSV 三元组 */
        private List<Double> hsv;
    }

    /** 接口 {@code skuInfo} —— 小程序跳转信息 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkuInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        private String miniProgramId;
        private String path;
    }

    /** 接口 {@code shortTimeRead} —— 短时阅读开关 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShortTimeRead implements Serializable {

        private static final long serialVersionUID = 1L;

        private Integer active;
    }
}
