package com.bookcollector.publish.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语雀「创建文档」接口的返回。
 *
 * <p>对应官方 OpenAPI 的 {@code V2DocDetail}，只取程序要用的四个字段：
 * {@code id}（挂目录时必须用）、{@code slug} / {@code title}（展示）、
 * {@code url}（拼好的可点链接）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class YuqueDoc {

    /** 文档 ID（int64）。挂到目录时传给 {@code doc_ids} */
    private Long id;

    /** 文档路径 */
    private String slug;

    /** 文档标题 */
    private String title;

    /** 可访问的完整 URL */
    private String url;
}
