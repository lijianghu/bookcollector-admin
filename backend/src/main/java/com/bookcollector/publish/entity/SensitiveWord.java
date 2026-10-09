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
import java.util.List;

/**
 * 敏感词与合规替换词（集合 {@code ai_sensitive_words}）——
 * <b>由用户手工维护，程序只读</b>。
 *
 * <h3>{@link #replacement} 为什么是数组而不是单个字符串</h3>
 * 用户给的词表里大量是「两个候选」的形式（如 {@code 极权主义 → 绝对权力 / 权力异化}）。
 * 存成单字符串的话，替换后会得到「绝对权力 / 权力异化」这种带斜杠的怪句子。
 * 所以存数组，替换时<b>随机取一个</b> —— 避免整篇文章反复出现同一个词，读起来自然些。
 *
 * <h3>🔴 替换必须按 {@code word.length()} 降序</h3>
 * 词表里存在真实的前缀关系，顺序错了会替换出半截词：
 * <pre>
 *   先替「极权」(2 字)  → 「极权主义」变成「绝对权力主义」
 *   先替「斯大林」(3 字) → 「苏联斯大林时代」变成「苏联特定历史人物时代」
 * </pre>
 * 所以替换前先按词长降序排，与 {@link #sort}（纯展示顺序）无关。
 *
 * <h3>为什么不需要多轮替换</h3>
 * 已逐条核对过：现有的 18 条里没有任何一个 replacement 会再命中另一个 word
 * （唯一擦边的是「背叛者」含「背叛」，但「背叛」不在表里）。所以一遍扫描即可。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_sensitive_words")
public class SensitiveWord implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 原词（会被替换掉的那个） */
    private String word;

    /** 合规替换词候选。<b>随机取一个</b>；为空时该条不生效 */
    private List<String> replacement;

    /** 是否启用。{@code null} 视为启用（只有显式 false 才跳过） */
    private Boolean enabled;

    /** 展示用排序号 */
    private Integer sort;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
