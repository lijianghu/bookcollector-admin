package com.bookcollector.publish.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 模型配置（集合 {@code ai_model}）—— <b>由用户手工维护，程序只读</b>。
 *
 * <h3>读取规则</h3>
 * 筛 {@code type = "ai_model"} 且 {@code enable = true} → 按 {@code sort} 正序 → <b>取第一条</b>。
 * 用户 2026-10-09 明确：「只会有一个 true，如果有多个，自动挑选第一个，sort 正序」。
 *
 * <h3>为什么 {@code baseUrl} 三个字段就能驱动任意模型</h3>
 * {@code baseUrl} + {@code apiKey} + {@code model} 正好是 <b>OpenAI 兼容协议</b>的三要素，
 * 而 DeepSeek 的接口就是 OpenAI 兼容格式（{@code POST {baseUrl}/chat/completions}
 * + {@code Authorization: Bearer}）。所以将来在表里插一行别的兼容服务、改个 sort
 * 就能切换，代码不用动。
 *
 * <h3>⚠️ 字段名是 {@code enable}（无 d）</h3>
 * 这是用户建表时定的。注意它和 {@code ai_taxonomy_config} 的 {@code enabled}（有 d）
 * <b>不一样</b> —— 写错了就是「静默读不到」，两张表各按各的来。
 *
 * <h3>🔴 {@code apiKey} 是敏感信息</h3>
 * 加了 {@link ToString#Exclude}：{@code @Data} 生成的 {@code toString()} 会带上所有字段，
 * 一旦有人顺手写 {@code log.info("配置：{}", aiModel)}，key 就进日志了。
 * 排除之后这类误用也不会泄露 —— 纵深防御。日志里只允许打 name / model / baseUrl。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_model")
public class AiModel implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 固定为 {@code "ai_model"}（表里可能还有别的 type，读的时候要筛） */
    private String type;

    /** 展示名，如 "DeepSeek" */
    private String name;

    /** ★ 敏感：API Key。绝不进日志、绝不回传前端 */
    @ToString.Exclude
    private String apiKey;

    /** 模型名，如 "deepseek-flash" / "deepseek-v4-pro" */
    private String model;

    /** 服务地址，如 "https://api.deepseek.com"（代码里会 trim 掉尾部斜杠） */
    private String baseUrl;

    /** 是否启用。同一时刻只应有一行为 true */
    private Boolean enable;

    /** 排序号，从 1 开始。多条 enable=true 时取最小的那条 */
    private Integer sort;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
