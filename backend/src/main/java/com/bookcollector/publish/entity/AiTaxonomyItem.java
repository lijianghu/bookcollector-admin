package com.bookcollector.publish.entity;

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
 * AI 发布的<b>分类白名单</b>（集合 {@code ai_taxonomy_config}）——
 * <b>由用户手工维护，程序只读</b>。
 *
 * <h3>它和 {@code taxonomy_config} 的关系</h3>
 * 结构与采集字典 {@code com.bookcollector.taxonomy.entity.TaxonomyItem} 相同，
 * 数据由用户从那边复制过来 —— 但用户 2026-10-09 明确要求<b>新表对应新实体</b>，
 * 所以这里是独立的一份，不复用 {@code TaxonomyItem}。
 *
 * <h3>读取规则</h3>
 * 取 {@code enabled != false} 的行的 {@link #code}，作为分类白名单。
 * <b>不筛 {@code type}</b> —— 表名已经表达了语义，少一个条件就少一个失败点。
 *
 * <h3>🔴 为什么 {@code code} 能直接当 {@code categoryId} 用</h3>
 * 一级分类的 {@code code}（如 {@code "300000"}）就是微信读书
 * {@code bookInfo.categories[].categoryId}，所以挑书时直接
 * {@code Criteria.where("categories.categoryId").in(codes)} 即可，
 * <b>不需要「名字 → code」的映射</b>。
 *
 * <p>⚠️ 只有<b>一级</b>分类的 code 能这么用：二级的 code 是 {@code subCategoryId}，
 * 是另一个字段，传进去恒不匹配、静默返回 0 条。
 * （见 {@code TaxonomyItem} 的类注释。）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_taxonomy_config")
public class AiTaxonomyItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** CATEGORY / RANKING。本表只用 CATEGORY，但保留字段以对齐采集字典的结构 */
    private String type;

    /** 分类编码（数字字符串，如 "300000"）—— 就是 {@code categories[].categoryId} */
    private String code;

    /** 展示名，如 "文学"。仅用于页面显示「当前生效分类」 */
    private String name;

    /** 上级分类 code。{@code null} = 一级分类。本表只放一级，正常都是 null */
    private String parentCode;

    /** 是否参与 AI 发布。{@code null} 视为参与（只有显式 false 才排除） */
    private Boolean enabled;

    /** 排序号，决定页面上「当前生效分类」的展示顺序 */
    private Integer sort;

    /** 备注 */
    private String remark;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
