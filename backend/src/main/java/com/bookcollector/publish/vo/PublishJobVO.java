package com.bookcollector.publish.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * AI 书籍解读页面的<b>全部数据</b>，一个接口拿齐。
 *
 * <h3>为什么合成一个 VO</h3>
 * 同 {@code RefreshJobVO}：页面上它们是一个整体（配置区 + 操作区 + 进度 + 清单统计），
 * 拆成多个接口前端要处理「一个成功一个失败」的中间态，而数据量只有几 KB。
 *
 * <h3>🔴 这里<b>绝不含</b> {@code apiKey} / 语雀 {@code token}</h3>
 * 用户 2026-10-09 明确要求这两个是敏感信息，代码注释、日志、接口返回都不许带内容。
 * 所以模型与语雀只暴露「配没配 + 用的哪个模型 + 地址」，
 * 需要「是否已配置」的判断时用 {@link #modelConfigured} / {@link #yuqueConfigured} 这两个布尔。
 *
 * <h3>{@code initRunning} / {@code genRunning} 来自内存而不是 DB</h3>
 * 「是不是正在跑」的唯一权威是 {@code PublishServiceImpl} 里的 {@code AtomicBoolean}。
 * DB 里的状态只是快照，进程被杀之后就是假的。页面靠内存态决定要不要继续轮询。
 */
@Data
@Builder
@Schema(description = "AI 书籍解读页面全部数据")
public class PublishJobVO {

    // ==================================================================
    // 配置
    // ==================================================================

    @Schema(description = "cron 表达式（6 段式：秒 分 时 日 月 周）")
    private String cron;

    @Schema(description = "是否启用定时触发")
    private Boolean enabled;

    @Schema(description = "下一次触发时间。null 表示未启用或算不出")
    private Date nextFireTime;

    // ==================================================================
    // 分类白名单（只读展示，来自 ai_taxonomy_config）
    // ==================================================================

    @Schema(description = "当前生效的分类名，如 [\"文学\",\"历史\"]")
    private List<String> categoryNames;

    @Schema(description = "当前生效的分类数量")
    private Integer categoryCount;

    // ==================================================================
    // 模型 / 语雀（脱敏）
    // ==================================================================

    @Schema(description = "是否已配置可用的 AI 模型（ai_model 里有 enable=true 的行）")
    private Boolean modelConfigured;

    @Schema(description = "模型配置的展示名，如 DeepSeek")
    private String modelName;

    @Schema(description = "模型名，如 deepseek-flash")
    private String modelId;

    @Schema(description = "服务地址，如 https://api.deepseek.com")
    private String modelBaseUrl;

    @Schema(description = "是否已配置语雀 Token（ai_yuque_token 里有行）")
    private Boolean yuqueConfigured;

    // ==================================================================
    // 内存态
    // ==================================================================

    @Schema(description = "「初始化候选」是否正在执行")
    private Boolean initRunning;

    @Schema(description = "「生成并发布」是否正在执行")
    private Boolean genRunning;

    // ==================================================================
    // 初始化进度（内存快照，逐批更新）
    // ==================================================================

    @Schema(description = "已扫描的图书数")
    private Integer initScanned;

    @Schema(description = "真正插入清单的条数（bulkWrite 的 upsertedCount 累计）")
    private Integer initInserted;

    @Schema(description = "因已存在而跳过的条数（= 扫描数 − 插入数）")
    private Integer initSkipped;

    // ==================================================================
    // 生成进度（内存快照）
    // ==================================================================

    @Schema(description = "正在解读的书名")
    private String currentBookTitle;

    @Schema(description = "当前阶段：挑书 / 生成中 / 替换敏感词 / 写入本地 / 发布到语雀")
    private String currentPhase;

    @Schema(description = "当前阶段已耗时（毫秒）")
    private Long currentElapsedMs;

    @Schema(description = "最近一次执行的一句话总结")
    private String lastMessage;

    // ==================================================================
    // 清单统计
    // ==================================================================

    @Schema(description = "待解读条数")
    private Long pendingCount;

    @Schema(description = "生成中条数（进程被杀会残留，不做启动清理）")
    private Long generatingCount;

    @Schema(description = "已发布条数")
    private Long publishedCount;

    @Schema(description = "失败条数")
    private Long failedCount;

    @Schema(description = "清单总条数")
    private Long totalCount;
}
