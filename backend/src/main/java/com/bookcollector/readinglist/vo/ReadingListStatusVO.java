package com.bookcollector.readinglist.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 「这本书在阅读清单里吗、什么状态」—— 批量查询的返回项。
 *
 * <h3>为什么返回 {@code {bookId, status}} 而不是只返回 {@code Set<String>}</h3>
 * 图书库 / 精选图书 / 我的藏书 三个页面既要判「在不在阅读清单」（决定按钮文案
 * 「加入」还是「移除」），也要显示<b>当前状态</b>（想读 / 在读 / 已读）。
 * 只回一个 bookId 集合只能答前半句。
 *
 * <p>（对比 {@code shelf} 的 {@code POST /status} 返回 {@code Set<String>}：
 * 藏书阁只有一个维度「在不在」，所以那样够了。）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "一本书的阅读清单状态")
public class ReadingListStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "图书 ID")
    private String bookId;

    @Schema(description = "阅读状态：WANT_TO_READ / READING / FINISHED")
    private String status;
}
