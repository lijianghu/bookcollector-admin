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
 * 语雀访问令牌（集合 {@code ai_yuque_token}）—— <b>由用户手工维护，程序只读</b>。
 *
 * <h3>读取规则</h3>
 * 按 {@code sort} 正序 → <b>取第一条</b>。这张表<b>没有</b> {@code enable} 字段
 * （用户 2026-10-09 建表时就没建），所以不做启用判断。
 *
 * <h3>🔴 {@code token} 是敏感信息</h3>
 * 加了 {@link ToString#Exclude}。日志里只允许打「已加载语雀 Token（长度 N）」这种
 * 不含内容的形式；接口永不回传 token 本身。
 *
 * <p>令牌在语雀后台 https://www.yuque.com/settings/tokens 创建，
 * 请求时放在 {@code X-Auth-Token} 头里（见官方 OpenAPI 的 securitySchemes.authToken）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_yuque_token")
public class AiYuqueToken implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** ★ 敏感：语雀 Token。绝不进日志、绝不回传前端 */
    @ToString.Exclude
    private String token;

    /** 排序号，从 1 开始。多条时取最小的那条 */
    private Integer sort;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
