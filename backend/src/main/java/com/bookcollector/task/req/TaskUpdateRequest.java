package com.bookcollector.task.req;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 编辑任务请求。
 *
 * <p>只有四个字段可改，且<b>没有</b> {@code targetType} / {@code targetId} ——
 * 目标不能改（它是游标的归属键）。这个类刻意不提供那两个字段，
 * 让「不能改」这件事在类型层面就成立，而不是靠 Service 里的一句 if 判断。
 */
@Schema(description = "编辑任务请求（只改传了的字段）")
public class TaskUpdateRequest {

    @Schema(description = "任务名")
    private String name;

    @Schema(description = "最大页数。0 = 不限制")
    private Integer maxPages;

    /**
     * 排除分类 code 列表。
     *
     * <p>⚠️ <b>null 与空数组语义不同</b>：{@code null} = 不改；
     * <b>空数组 = 清空排除</b>。前端把多选框清空后提交的就是空数组，
     * 那是用户的真实意图（「我不排除了」），不能当成「没传」忽略掉。
     * 这条约定和 {@code name} / {@code remark} 那种「空串 = 不改」不一样。
     */
    @Schema(description = "排除分类 code 列表。null = 不改，空数组 = 清空排除")
    private List<String> excludeCategories;

    @Schema(description = "备注")
    private String remark;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(Integer maxPages) {
        this.maxPages = maxPages;
    }

    public List<String> getExcludeCategories() {
        return excludeCategories;
    }

    public void setExcludeCategories(List<String> excludeCategories) {
        this.excludeCategories = excludeCategories;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
