package com.bookcollector.shelf.service.impl;

import com.bookcollector.common.BizException;
import com.bookcollector.common.PageResult;
import com.bookcollector.shelf.entity.ShelfBook;
import com.bookcollector.shelf.repository.ShelfBookRepository;
import com.bookcollector.shelf.service.ShelfService;
import com.bookcollector.shelf.vo.ShelfBookVO;
import com.bookcollector.shelf.vo.ShelfCategoryVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 藏书阁服务实现。
 *
 * <p>「读」很薄：参数归一化后交给仓库。「写」多一层<b>取快照</b> ——
 * 加藏书时先回 {@code books} 读那本书，读不到就报「图书不存在」，
 * 而不是塞一条空壳进去。
 */
@Service
public class ShelfServiceImpl implements ShelfService {

    private static final Logger log = LoggerFactory.getLogger(ShelfServiceImpl.class);

    /** 默认每页 30 条 —— 前端一屏是 10 列 × 3 行 */
    private static final int DEFAULT_SIZE = 30;

    /** 上限。防 {@code size=99999} 把整表拉回来 */
    private static final int MAX_SIZE = 200;

    private final ShelfBookRepository repository;

    public ShelfServiceImpl(ShelfBookRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ShelfCategoryVO> listCategories() {
        return repository.listTopCategories();
    }

    @Override
    public PageResult<ShelfBookVO> pageBooks(String category, Integer page, Integer size) {
        String top = topCategoryOf(category);
        int p = normalizePage(page);
        int s = normalizeSize(size);
        int skip = (p - 1) * s;

        long total = repository.countBooks(top);
        List<ShelfBook> rows = repository.pageBooks(top, skip, s);

        List<ShelfBookVO> list = new ArrayList<ShelfBookVO>(rows.size());
        for (ShelfBook row : rows) {
            list.add(toVO(row));
        }
        return PageResult.of(list, total, p, s);
    }

    @Override
    public boolean add(String bookId) {
        String id = trimToNull(bookId);
        if (id == null) {
            throw BizException.warn("缺少图书 ID");
        }
        if (repository.exists(id)) {
            return false;
        }

        ShelfBook snapshot = repository.readBookSnapshot(id);
        if (snapshot == null) {
            throw BizException.notFound("图书不存在：" + id);
        }
        // 读自 books，_id 是那本书的 ObjectId —— 清掉，让 Mongo 给藏书阁这条生成新 _id
        snapshot.setId(null);
        snapshot.setBookId(id);
        snapshot.setAddedAt(new Date());

        try {
            repository.insert(snapshot);
            return true;
        } catch (DuplicateKeyException e) {
            // 并发下两次「添加」撞上 uk_bookId：后到的那次当成「已存在」，不报错
            log.debug("添加藏书命中唯一键，视为已存在：{}", id);
            return false;
        }
    }

    @Override
    public boolean remove(String bookId) {
        String id = trimToNull(bookId);
        if (id == null) {
            throw BizException.warn("缺少图书 ID");
        }
        return repository.remove(id) > 0;
    }

    @Override
    public Set<String> statusOf(List<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return new HashSet<String>();
        }
        return repository.findOnShelf(bookIds);
    }

    // ------------------------------------------------------------------
    // 归一化
    // ------------------------------------------------------------------

    /**
     * 把传入的类目名截成一级；{@code null} / 空 / 只有空白 → {@code null}（= 不筛，取全部）。
     *
     * <p>{@code "文学-经典作品"} → {@code "文学"}；{@code "文学"} → {@code "文学"}。
     * 这样前端无论从字典里拿到的是「一级」还是「二级」，都不会出现
     * 「传了但查不到、还不报错」的静默空结果。
     */
    private String topCategoryOf(String category) {
        String v = trimToNull(category);
        if (v == null) {
            return null;
        }
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }

    /** 页码夹到 {@code >= 1} */
    private int normalizePage(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    /** size 夹在 [1, {@link #MAX_SIZE}]；不合法用默认值 */
    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return size > MAX_SIZE ? MAX_SIZE : size;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    /** 实体 → 对外 VO。只暴露展示用的 9 个字段，不吐内部 {@code _id} / {@code addedAt} */
    private ShelfBookVO toVO(ShelfBook row) {
        ShelfBookVO vo = new ShelfBookVO();
        vo.setBookId(row.getBookId());
        vo.setTitle(row.getTitle());
        vo.setAuthor(row.getAuthor());
        vo.setCover(row.getCover());
        vo.setCategory(row.getCategory());
        vo.setNewRating(row.getNewRating());
        vo.setNewRatingCount(row.getNewRatingCount());
        vo.setReadingCount(row.getReadingCount());
        vo.setIntro(row.getIntro());
        return vo;
    }
}
