package com.bookcollector.readinglist.service.impl;

import com.bookcollector.common.BizException;
import com.bookcollector.common.PageResult;
import com.bookcollector.readinglist.entity.ReadingListItem;
import com.bookcollector.readinglist.entity.ReadingStatus;
import com.bookcollector.readinglist.repository.ReadingListRepository;
import com.bookcollector.readinglist.service.ReadingListService;
import com.bookcollector.readinglist.vo.ReadingListBookVO;
import com.bookcollector.readinglist.vo.ReadingListStatusVO;
import com.bookcollector.readinglist.vo.ReadingListSummaryVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 阅读清单服务实现。
 *
 * <p>「读」很薄：参数归一化后交给仓库。「写」多一层<b>取快照</b> ——
 * 加入时先回 {@code books} 读那本书，读不到就报「图书不存在」，而不是塞一条空壳进去
 * （同 {@code ShelfServiceImpl.add}）。
 *
 * <p>🔴 <b>本类不碰 {@code shelf_books}</b>：收藏与阅读计划是两个独立维度，
 * 谁都不许顺手改对方。
 */
@Service
public class ReadingListServiceImpl implements ReadingListService {

    private static final Logger log = LoggerFactory.getLogger(ReadingListServiceImpl.class);

    /** 默认每页 30 条 —— 前端不传 {@code size} 时的兜底值 */
    private static final int DEFAULT_SIZE = 30;

    /** 上限。防 {@code size=99999} 把整表拉回来 */
    private static final int MAX_SIZE = 200;

    private final ReadingListRepository repository;

    public ReadingListServiceImpl(ReadingListRepository repository) {
        this.repository = repository;
    }

    @Override
    public PageResult<ReadingListBookVO> pageBooks(String status, String category, String title,
                                                   String author, Integer page, Integer size) {
        String st = trimToNull(status);
        String top = topCategoryOf(category);
        int p = normalizePage(page);
        int s = normalizeSize(size);
        int skip = (p - 1) * s;

        // title / author 刻意**不在这里归一化**：`RegexUtil.contains()` 自己会 trim，
        // 空白串返回 null = 不筛这一项（同 ShelfServiceImpl.pageBooks 的注释）。
        long total = repository.countBooks(st, top, title, author);
        List<ReadingListItem> rows = repository.pageBooks(st, top, title, author, skip, s);

        List<ReadingListBookVO> list = new ArrayList<ReadingListBookVO>(rows.size());
        for (ReadingListItem row : rows) {
            list.add(toVO(row));
        }
        return PageResult.of(list, total, p, s);
    }

    @Override
    public ReadingListSummaryVO summary() {
        ReadingListSummaryVO vo = new ReadingListSummaryVO();
        vo.setWantToRead(repository.countByStatus(ReadingStatus.WANT_TO_READ.name()));
        vo.setReading(repository.countByStatus(ReadingStatus.READING.name()));
        vo.setFinished(repository.countByStatus(ReadingStatus.FINISHED.name()));
        // 总数用 countAll 而不是三项之和：万一库里混进非法 status（正常不会），
        // 三项之和不等于真实条数，Tab 上的数字就会互相打架。总数以库为准。
        vo.setTotal(repository.countAll());
        return vo;
    }

    @Override
    public boolean add(String bookId, String status) {
        String id = trimToNull(bookId);
        if (id == null) {
            throw BizException.warn("缺少图书 ID");
        }
        ReadingStatus target = resolveStatus(status, true);
        if (repository.exists(id)) {
            return false;
        }

        ReadingListItem snapshot = repository.readBookSnapshot(id);
        if (snapshot == null) {
            throw BizException.notFound("图书不存在：" + id);
        }
        // 读自 books，_id 是那本书的 ObjectId —— 清掉，让 Mongo 给这一条生成新 _id
        snapshot.setId(null);
        snapshot.setBookId(id);
        snapshot.setStatus(target.name());
        Date now = new Date();
        snapshot.setAddedAt(now);
        snapshot.setStatusUpdatedAt(now);

        try {
            repository.insert(snapshot);
            return true;
        } catch (DuplicateKeyException e) {
            // 并发下两次「加入」撞上 uk_bookId：后到的那次当成「已存在」，不报错
            log.debug("加入阅读清单命中唯一键，视为已存在：{}", id);
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
    public boolean updateStatus(String bookId, String status) {
        String id = trimToNull(bookId);
        if (id == null) {
            throw BizException.warn("缺少图书 ID");
        }
        ReadingStatus target = resolveStatus(status, false);
        return repository.updateStatus(id, target.name(), new Date()) > 0;
    }

    @Override
    public List<ReadingListStatusVO> statusOf(List<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return new ArrayList<ReadingListStatusVO>();
        }
        return repository.findStatuses(bookIds);
    }

    // ------------------------------------------------------------------
    // 归一化 / 校验
    // ------------------------------------------------------------------

    /**
     * 解析阅读状态。
     *
     * @param optional {@code true} = 允许不传（用 {@link ReadingStatus#DEFAULT}）；
     *                 {@code false} = 必填，不传也报错
     * @throws BizException 传了但认不出 —— <b>不静默降级</b>：
     *                      用户点「标记为已读」失败时若被悄悄改成「想读」，比报错更让人困惑
     */
    private ReadingStatus resolveStatus(String status, boolean optional) {
        String v = trimToNull(status);
        if (v == null) {
            if (optional) {
                return ReadingStatus.DEFAULT;
            }
            throw BizException.warn("缺少阅读状态");
        }
        ReadingStatus parsed = ReadingStatus.parse(v);
        if (parsed == null) {
            throw BizException.warn("阅读状态不合法：" + v);
        }
        return parsed;
    }

    /**
     * 把传入的类目名截成一级；{@code null} / 空 / 只有空白 → {@code null}（= 不筛，取全部）。
     *
     * <p>{@code "文学-经典作品"} → {@code "文学"}；{@code "文学"} → {@code "文学"}。
     * 这样前端无论从字典里拿到的是「一级」还是「二级」，都不会出现
     * 「传了但查不到、还不报错」的静默空结果（同 {@code ShelfServiceImpl.topCategoryOf}）。
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

    /** 实体 → 对外 VO。只暴露展示字段 + 阅读状态，不吐内部 {@code _id} / {@code addedAt} */
    private ReadingListBookVO toVO(ReadingListItem row) {
        ReadingListBookVO vo = new ReadingListBookVO();
        vo.setBookId(row.getBookId());
        vo.setTitle(row.getTitle());
        vo.setAuthor(row.getAuthor());
        vo.setCover(row.getCover());
        vo.setCategory(row.getCategory());
        vo.setNewRating(row.getNewRating());
        vo.setNewRatingCount(row.getNewRatingCount());
        vo.setReadingCount(row.getReadingCount());
        vo.setIntro(row.getIntro());
        vo.setStatus(row.getStatus());
        // 展示用宽松解析：读不出来的旧值当「想读」，不让列表挂掉
        vo.setStatusLabel(ReadingStatus.of(row.getStatus()).getLabel());
        vo.setStatusUpdatedAt(row.getStatusUpdatedAt());
        return vo;
    }
}
