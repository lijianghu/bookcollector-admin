package com.bookcollector.readinglist.service;

import com.bookcollector.common.PageResult;
import com.bookcollector.readinglist.vo.ReadingListBookVO;
import com.bookcollector.readinglist.vo.ReadingListStatusVO;
import com.bookcollector.readinglist.vo.ReadingListSummaryVO;

import java.util.List;

/**
 * 阅读清单服务。
 *
 * <h3>它是什么</h3>
 * 「我打算读什么、读到哪了」—— 与藏书阁（{@code shelf}，我收藏了什么）<b>并列的另一个维度</b>。
 * 两者不可互相推导，也不许互相影响：本接口里没有任何方法会去动 {@code shelf_books}。
 *
 * <h3>允许未收藏的书直接进清单</h3>
 * {@link #add} 只要求这本书存在于 {@code books}，<b>不要求</b>它已在藏书阁。
 */
public interface ReadingListService {

    /**
     * 分页取书。
     *
     * @param status   阅读状态枚举名（{@code WANT_TO_READ} / {@code READING} / {@code FINISHED}）。
     *                 {@code null} / 空白 = 不筛（全部）
     * @param category 一级类目名。{@code null} / 空 = 不筛。也接受带二级的完整名，内部截到一级
     * @param title    书名关键词，<b>包含匹配</b>（模糊，大小写不敏感）
     * @param author   作者关键词，<b>包含匹配</b>（模糊，大小写不敏感）
     * @param page     页码，从 1 开始
     * @param size     每页条数。{@code null} / {@code < 1} 用默认值，超过上限会被夹住
     */
    PageResult<ReadingListBookVO> pageBooks(String status, String category, String title,
                                            String author, Integer page, Integer size);

    /** 各状态计数（给状态 Tab 的角标） */
    ReadingListSummaryVO summary();

    /**
     * 把一本书加进阅读清单（从 {@code books} 取快照）。
     *
     * @param status 初始状态枚举名。{@code null} / 空白 = {@code WANT_TO_READ}。
     *               传了非法值会抛 {@code BizException}（不静默降级）
     * @return {@code true} = 新加入；{@code false} = 本来就在（幂等，不报错）
     * @throws com.bookcollector.common.BizException 图书库里没有这个 bookId / 状态非法
     */
    boolean add(String bookId, String status);

    /**
     * 从阅读清单移除一本书。
     *
     * <p>⚠️ 只动 {@code reading_list} —— 这本书在藏书阁里的记录<b>不受影响</b>。
     *
     * @return {@code true} = 确实删掉了；{@code false} = 本来就不在（幂等，不报错）
     */
    boolean remove(String bookId);

    /**
     * 改阅读状态。
     *
     * @param status 目标状态枚举名，<b>必填</b>；非法值抛 {@code BizException}
     * @return {@code true} = 这本书现在处于目标状态（含「本来就相同」的幂等情形）；
     *         {@code false} = 它不在阅读清单里
     */
    boolean updateStatus(String bookId, String status);

    /**
     * 批量查「哪些书已在阅读清单、分别什么状态」。四个列表页都用它。
     *
     * @param bookIds 待查的图书 ID（一般是当前页）；{@code null} / 空 → 返回空列表
     */
    List<ReadingListStatusVO> statusOf(List<String> bookIds);
}
