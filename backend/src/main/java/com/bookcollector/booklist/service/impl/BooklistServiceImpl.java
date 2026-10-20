package com.bookcollector.booklist.service.impl;

import com.bookcollector.book.entity.Book;
import com.bookcollector.book.repository.BookRepository;
import com.bookcollector.booklist.BooklistCategories;
import com.bookcollector.booklist.entity.Booklist;
import com.bookcollector.booklist.repository.BooklistRepository;
import com.bookcollector.booklist.req.BooklistQuery;
import com.bookcollector.booklist.service.BooklistService;
import com.bookcollector.booklist.vo.BooklistOptionVO;
import com.bookcollector.common.BizException;
import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;

import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * {@link BooklistService} 的实现 —— 一层薄转发。
 *
 * <p>看着像多余的中间层，但项目里所有读路径都长这样
 * （{@code BookServiceImpl#page} / {@code ReadingListServiceImpl}），
 * 保持一致比省一个类重要：以后要加「列表里顺带算点什么」时，
 * 有这层不用改 Controller 的签名。
 */
@Service
public class BooklistServiceImpl implements BooklistService {

    private final BooklistRepository repository;
    private final BookRepository bookRepository;

    public BooklistServiceImpl(BooklistRepository repository, BookRepository bookRepository) {
        this.repository = repository;
        this.bookRepository = bookRepository;
    }

    @Override
    public PageResult<Booklist> page(BooklistQuery query) {
        return repository.pageQuery(query);
    }

    @Override
    public PageResult<Book> pageBooks(String booklistId, Integer page, Integer size) {
        return bookRepository.pageByBooklistId(booklistId, page, size);
    }

    @Override
    public List<Booklist.PlatformCategory> categories() {
        return BooklistCategories.all();
    }

    @Override
    public List<BooklistOptionVO> options() {
        return repository.findOptions();
    }

    @Override
    public Booklist create(String booklistId, String name) {
        String id = booklistId == null ? "" : booklistId.trim();
        if (id.isEmpty()) {
            throw new BizException(ResultBean.code_warn, "书单 ID 不能为空");
        }
        if (repository.findByBooklistId(id) != null) {
            throw new BizException(ResultBean.code_warn, "书单已存在：" + id);
        }
        Date now = new Date();
        Booklist booklist = Booklist.builder()
                .booklistId(id)
                .name(name == null || name.trim().isEmpty() ? null : name.trim())
                .collectStatus("PENDING")
                .bookCountCollected(0)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
        return repository.save(booklist);
    }
}
