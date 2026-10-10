package com.bookcollector.shelf.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 藏书阁里的一本书（集合 {@code shelf_books}）。
 *
 * <h3>2026-10-10：从「读候选清单」改成「自己存」</h3>
 * 第一版的书架是<b>只读</b>的，直接投影 {@code book_interpretations}（AI 解读候选清单）
 * 里的书 —— 那时藏书阁只是「把候选清单换个好看的方式展示」。
 *
 * <p>用户后来要求藏书阁成为<b>自己的一本书籍集合</b>：图书库里能「添加藏书 / 移除藏书」，
 * 于是有了这张表。它不再是任何其它集合的视图。
 *
 * <h3>字段是「快照」而不是引用</h3>
 * 同 {@code publish_record} 的道理：书名 / 作者 / 封面 / 分类 / 评分 / 简介都是
 * <b>加入藏书阁那一刻</b>的值。图书库里的记录后来被改过（人工编辑）或被删过，
 * 都不该改写藏书阁 —— 否则「我收藏的是什么」就查不出来了。
 *
 * <p>⚠️ 代价也要说清楚：图书库删掉一本书后，藏书阁里<b>仍会留着它</b>（快照还在）。
 * 想让它消失，用图书库操作列的「移除藏书」。
 *
 * <h3>为什么不存正文</h3>
 * {@code books} 里带几千字的 {@code content} / {@code rawContent}，藏书阁只用来「看」，
 * 用不上它们 —— 存了只会让这张表和查询都变重。所以只快照下面这 9 个展示字段。
 */
@Data
@Document(collection = "shelf_books")
public class ShelfBook implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Mongo 主键 */
    @Id
    private String id;

    /**
     * 图书 ID。★ 唯一索引 {@code uk_bookId} 建在它上面 ——
     * 同一本书在藏书阁里只能有一行（重复添加是幂等的，不是新增一条）。
     */
    private String bookId;

    // ------------------------------------------------------------------
    // 图书快照（加入藏书阁那一刻的值）
    // ------------------------------------------------------------------

    /** 书名 */
    private String title;

    /** 作者（可能是「作者 + 译者/编者」） */
    private String author;

    /** 封面 URL。实测 CDN 无防盗链，可直连 */
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
    // 藏书阁自己的字段
    // ------------------------------------------------------------------

    /**
     * 加入藏书阁的时间。
     *
     * <p>当前列表<b>不按它排</b>（按评分，见 {@code ShelfBookRepository.pageBooks}）——
     * 因为迁移进来的 4400 本 {@code addedAt} 是同一时刻，按它排等于随机序。
     * 留着这个字段是为了以后做「最近加入」视图 / 排序。
     */
    private Date addedAt;
}
