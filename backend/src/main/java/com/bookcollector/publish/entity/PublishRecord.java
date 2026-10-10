package com.bookcollector.publish.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 发布记录（集合 {@code publish_record}）—— 每次成功发布到语雀留下一条。
 *
 * <h3>为什么与 {@code book_interpretations} 分开存</h3>
 * {@code book_interpretations} 是「候选清单 + 当前状态」，一本书<b>只有一行</b>
 * （{@code uk_bookId} 唯一索引），状态会被后续操作覆盖 ——
 * 重新生成一次，上一次的语雀链接就没了。
 *
 * <p>而「发布记录」要的是<b>流水账</b>：同一本书可以发布多次
 * （页面上的「重置为待解读」明确提示过「重新生成会在语雀里新增一篇」），
 * 每次都是一条独立记录。这是两个不同的事实，硬塞进一张表必然要
 * 「要么覆盖历史、要么破坏唯一索引」二选一。
 *
 * <h3>字段是「快照」而不是引用</h3>
 * 书名 / 作者 / 分类 / 推荐值都是<b>发布那一刻</b>的值。图书库里的记录后来被改过
 * （人工编辑）或被删过，都不该改写历史流水 —— 否则「当时发的是什么」就查不出来了。
 *
 * <h3>⚠️ 没有 VO，实体直出</h3>
 * 本项目一般允许实体直出（见 {@code backend/AGENTS.md}）。
 * {@code InterpretationVO} 之所以存在，是因为 {@code Interpretation} 带着
 * 几千字的 {@code content} / {@code rawContent}；本实体<b>没有大字段</b>
 * （正文只落本地文件和语雀，不进这张表），所以不另做 VO。
 */
@Data
@Document(collection = "publish_record")
public class PublishRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Mongo 主键。每次发布都是一条新记录，所以不做业务唯一键 */
    @Id
    private String id;

    // ------------------------------------------------------------------
    // 图书快照（发布那一刻的值）
    // ------------------------------------------------------------------

    /** 图书 ID */
    private String bookId;

    /** 书名 */
    private String title;

    /** 作者 */
    private String author;

    /** 分类的字符串形式，如 {@code "文学-经典作品"} */
    private String category;

    /** 推荐值 0~1000（913 = 91.3%） */
    private Integer newRating;

    // ------------------------------------------------------------------
    // 发布产物
    // ------------------------------------------------------------------

    /** 语雀文档 ID（建文档接口返回的 {@code data.id}） */
    private Long yuqueDocId;

    /** 语雀文档 URL */
    private String yuqueUrl;

    /** 本地 Markdown 文件路径 */
    private String localPath;

    /** 本次使用的模型名快照，如 {@code "deepseek-flash"} */
    private String model;

    /** 输入 token 数（接口没返回时为 null） */
    private Integer promptTokens;

    /** 输出 token 数（接口没返回时为 null） */
    private Integer completionTokens;

    /** 命中的敏感词个数 */
    private Integer sensitiveHitCount;

    /** 触发方式：{@code MANUAL}（手动）/ {@code SCHEDULED}（定时） */
    private String trigger;

    // ------------------------------------------------------------------
    // 时间
    // ------------------------------------------------------------------

    /**
     * 发布时间。★ 列表按它<b>倒序</b>，索引 {@code idx_publishedAt} 建在它上面。
     *
     * <p>取的是本次发布流程结束的时刻（与 {@code Interpretation.finishedAt} 同一个值）。
     */
    private Date publishedAt;

    /** 记录写入时间。当前与 {@link #publishedAt} 相同，留字段是为了以后补录历史时能区分 */
    private Date createdAt;
}
