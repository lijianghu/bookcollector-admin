package com.bookcollector.publish.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次 AI 生成的结果。
 *
 * <p>字段只保留程序要用的：正文 + token 用量 + 实际模型名。
 * 完整的原始响应<b>不</b>在这里留存（可能很大，也没有再看的价值）。
 *
 * <p>🔴 这里不会有任何密钥 —— 请求头不参与响应解析。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiCompletion {

    /** 模型输出的正文（Markdown） */
    private String content;

    /** 输入 token 数（usage.prompt_tokens，可能为 null） */
    private Integer promptTokens;

    /** 输出 token 数（usage.completion_tokens，可能为 null） */
    private Integer completionTokens;

    /** 实际使用的模型名（响应里的 model 字段） */
    private String model;
}
