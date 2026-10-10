package com.bookcollector.shelf.service;

import com.bookcollector.common.PageResult;
import com.bookcollector.shelf.vo.ShelfBookVO;
import com.bookcollector.shelf.vo.ShelfCategoryVO;

import java.util.List;
import java.util.Set;

/**
 * 藏书阁服务。
 *
 * <h3>2026-10-10：从只读变可写</h3>
 * 第一版只有两个只读方法（类目 / 取书）。现在藏书阁是用户自己的一张书单，
 * 所以加了「加入 / 移除 / 批量查状态」三个方法，并把取书改成分页。
 *
 * <p>「取书」仍只按<b>一级类目</b>筛 —— 类目导航是一级的（见 {@link ShelfCategoryVO}）。
 */
public interface ShelfService {

    /** 一级类目 + 数量，按数量降序 */
    List<ShelfCategoryVO> listCategories();

    /**
     * 分页取书（一次 30 条，供前端下滑加载）。
     *
     * @param category 一级类目名。{@code null} / 空 = 不筛，取全部。
     *                 也接受带二级的完整名（{@code "文学-经典作品"}），内部会截到一级
     * @param page     页码，从 1 开始
     * @param size     每页条数。{@code null} / {@code < 1} 用默认值，超过上限会被夹住
     */
    PageResult<ShelfBookVO> pageBooks(String category, Integer page, Integer size);

    /**
     * 把一本书加进藏书阁（从 {@code books} 取快照）。
     *
     * @return {@code true} = 新加入；{@code false} = 本来就在（幂等，不报错）
     * @throws com.bookcollector.common.BizException 图书库里没有这个 bookId
     */
    boolean add(String bookId);

    /**
     * 从藏书阁移除一本书。
     *
     * @return {@code true} = 确实删掉了；{@code false} = 本来就不在（幂等，不报错）
     */
    boolean remove(String bookId);

    /**
     * 批量查「哪些书已在藏书阁」。图书库列表用它决定每行显示哪个按钮。
     *
     * @param bookIds 待查的图书 ID（一般是当前页）；{@code null} / 空 → 返回空集
     */
    Set<String> statusOf(List<String> bookIds);
}
