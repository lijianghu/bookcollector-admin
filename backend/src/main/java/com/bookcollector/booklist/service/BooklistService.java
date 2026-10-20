package com.bookcollector.booklist.service;

import com.bookcollector.book.entity.Book;
import com.bookcollector.booklist.entity.Booklist;
import com.bookcollector.booklist.req.BooklistQuery;
import com.bookcollector.booklist.vo.BooklistOptionVO;
import com.bookcollector.common.PageResult;

import java.util.List;

/**
 * 书单的<b>读</b>服务 —— 书单配置页的列表与分类清单。
 *
 * <h3>为什么与 {@link BooklistSyncService} 分成两个接口</h3>
 * 两者一个读一个写、生命周期完全不同：本接口是<b>无状态</b>的普通查询，
 * 那个是<b>有状态</b>的长任务（持有互斥位、进度快照、自己的线程池）。
 * 合在一起会让「查询」也被迫依赖一个带后台线程的 bean，
 * 而且 {@code BooklistSyncServiceImpl} 的类注释里那些取舍（互斥、进度不落库）
 * 与查询毫无关系，读的人要在两件不相干的事之间来回跳。
 *
 * <p>写侧（链路 B：按书单采书、回填书单名称）也归 {@link BooklistSyncService} 那一族，
 * 本接口<b>只读</b>，不产生任何副作用。
 */
public interface BooklistService {

    /**
     * 分页 + 多条件筛选。条件与排序规则见 {@link BooklistQuery}。
     *
     * <p>{@code query} 为 {@code null} 时按默认条件（无筛选、收藏人数降序、第 1 页 20 条）返回。
     */
    PageResult<Booklist> page(BooklistQuery query);

    /**
     * 按<b>书单 ID</b> 分页查这本书单里的书（2026-10-16 新增，书单详情弹窗用）。
     *
     * <p>匹配的是 {@code books.bookLists.bookListId}（书单引用数组），
     * 排序口径与图书库列表默认一致：评分降序 + {@code _id} 兜底。
     *
     * @param booklistId 书单 ID；空/空白按「查不到数据」处理而不是抛错，
     *                   与列表页其它查询「非法值筛 0 条」的读取侧口径一致
     * @param page 页码，从 1 开始；null/{@code <1} 归一到 1
     * @param size 每页条数；null/{@code <1} 归一到默认 20，上限 200（见 {@code PageQueryUtil}）
     */
    PageResult<Book> pageBooks(String booklistId, Integer page, Integer size);

    /**
     * 全部 16 个平台分类（书单配置页顶部要平铺出来的那份清单）。
     *
     * <p>由 {@code BooklistCategories} 提供，是<b>业务事实</b>而不是「从接口响应里读的」——
     * 接口响应的 {@code categories[]} 随请求而变（抓包样例里只有 15 个），
     * 拿它当遍历源会静默漏掉分类。
     *
     * <p>返回<b>不可变</b>列表，调用方不要试图改它。
     */
    List<Booklist.PlatformCategory> categories();

    /**
     * 全量书单的「ID + 名称」清单（图书库页「书单」筛选下拉用，2026-10-16）。
     *
     * <p>刻意<b>不分页</b>：下拉要的就是全量。只投影两个字段，228 条也就几十 KB。
     */
    List<BooklistOptionVO> options();

    /**
     * 手工新增一张书单（2026-10-20）。
     *
     * <p>只写 {@code booklistId} 与可选的 {@code name}；书籍数、收藏人数等由后续
     * 「同步」采集回填。同一 {@code booklistId} 已存在时抛业务异常。
     */
    Booklist create(String booklistId, String name);
}
