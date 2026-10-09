package com.bookcollector.publish.req;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI 发布配置修改请求。
 *
 * <p>两个字段都是<b>可选</b>：{@code null} 表示「不改这一项」——
 * 同 {@code RefreshConfigRequest} 的取舍：只切启用开关时不必把 cron 一起传回来，
 * 否则前端手里那份旧 cron 会把用户刚改的值悄悄覆盖掉。
 */
@Schema(description = "AI 发布配置修改请求（两个字段都可选，null = 不改）")
public class PublishConfigRequest {

    @Schema(description = "cron 表达式（6 段式：秒 分 时 日 月 周）", example = "0 0 4 * * ?")
    private String cron;

    @Schema(description = "是否启用定时触发。「立即生成并发布」不受它影响")
    private Boolean enabled;

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
