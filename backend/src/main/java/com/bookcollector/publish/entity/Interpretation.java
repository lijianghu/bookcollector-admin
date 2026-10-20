package com.bookcollector.publish.entity;

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
 * 候选清单 + 解读记录（集合 {@code book_interpretations}）。
 *
 * <h3>它承担两件事</h3>
 * <ol>
 *   <li><b>候选清单</b>：初始化时把 {@code books} 里符合条件的书灌进来，{@code status = PENDING}；</li>
 *   <li><b>解读记录</b>：被挑中后依次变成 GENERATING → PUBLISHED / FAILED，并记录
 *       本地文件路径、语雀链接、失败原因等。</li>
 * </ol>
 * 之所以合成一张表：挑书只需要查这几十~几百条，而不是每次全表扫 {@code books}（几万条）。
 * 这是用户 2026-10-09 定的优化方案。
 *
 * <h3>🔴 写入是「books 文档的完整快照」，不是实体路径</h3>
 * 用户要求「快照所有字段，除了 {@code _class}」。所以初始化时用
 * {@code MongoTemplate} + {@code Document} 做原始 BSON 拷贝（再 {@code remove("_class")}），
 * <b>不</b>走实体序列化 —— 那样才能真正做到「所有字段」，且将来 {@code books} 加字段自动跟随。
 *
 * <p>因此本实体只声明<b>程序真正要用的字段</b>：写入时它不参与，
 * 读取时 Spring Data 会忽略文档里那些没声明的字段（这正是我们要的）。
 *
 * <p>⚠️ 也因为走 Document 路径，{@code @CreatedDate} / {@code @LastModifiedDate}
 * <b>不生效</b>（审计只在实体 save 时填充），所以这里不加审计注解，
 * {@link #createdAt} / {@link #updatedAt} 由代码手工维护 —— 加了注解反而误导。
 *
 * <h3>字段名与 {@code books} 对齐</h3>
 * {@code title} / {@code author} / {@code category} / {@code newRating} / {@code newRatingCount}
 * 用的都是 {@code books} 里的原名（不是 {@code bookTitle} 之类），因为这份文档就是它的快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "book_interpretations")
public class Interpretation implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 保留 {@code books} 的原 {@code _id}，真·快照 */
    @Id
    private String id;

    // ------------------------------------------------------------------
    // 以下字段来自 books 快照（同名字段，供页面展示与挑书排序）
    // ------------------------------------------------------------------

    /** 图书 ID。★ 唯一索引 uk_bookId 建在这个字段上（去重 + 防重复解读） */
    private String bookId;

    /** 书名 */
    private String title;

    /** 作者 */
    private String author;

    /** 分类的字符串形式，如 "精品小说-社会小说"（等价于 categories[0].title） */
    private String category;

    /**
     * 官方简介。
     *
     * <p>声明它是因为生成时要把它喂给模型 —— 库里<b>没有正文</b>，
     * 简介是唯一一段真实原文，带上它能明显提升解读的准确度
     * （见 {@code InterpretationPrompt.buildUser}）。
     *
     * <p>⚠️ 它同样来自 {@code books} 快照（同名 {@code intro}），
     * 不是我们另外去查了一次 {@code books}。
     */
    private String intro;

    /** 推荐值 0~1000（913 = 91.3%） */
    private Integer newRating;

    /** 评价人数。挑书时按它降序取第一条 */
    private Integer newRatingCount;

    /** 阅读人数（2026-10-13 加：列表展示 + 可排序） */
    private Integer readingCount;

    /**
     * 评分详情：{@code {good, fair, poor, recent, title}}（2026-10-13 加）。
     *
     * <p>列表要展示「好评数 / 中评数 / 差评数」，就是这里的 {@code good} / {@code fair} / {@code poor}。
     *
     * <p>⚠️ 声明成 {@code Map<String, Object>} 而不是一个具体的嵌套类型，有两个原因：
     * <ol>
     *   <li>{@code publish} 与 {@code book} 两个包<b>刻意互不 import</b>
     *       （见 {@code InterpretationQuery} 的类注释），而 {@code RatingDetail}
     *       定义在 {@code book.entity.Book} 里；</li>
     *   <li>这个子文档是从 {@code books} 原样快照过来的，<b>可能带 {@code _class}</b>
     *       （顶层那个被 {@code remove} 了，嵌套的不一定）。用一个同名的本地类型去映射它，
     *       等于赌 Spring Data 的类型解析行为 —— 而 {@code Map} 没有这个问题。</li>
     * </ol>
     * 取用时在 {@code InterpretationVO.from()} 里做安全转换（{@code null} / 非数字都兜住）。
     */
    private Map<String, Object> newRatingDetail;

    // ------------------------------------------------------------------
    // 解读状态与产物
    // ------------------------------------------------------------------

    /** 取值见 {@link PublishStatus}。挑书只认 {@code "PENDING"} */
    private String status;

    /** 进入候选清单的时间 */
    private Date initializedAt;

    /** 本地 Markdown 文件路径（落盘后写入） */
    private String localPath;

    /**
     * 合规后的正文（已做敏感词替换）。发布到语雀的就是它。
     *
     * <p>⚠️ 列表接口<b>不返回</b>这个字段（几千字 × 几百条太重），
     * 分页查询走 {@code InterpretationVO} 收敛。
     */
    private String content;

    /** AI 原始输出（替换敏感词之前），留档备查 */
    private String rawContent;

    /** 本次命中的敏感词列表（原词），用于事后核对替换范围 */
    private List<String> sensitiveHits;

    /** 语雀文档 ID（建文档接口返回的 {@code data.id}） */
    private Long yuqueDocId;

    /** 语雀文档 URL */
    private String yuqueUrl;

    /** 本次使用的模型名快照，如 "deepseek-flash" */
    private String model;

    /** 输入 token 数（接口返回的 usage.prompt_tokens，可能为 null） */
    private Integer promptTokens;

    /** 输出 token 数（接口返回的 usage.completion_tokens，可能为 null） */
    private Integer completionTokens;

    /** 失败原因。成功时为 null */
    private String errorMsg;

    /** 触发方式：MANUAL（手动）/ SCHEDULED（定时） */
    private String trigger;

    /** 本次生成的开始时间 */
    private Date startedAt;

    /** 本次生成的结束时间（无论成败） */
    private Date finishedAt;

    private Date createdAt;

    private Date updatedAt;
}
