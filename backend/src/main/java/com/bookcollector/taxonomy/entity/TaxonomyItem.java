package com.bookcollector.taxonomy.entity;

import com.bookcollector.common.enums.TargetType;
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

/**
 * 采集目标字典 —— 一级分类 21 个 + 二级分类（清单见 {@code config.SeedRunner}）+ 榜单 7 个。
 *
 * <p>数据来源是原 {@code config.py} 的 {@code CATEGORIES} / {@code RANKING_LISTS}，
 * 由 {@code SeedRunner} 在启动时幂等写入（已存在则保留用户改过的 {@code enabled}）。
 *
 * <p><b>为什么字典要落库而不是写死在代码里</b>：前端需要一个「可选目标列表」，
 * 而且以后想停掉某个分类（{@code enabled=false}）不该改代码重新打包。
 *
 * <h3>🔴 一级与二级都是合法的采集目标</h3>
 * {@code targetId} 只是被原样拼进 {@code /bookListInCategory/{targetId}}
 * （见 {@code WereadClient#buildUrl}），传二级 ID 同样成立 —— 上游对<b>每个 ID</b>
 * 各自封顶 25 页（500 本），所以「历史」+「历史-历史读物」… 能把可采总量放大一个量级。
 * 层级只用 {@link #parentCode} 表示：{@code null} = 一级，非 null = 二级。
 *
 * <h3>⚠️ 但只有一级的 code 能当 {@code categoryId} 用</h3>
 * 图书库的「平台分类」筛选走的是 {@code BookQuery.categoryId}，它精确匹配
 * {@code bookInfo.categories[].categoryId}；而二级的 code 是 {@code subCategoryId}，
 * <b>是另一个字段</b>。把二级 code 传进去恒不匹配、静默返回 0 条 ——
 * 所以前端那个下拉只能列一级（见 {@code views/book/index.vue} 的 {@code categoryOptions}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "taxonomy_config")
public class TaxonomyItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 类型：CATEGORY / RANKING，取值见 {@link TargetType} */
    private String type;

    /**
     * 编码。CATEGORY 用数字字符串（"300000"），RANKING 用英文串（"rising"）。
     * 统一存字符串，因为要拼进 URL 且两类格式不同。
     */
    private String code;

    /** 展示名："文学" / "飙升榜"。二级分类统一用「一级-二级」形式，如 "历史-历史读物" */
    private String name;

    /**
     * 上级分类的 {@link #code}。<b>{@code null} = 一级分类</b>，非 null = 二级分类。
     *
     * <p>不存上级的 {@code _id} 而存 {@code code}：{@code code} 才是采集目标的身份
     * （任务的 {@code targetId}、游标主键都用它），而 {@code _id} 是 MongoDB 生成的、
     * 删掉重建就会变。前端拿 {@code code} 直接就能分组，不用再查一次库。
     */
    private String parentCode;

    /** 是否启用。停用的目标不出现在「新建任务」的可选项里 */
    private Boolean enabled;

    /** 排序号，从 1 开始，决定前端下拉框的顺序 */
    private Integer sort;

    /** 备注 */
    private String remark;

    /** seed 写入时间。由审计自动填充（SeedRunner 用 insertAll，走实体路径，审计生效） */
    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
