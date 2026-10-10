package com.bookcollector.shelf.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 藏书阁的一个一级类目（含该类目下的图书数量）。
 *
 * <p>数量是「分类导航」上显示的角标。它由 {@code shelf_books} 聚合得出，
 * <b>不是</b>写死的常量 —— 加书 / 移除书之后，数字自动跟着变。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "书架的一个一级类目")
public class ShelfCategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "一级类目名，如 文学")
    private String name;

    @Schema(description = "该类目下的图书数量")
    private long count;
}
