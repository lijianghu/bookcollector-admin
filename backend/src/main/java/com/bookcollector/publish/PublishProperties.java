package com.bookcollector.publish;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AI 书籍解读的配置。对应 {@code application.yml} 的 {@code bookcollector.publish.*}。
 *
 * <h3>为什么这些能配、而「分类白名单」不能配</h3>
 * 分类白名单是<b>用户手工维护的数据</b>（{@code ai_taxonomy_config} 表），
 * 而这里放的是<b>代码行为的参数</b>（超时、批大小、输出目录、语雀坐标）。
 * 后者改一次就不动了，写进 yml 比建表更省事。
 *
 * <h3>⚠️ 语雀坐标写在这里而不是页面上</h3>
 * {@code yuqueNamespace} / {@code yuqueParentUuid} 指向「读书笔记 / 书籍解读」这一个固定位置。
 * 同 {@code RefreshTarget.ALL} 的取舍：做成可配置的话，改坏了只会表现为
 * 「发布成功了但目录里找不到」—— 这种静默失败最难查。
 *
 * <p>🔴 本类<b>不含</b>任何密钥。{@code apiKey} 在 {@code ai_model} 表、
 * 语雀 token 在 {@code ai_yuque_token} 表，两者都由 {@code AiConfigRepository} 运行时读取。
 */
@Component
@ConfigurationProperties(prefix = "bookcollector.publish")
public class PublishProperties {

    /**
     * 本地 Markdown 的输出目录。
     *
     * <p>⚠️ {@code spring-boot:run} fork 出来的 JVM <b>工作目录是 {@code backend/}</b>
     * （见 {@code pom.xml} 里 {@code -DLOG_DIR} 那段注释），所以这里写 {@code ../docs}
     * 才能落到项目根的 {@code docs/}。pom 里同时注入了 {@code -DPUBLISH_DIR}，
     * yml 用 {@code ${PUBLISH_DIR:../docs}} 兜底，换机器不会废。
     */
    private String outputDir = "../docs";

    /** 调 AI 接口的连接超时（毫秒） */
    private int aiConnectTimeout = 10000;

    /**
     * 调 AI 接口的读超时（毫秒）。
     *
     * <p>默认 180 秒。生成一篇解读要 30~120 秒（带思考更久），
     * 而 {@code RestTemplate} 默认<b>无限等</b> —— 不显式设的话，
     * 对端一旦不回，生成线程会一直挂在那里，页面永远显示「生成中」。
     */
    private int aiReadTimeout = 180000;

    /** 单次生成的最大输出 token 数。一篇解读约 3~5 千字，8192 足够 */
    private int aiMaxTokens = 8192;

    /** 语雀 API 根地址。官方 OpenAPI 的 servers 是 https://www.yuque.com */
    private String yuqueBaseUrl = "https://www.yuque.com/api/v2";

    /** 语雀知识库 namespace（团队 login / 知识库 slug） */
    private String yuqueNamespace = "timesaving/met7rq";

    /** 「书籍解读」目录节点的 UUID。新文档会被挂到这个节点下面 */
    private String yuqueParentUuid = "lTQh6UTDXpfRIY3K";

    /**
     * 「高质量」的评级档位白名单。
     *
     * <p>对应 {@code books.newRatingTitle}。用户 2026-10-09 圈定的 5 档。
     * ⚠️ 查的是<b>顶层字段</b> {@code newRatingTitle}（由 {@code newRatingDetail.title}
     * 拍平而来），不是 {@code newRatingDetail.title} —— 路径写错会静默返回 0 条。
     */
    private List<String> ratingTitles = new ArrayList<String>(Arrays.asList(
            "神作", "好评如潮", "神作潜力", "脍炙人口", "值得一读"));

    /** 「高质量」的评价人数下限（严格大于）。用户 2026-10-09 定的是 500 */
    private int minRatingCount = 500;

    /** 初始化候选时一次 bulkWrite 的批大小 */
    private int initBatchSize = 500;

    // ==================================================================

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public int getAiConnectTimeout() {
        return aiConnectTimeout;
    }

    public void setAiConnectTimeout(int aiConnectTimeout) {
        this.aiConnectTimeout = aiConnectTimeout;
    }

    public int getAiReadTimeout() {
        return aiReadTimeout;
    }

    public void setAiReadTimeout(int aiReadTimeout) {
        this.aiReadTimeout = aiReadTimeout;
    }

    public int getAiMaxTokens() {
        return aiMaxTokens;
    }

    public void setAiMaxTokens(int aiMaxTokens) {
        this.aiMaxTokens = aiMaxTokens;
    }

    public String getYuqueBaseUrl() {
        return yuqueBaseUrl;
    }

    public void setYuqueBaseUrl(String yuqueBaseUrl) {
        this.yuqueBaseUrl = yuqueBaseUrl;
    }

    public String getYuqueNamespace() {
        return yuqueNamespace;
    }

    public void setYuqueNamespace(String yuqueNamespace) {
        this.yuqueNamespace = yuqueNamespace;
    }

    public String getYuqueParentUuid() {
        return yuqueParentUuid;
    }

    public void setYuqueParentUuid(String yuqueParentUuid) {
        this.yuqueParentUuid = yuqueParentUuid;
    }

    public List<String> getRatingTitles() {
        return ratingTitles;
    }

    public void setRatingTitles(List<String> ratingTitles) {
        this.ratingTitles = ratingTitles;
    }

    public int getMinRatingCount() {
        return minRatingCount;
    }

    public void setMinRatingCount(int minRatingCount) {
        this.minRatingCount = minRatingCount;
    }

    public int getInitBatchSize() {
        return initBatchSize;
    }

    public void setInitBatchSize(int initBatchSize) {
        this.initBatchSize = initBatchSize;
    }
}
