package com.bookcollector.readinglist.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 阅读清单里的一本书（集合 {@code reading_list}）。
 *
 * <h3>它和「藏书阁」（{@code shelf_books}）是两个维度</h3>
 * <ul>
 *   <li>{@code shelf_books} = <b>我收藏了什么</b>（藏书阁 / 我的藏书）；</li>
 *   <li>{@code reading_list} = <b>我打算读什么、读到哪了</b>（阅读计划）。</li>
 * </ul>
 * 两者<b>不可互相推导</b>：收藏了一本不等于想读，想读一本也不等于收藏。
 * 所以「加入阅读清单」<b>不要求</b>这本书已经在藏书阁里 ——
 * 只要它在 {@code books} 里就能加。
 *
 * <p>🔴 <b>移除一个不影响另一个</b>：从藏书阁移除一本书，阅读清单里那条<b>保留</b>；
 * 从阅读清单移除，藏书阁里那条也<b>保留</b>。两边的 Service 互不感知对方。
 *
 * <h3>字段是「快照」而不是引用</h3>
 * 同 {@code shelf_books} / {@code publish_record} 的道理：书名 / 作者 / 封面 / 分类 /
 * 评分 / 简介都是<b>加入阅读清单那一刻</b>的值。图书库里的记录后来被改过（人工编辑）
 * 或被删过，都不该改写这里 —— 否则「我当初想读的是哪一本」就查不出来了。
 *
 * <p>⚠️ 代价同样要说清楚：图书库删掉一本书后，阅读清单里<b>仍会留着它</b>。
 * 想让它消失，用「移除阅读清单」。
 *
 * <h3>为什么不存正文</h3>
 * 同 {@code shelf_books}：{@code books} 里带几千字的 {@code content} / {@code rawContent}，
 * 阅读清单只用来「看」与「标记进度」，用不上它们。
 */
@Data
@Document(collection = "reading_list")
public class ReadingListItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Mongo 主键 */
    @Id
    private String id;

    /**
     * 图书 ID。★ 唯一索引 {@code uk_bookId} 建在它上面 ——
     * 同一本书在阅读清单里只能有一行（重复添加是幂等的，不是新增一条）。
     */
    private String bookId;

    // ------------------------------------------------------------------
    // 图书快照（加入阅读清单那一刻的值）
    // ------------------------------------------------------------------

    /** 书名 */
    private String title;

    /** 作者（可能是「作者 + 译者/编者」） */
    private String author;

    /** 封面 URL */
    private String cover;

    /** 平台分类的字符串形式，如 {@code "文学-散文杂著"} */
    private String category;

    /** 推荐值 0~1000（913 = 91.3%），不做换算 */
    private Integer newRating;

    /** 评价人数 */
    private Integer newRatingCount;

    /** 阅读人数 */
    private Integer readingCount;

    /** 官方简介（详情面板用） */
    private String intro;

    // ------------------------------------------------------------------
    // 阅读清单自己的字段
    // ------------------------------------------------------------------

    /**
     * 阅读状态，存<b>枚举名</b>：{@code WANT_TO_READ} / {@code READING} / {@code FINISHED}。
     *
     * <p>用 {@code String} 而不是枚举类型：查询要按字符串等值匹配
     * （{@code Criteria.where("status").is("READING")}），
     * 合法性由 {@link ReadingStatus#parse(String)} 在 Service 层守住。
     */
    private String status;

    /** 加入阅读清单的时间 */
    private Date addedAt;

    /**
     * 状态<b>最后一次变更</b>的时间（加入时 = {@code addedAt}）。
     *
     * <p>列表按它倒序 —— 「最近在读的排前面」。没有这个字段就只能退回按加入时间排，
     * 那和「我最近在读什么」对不上。
     */
    private Date statusUpdatedAt;
}
