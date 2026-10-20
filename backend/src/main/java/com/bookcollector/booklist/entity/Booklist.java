package com.bookcollector.booklist.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 书单（集合 {@code booklists}）。
 *
 * <h3>它从哪来</h3>
 * 由<b>链路 A</b>（接口 {@code /booklist/category}，按平台分类分页 + {@code tag=最多收藏}）
 * 发现并 upsert 进来。<b>不是</b>人工维护的字典 —— 用户明确要求「不做种子」，
 * 所以本表没有 {@code SeedRunner} 入口，只有采集链路一个写入者。
 *
 * <h3>🔴 字段名口径：与接口原样对齐，只做一处改名</h3>
 * 接口返回的字段名基本照搬，<b>唯一改名</b>是 {@code totalCount → }{@link #bookCount}：
 * 书单响应里 {@code totalCount} 表示「这张书单里有几本书」，而响应信封里也有一个
 * {@code totalCount}（表示「该分类下共有几张书单」）。两个同名、语义完全不同，
 * 直接照搬会在排障时把人绕进去，所以这里显式改名为 {@code bookCount}。
 *
 * <p>项目铁律：<b>Mongo 字段名 == Java 字段名</b>（零 {@code @Field}），
 * 所以改 Java 字段名就等于改库字段名，上面这次改名要一次到位。
 *
 * <h3>⚠️ {@code userVid} 用 {@code Long} 而不是 {@code Integer}</h3>
 * 样例里是 {@code 352003193}（int 放得下），但 vid 是平台侧的全局自增 ID，
 * 没有「一定不超过 2^31」的保证。存成 int 一旦溢出是<b>静默截断</b>（负数），
 * 而用 Long 的边际成本是 0。
 *
 * <h3>⚠️ 时间字段是 unix <b>秒</b></h3>
 * 接口给的是秒（如 {@code 1791620913}），这里统一换算成 {@link Date} 存 ——
 * 与项目其它表的「时间字段一律 Date」保持一致，避免前端拿到两种单位。
 * 换算只在 {@code BooklistParser} 里做一次，本类不做。
 *
 * <h3>索引不在本类声明</h3>
 * {@code application.yml} 里 {@code auto-index-creation: false}，全部索引由
 * {@code com.bookcollector.config.MongoIndexInitializer#initBooklists()} 显式创建。
 * 其中 {@code uk_booklistId} 是<b>幂等写入的正确性依赖</b>（同 {@code books.uk_bookId}）：
 * 同一个书单会被多个分类的采集扫到，没有唯一索引就会插出多行。
 *
 * @see com.bookcollector.config.MongoIndexInitializer
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "booklists")
public class Booklist implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Mongo 主键 */
    @Id
    private String id;

    /**
     * 书单 ID，形如 {@code "352003193_8cKur0WC3"}（{@code {userVid}_{随机串}}）。
     * ★ 唯一索引 {@code uk_booklistId} 建在它上面；也是 {@code TargetType.BOOKLIST} 的 targetId。
     */
    private String booklistId;

    /** 书单名 */
    private String name;

    /** 简介。可能为空串 */
    private String description;

    /** 创建者 ID。与 {@link #authorName} 同一个来源（接口的 {@code userVid}） */
    private Long userVid;

    /**
     * 创建者名称，由接口的 {@code author.name} 拍平而来。
     *
     * <p>⚠️ 接口 A 的 {@code author} <b>只有 3 个字段</b>（{@code userVid/name/avatar}），
     * 比接口 B 的 {@code author}（11 个字段）少得多。这里只存用得上的两个。
     */
    private String authorName;

    /** 创建者头像 URL，由接口的 {@code author.avatar} 拍平而来 */
    private String authorAvatar;

    // ------------------------------------------------------------------
    // 接口原样带过来的计数与时间
    // ------------------------------------------------------------------

    /**
     * 书单里有几本书。★ 采集闸门 1（{@code >= 50}）看它。
     *
     * <p>⚠️ 接口里这个字段叫 {@code totalCount}，与本表改名理由见类注释。
     */
    private Integer bookCount;

    /**
     * 收藏人数。★ 采集闸门 2（{@code >= 10000}）看它，也是「提前终止」的判据。
     *
     * <p>接口在带 {@code tag=最多收藏} 时<b>按这个字段降序返回</b>，
     * 所以一旦某条的 {@code collectCount} 低于阈值，后面所有条都不用看了。
     */
    private Integer collectCount;

    /** 点赞数 */
    private Integer likedCount;

    /** 分享数 */
    private Integer shareCount;

    /** 创建时间（接口是 unix 秒） */
    private Date createTime;

    /** 更新时间（接口是 unix 秒） */
    private Date updateTime;

    /** 最后阅读时间（接口是 unix 秒） */
    private Date lastReadTime;

    // ------------------------------------------------------------------
    // 本模块自己维护的字段
    // ------------------------------------------------------------------

    /**
     * 平台分类归属 —— 这张书单出现在哪些平台分类下。
     *
     * <p><b>为什么是数组</b>：一张书单可能同时挂在多个分类板块下（接口按分类分页返回，
     * 同一张书单会在多个分类的列表里出现）。所以用 {@code $addToSet} 累加，
     * 而不是每次覆盖成「最后一次扫到的分类」。
     *
     * <p>🔴 <b>{@code categoryId} 是字符串</b>：接口 A 的分类 ID 形如 {@code "200000"}
     * 甚至 {@code "topic"}，而 {@code books.categories[].categoryId} 是 <b>int</b>。
     * 两者<b>不能直接互查</b> —— 本项目踩过「拿字符串去匹配 int 字段 → 恒 0 条、不报错」
     * 的坑。要关联时显式转换。
     */
    private List<PlatformCategory> platformCategories;

    /**
     * 采书状态，存<b>枚举名</b>：{@code PENDING} / {@code COLLECTING} / {@code COLLECTED} / {@code FAILED}。
     *
     * <p>用 {@code String} 而不是枚举类型：查询要按字符串等值匹配，
     * 合法性由 {@link BooklistCollectStatus#parse(String)} 在 Service 层守住
     * （同 {@code reading_list.status} 的写法）。
     */
    private String collectStatus;

    /**
     * 实际采到几本书。
     *
     * <p>与 {@link #bookCount}（接口声明的书数）对不上时，说明书单被改过、
     * 或采集时被过滤（排除分类 / 期刊只留本月）。这是<b>唯一能看出这件事的地方</b>，
     * 所以单独存一列，而不是靠「采完了」这个状态去猜。
     */
    private Integer bookCountCollected;

    /** 首次发现时间。upsert 时用 {@code $setOnInsert} 写入，之后不再改 */
    private Date firstSeenAt;

    /** 最近一次被采集链路扫到的时间。每次 upsert 都用 {@code $set} 覆盖 */
    private Date lastSeenAt;

    /** 最近一次采书时间（链路 B）。没采过时为 null */
    private Date lastCollectedAt;

    /**
     * 平台分类引用。
     *
     * <p>只存 ID 与标题，不存 totalCount —— 那是「该分类下书单总数」，
     * 属于分类的属性而不是书单的属性，而且每次请求都在变，存下来只会误导。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformCategory implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 分类 ID，<b>字符串</b>（形如 {@code "200000"} 或 {@code "topic"}） */
        private String categoryId;

        /** 分类名，如 {@code "历史"} */
        private String title;
    }
}
