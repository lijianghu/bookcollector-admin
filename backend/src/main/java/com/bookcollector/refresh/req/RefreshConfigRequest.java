package com.bookcollector.refresh.req;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 定时刷新配置修改请求。
 *
 * <p>两个字段都是<b>可选</b>：{@code null} 表示「不改这一项」。
 * 所以「只切启用开关」不需要把 cron 一起传回来 ——
 * 传回来的话，前端就得保证它手里的 cron 和后端当前值一致，
 * 一旦页面上那份是旧的，就会把用户刚改的 cron 悄悄覆盖掉。
 */
@Schema(description = "定时刷新配置修改请求（两个字段都可选，null = 不改）")
public class RefreshConfigRequest {

    @Schema(description = "cron 表达式（6 段式：秒 分 时 日 月 周）", example = "0 0 3 * * ?")
    private String cron;

    @Schema(description = "是否启用定时触发。「立即执行」不受它影响")
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
