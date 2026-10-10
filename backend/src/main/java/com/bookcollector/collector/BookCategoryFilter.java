package com.bookcollector.collector;

import com.bookcollector.book.entity.Book;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 按分类排除图书 —— 采集主循环里「解析完、写库前」的那一道闸。
 *
 * <h3>判定口径：任一命中即跳过</h3>
 * 一本书的 {@code bookInfo.categories} 是<b>数组</b>，元素形如
 * {@code {categoryId: 300000, subCategoryId: 300007, title: "文学-文学鉴赏"}}。
 * 只要其中<b>任意一个</b>元素的 {@code categoryId} 或 {@code subCategoryId}
 * 命中排除集合，整本就被丢掉。
 *
 * <p>⚠️ <b>这个口径的代价是已知且已确认的</b>：实测库里 47492 本书全部带
 * {@code categories}，且一本常同时挂 2~4 个分类 —— 例如
 * 《脂粉帝国》同时属于 {@code 社会文化-文化} / {@code 文学-文学鉴赏} /
 * {@code 个人成长-女性成长}。所以排除 {@code 1000007}（女性成长）之后，
 * 这本书在采「文学」时也会被跳过。用户明确选了「任一命中即跳过」，
 * 因为那才符合「不想采女性成长的书」的直觉；另一种更保守的口径
 * （必须同时命中当前任务的目标）会让只挂女性成长的书漏进来。
 *
 * <h3>为什么单独一个类，而不是内联在 CollectLoop 里</h3>
 * {@link CollectLoop#run} 已经是一百多行的长方法，每一段都在讲一件事。
 * 过滤规则自己有一套（多标签、两级 code、空值保护），塞进去会让主循环
 * 从「分页采集的骨架」变成「骨架 + 一个业务规则」。
 *
 * <h3>为什么是 Spring bean 而不是静态工具方法</h3>
 * 它无状态，做成静态也行。但 {@code CollectLoop} 的依赖全是构造器注入的 bean，
 * 保持同一种风格，读的人不用在两种调用方式之间切换。
 */
@Component
public class BookCategoryFilter {

    /**
     * 过滤掉命中排除分类的图书。
     *
     * <p>不修改入参，返回新列表（可能<b>就是入参本身</b> —— 没有排除项时直接原样返回，
     * 不为一个恒等操作复制一遍 20 个元素的数组）。
     *
     * @param books         解析出来的图书，可为 null / 空
     * @param excludedCodes 排除分类 code（一级 / 二级混存），可为 null / 空
     * @return 保留下来的图书。顺序与入参一致
     */
    public List<Book> exclude(List<Book> books, Set<String> excludedCodes) {
        if (books == null || books.isEmpty()
                || excludedCodes == null || excludedCodes.isEmpty()) {
            return books;
        }
        List<Book> kept = new ArrayList<Book>(books.size());
        for (Book book : books) {
            if (!hit(book, excludedCodes)) {
                kept.add(book);
            }
        }
        return kept;
    }

    /**
     * 这本书有没有挂到任何一个被排除的分类上。
     *
     * <p>{@code categories} 缺失时返回 false（不拦）—— 没标签的书没有依据说它属于
     * 被排除的分类，宁可放进去让用户自己删，也不要静默丢掉。
     */
    private boolean hit(Book book, Set<String> excludedCodes) {
        List<Book.BookCategory> categories = book.getCategories();
        if (categories == null || categories.isEmpty()) {
            return false;
        }
        for (Book.BookCategory category : categories) {
            if (category == null) {
                continue;
            }
            // 一级：排除「个人成长 1000000」这种整块不要的情况
            Integer topId = category.getCategoryId();
            if (topId != null && excludedCodes.contains(String.valueOf(topId))) {
                return true;
            }
            // 二级：排除「个人成长-女性成长 1000007」这种只掐一支的情况
            Integer subId = category.getSubCategoryId();
            if (subId != null && excludedCodes.contains(String.valueOf(subId))) {
                return true;
            }
        }
        return false;
    }
}
