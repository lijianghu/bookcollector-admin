package com.bookcollector.task.entity;

import com.bookcollector.common.enums.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 采集任务定义。原 Python 没有这个概念（它是一次性脚本），是本次改造新增的。
 *
 * <p>一个任务 = 一个「目标」（某分类 或 某榜单）+ 一个「最大页数」
 * + 一份可选的「排除分类」黑名单。
 * 任务本身只是配置，真正执行产生的是 {@link TaskRun}。
 *
 * <p><b>为什么把 targetName 冗余存一份</b>：任务列表页要显示「文学」「飙升榜」，
 * 如果不冗余，每行都要回查 {@code taxonomy_config}。字典是低频变更数据，
 * 冗余的维护成本远低于查询成本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "collect_tasks")
public class CollectTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 任务名，如「文学-前10页」 */
    private String name;

    /** 采集目标类型：CATEGORY / RANKING */
    private String targetType;

    /** 采集目标 ID："300000" / "rising" */
    private String targetId;

    /** 目标名称快照："文学" / "飙升榜" */
    private String targetName;

    /**
     * 最大页数。{@code 0} 或 {@code null} 表示不限制（一直翻到 hasMore=false）。
     * 注意：不限页数的分类（如文学有 54078 本、约 2704 页）会跑很久，默认给个有限值。
     */
    private Integer maxPages;

    /**
     * 排除分类 code 列表。命中的图书<b>不写库</b>。{@code null} / 空 = 不排除。
     *
     * <p>存的是分类 code（{@code taxonomy_config.code}），<b>一级与二级混存</b>：
     * 想整块不要就填一级（{@code "1000000"} = 个人成长），
     * 只想掐掉一支就填二级（{@code "1000007"} = 个人成长-女性成长）。
     * 匹配时两者都会跟 {@code Book.BookCategory} 的 {@code categoryId} /
     * {@code subCategoryId} 逐个比（见 {@code collector.BookCategoryFilter}）。
     *
     * <p>⚠️ <b>用 String 而不是 Integer</b>：字典的 code 本来就是字符串
     * （见 {@code TaxonomyItem.code}），而且以后混进榜单那种英文 code 也不用改类型。
     *
     * <p>⚠️ 判定口径是「<b>任一命中即跳过</b>」：一本书的 {@code categories} 数组里
     * 只要有<b>任意一个</b>标签命中这里，整本就不入库。实测库里 47492 本书全部带
     * {@code categories}，且一本常同时挂 2~4 个分类 —— 所以排除「女性成长」之后，
     * 一本同时属于「文学」和「女性成长」的书，在采「文学」时也会被跳过。
     * 这是与用户确认过的口径，不是 bug。
     */
    private List<String> excludeCategories;

    /** 任务状态，取值见 {@link TaskStatus} */
    private String status;

    /** 备注 */
    private String remark;

    /** 最近一次运行的 runId */
    private String lastRunId;

    /** 累计运行次数 */
    private Integer runCount;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
