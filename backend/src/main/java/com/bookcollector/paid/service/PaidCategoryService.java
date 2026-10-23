package com.bookcollector.paid.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bookcollector.book.dto.UpsertStat;
import com.bookcollector.book.entity.Book;
import com.bookcollector.book.repository.BookRepository;
import com.bookcollector.collector.BookParser;
import com.bookcollector.collector.SimpleRateLimiter;
import com.bookcollector.collector.WereadClient;
import com.bookcollector.collector.dto.WereadRawResponse;
import com.bookcollector.common.BizException;
import com.bookcollector.common.ResultBean;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.config.WereadProperties;
import com.bookcollector.paid.entity.PaidCollectState;
import com.bookcollector.paid.repository.PaidCollectStateRepository;
import com.bookcollector.paid.vo.PaidCategoryVO;
import com.bookcollector.paid.vo.PaidCollectVO;
import com.bookcollector.taxonomy.entity.TaxonomyItem;
import com.bookcollector.taxonomy.repository.TaxonomyRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 「付费精选配置」—— 按 {@code taxonomy_config} 的一级分类分页采集付费精选书籍（2026-10-20）。
 *
 * <h3>接口</h3>
 * {@code GET {sUrl}/category/data?categoryId=9000100&count=10&filterCategory={code}
 * &finish=0&maxIdx={n}&sort=2&synckey={ts}&wordcount_begin=0&wordcount_end=0}
 *
 * <h3>🔴 写入口径与普通采集不同</h3>
 * 不覆盖已有书籍字段：已存在时只 {@code $addToSet collectSource} + {@code $set paidCollect=1}；
 * 不存在才插入。见 {@code BookRepository#upsertPaidMany}。
 *
 * <h3>🔴 同一时刻只跑一个分类</h3>
 * {@link #running} 互斥；进度是全局一份快照。
 */
@Service
public class PaidCategoryService {

    private static final Logger log = LoggerFactory.getLogger(PaidCategoryService.class);

    /** 付费精选的分类 ID（固定） */
    private static final String PAID_CATEGORY_ID = "9000100";

    private final TaxonomyRepository taxonomyRepository;
    private final WereadClient client;
    private final BookParser parser;
    private final BookRepository bookRepository;
    private final PaidCollectStateRepository stateRepository;
    private final SimpleRateLimiter rateLimiter;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicReference<PaidCollectVO> live = new AtomicReference<PaidCollectVO>();

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public PaidCategoryService(TaxonomyRepository taxonomyRepository,
                               WereadClient client,
                               BookParser parser,
                               BookRepository bookRepository,
                               PaidCollectStateRepository stateRepository,
                               WereadProperties props) {
        this.taxonomyRepository = taxonomyRepository;
        this.client = client;
        this.parser = parser;
        this.bookRepository = bookRepository;
        this.stateRepository = stateRepository;
        this.rateLimiter = new SimpleRateLimiter(props.getRateLimitPerSecond());
    }

    @PostConstruct
    public void init() {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("paid-collect-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        live.set(idle(null));
        // 上次进程遗留的 RUNNING → INTERRUPTED（旧版只写 maxIdx 的也一并处理）
        stateRepository.markInterruptedOnStartup();
    }

    @PreDestroy
    public void destroy() {
        executor.shutdown();
    }

    /** 可采集的分类：{@code taxonomy_config} 里一级 + enabled=true；附带断点/状态 */
    public List<PaidCategoryVO> list() {
        Map<String, PaidCollectState> states = new HashMap<String, PaidCollectState>();
        for (PaidCollectState s : stateRepository.listAll()) {
            if (s != null && s.getFilterCategory() != null) {
                states.put(s.getFilterCategory(), s);
            }
        }
        List<TaxonomyItem> items = taxonomyRepository.findByType(TargetType.CATEGORY, true);
        List<PaidCategoryVO> result = new ArrayList<PaidCategoryVO>();
        for (TaxonomyItem item : items) {
            if (item == null) {
                continue;
            }
            String parent = item.getParentCode();
            if (parent != null && !parent.trim().isEmpty()) {
                continue;                       // 只一级
            }
            String code = item.getCode();
            if (code == null || code.trim().isEmpty()) {
                continue;
            }
            if(PAID_CATEGORY_ID.equals(code)) { // 付费精选，code取0代表全部
                code = "0";
            }
            PaidCollectState s = states.get(code.trim());
            result.add(PaidCategoryVO.builder()
                    .code(code.trim())
                    .name(item.getName())
                    .lastStatus(s == null ? null : s.getLastStatus())
                    .page(s == null ? 0 : s.getPage())
                    .totalCount(s == null ? null : s.getTotalCount())
                    .updatedAt(s == null ? null : s.getUpdatedAt())
                    .build());
        }
        return result;
    }

    /**
     * 启动一个分类的采集（提交即返回）。
     *
     * @param reset {@code true} = 清掉断点从头采；{@code false} = 有断点就从断点续
     */
    public PaidCollectVO start(String filterCategory, boolean reset) {
        String code = filterCategory == null ? "" : filterCategory.trim();
        if (code.isEmpty()) {
            throw new BizException(ResultBean.code_warn, "filterCategory 不能为空");
        }
        String name = null;
        boolean allowed = false;
        for (PaidCategoryVO row : list()) {
            if (code.equals(row.getCode())) {
                allowed = true;
                name = row.getName();
                break;
            }
        }
        if (!allowed) {
            throw new BizException(ResultBean.code_warn,
                    "分类不在可采集范围（需一级 + enabled=true）：" + code);
        }
        if (!running.compareAndSet(false, true)) {
            throw new BizException(ResultBean.code_warn, "付费精选采集正在进行中，请等它跑完");
        }
        cancel.set(false);
        paused.set(false);

        if (reset) {
            stateRepository.delete(code);
        }
        PaidCollectState state = stateRepository.find(code);
        final int startMaxIdx = state == null ? 0 : Math.max(0, state.getMaxIdx());
        final int startPage = state == null ? 0 : Math.max(0, state.getPage());

        // 先落一行「运行中 + 分类名称」：即使第一页就失败，列表里也能看到这条记录
        stateRepository.save(code, name, startMaxIdx, startPage,
                state == null ? null : state.getTotalCount(), "RUNNING");

        final Progress p = new Progress(code, name);
        p.running = true;
        p.startedAtMs = System.currentTimeMillis();
        p.page = startPage;
        p.message = startMaxIdx > 0 ? "从第 " + (startPage + 1) + " 页继续" : "准备开始";
        live.set(p.toVO());

        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        doCollect(p, code, startMaxIdx);
                    } finally {
                        running.set(false);
                    }
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            live.set(idle("启动失败：" + e.getMessage()));
            throw new BizException(ResultBean.code_warn, "线程池繁忙，本次未能启动，请稍后重试");
        }
        log.info("付费精选：已提交 filterCategory={}，maxIdx={}，page={}", code, startMaxIdx, startPage);
        return progress();
    }

    public PaidCollectVO progress() {
        PaidCollectVO vo = live.get();
        return vo == null ? idle(null) : vo;
    }

    /** 请求停止（当前页跑完后退出） */
    public void stop() {
        cancel.set(true);
        paused.set(false);
    }

    /** 请求暂停：当前页跑完后原地等待，{@code maxIdx} 保留 */
    public void pause() {
        paused.set(true);
        updateStatusQuietly("PAUSED");
    }

    /** 继续采集 */
    public void resume() {
        paused.set(false);
        updateStatusQuietly("RUNNING");
    }

    private void updateStatusQuietly(String status) {
        PaidCollectVO vo = live.get();
        if (vo != null && vo.getFilterCategory() != null) {
            stateRepository.updateStatus(vo.getFilterCategory(), status);
        }
    }

    private void doCollect(Progress p, String filterCategory, int startMaxIdx) {
        String sourceKey = "paid:" + PAID_CATEGORY_ID + ":" + filterCategory;
        int maxIdx = startMaxIdx;
        try {
            while (true) {
                // 暂停：原地等待，maxIdx 保留；取消则退出
                if (paused.get()) {
                    p.paused = true;
                    p.message = "已暂停";
                    publish(p);
                    while (paused.get() && !cancel.get()) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    p.paused = false;
                }
                if (cancel.get()) {
                    stateRepository.updateStatus(filterCategory, "STOPPED");
                    p.message = "已停止";
                    break;
                }
                try {
                    rateLimiter.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stateRepository.updateStatus(filterCategory, "INTERRUPTED");
                    p.message = "已中断";
                    break;
                }

                WereadRawResponse resp = client.fetchCategoryData(filterCategory, maxIdx);
                // 401 = 身份过期：进入暂停态，等用户更新凭证后点「继续」重试同一页
                if (resp.getStatusCode() == 401) {
                    paused.set(true);
                    stateRepository.updateStatus(filterCategory, "PAUSED");
                    p.paused = true;
                    p.message = "身份已过期（HTTP 401），已暂停；请更新凭证后点「继续」";
                    publish(p);
                    while (paused.get() && !cancel.get()) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    p.paused = false;
                    if (cancel.get()) {
                        stateRepository.updateStatus(filterCategory, "STOPPED");
                        p.message = "已停止";
                        break;
                    }
                    continue;                   // 重试同一页，maxIdx 不变
                }
                if (!resp.isSuccess()) {
                    stateRepository.updateStatus(filterCategory, "FAILED");
                    p.errorMsg = "第 " + (p.page + 1) + " 页请求失败：" + resp.describeFailure();
                    p.message = "失败";
                    break;
                }

                JSONObject root;
                try {
                    root = JSON.parseObject(resp.getBody());
                } catch (RuntimeException e) {
                    stateRepository.updateStatus(filterCategory, "FAILED");
                    p.errorMsg = "响应不是合法 JSON：" + e.getMessage();
                    p.message = "失败";
                    break;
                }
                if (root == null) {
                    stateRepository.updateStatus(filterCategory, "FAILED");
                    p.errorMsg = "响应为空";
                    p.message = "失败";
                    break;
                }
                Integer errcode = root.getInteger("errcode");
                if (errcode != null && errcode.intValue() != 0) {
                    stateRepository.updateStatus(filterCategory, "FAILED");
                    p.errorMsg = "接口拒绝：errcode=" + errcode + " " + root.getString("errmsg");
                    p.message = "失败";
                    break;
                }

                JSONArray books = root.getJSONArray("books");
                if (books == null || books.isEmpty()) {
                    stateRepository.save(filterCategory, p.filterCategoryName, maxIdx, p.page,
                            p.totalCount, "COMPLETED");
                    p.message = "已到末页";
                    break;
                }

                List<Book> parsed = parser.parseAll(books);
                if (!parsed.isEmpty()) {
                    UpsertStat stat = bookRepository.upsertPaidMany(parsed, sourceKey);
                    p.booksInserted += stat.getInserted();
                    p.booksUpdated += stat.getUpdated();
                }
                p.totalCount = root.getInteger("totalCount");
                p.page++;
                p.booksSaved += parsed.size();
                publish(p);

                int nextMaxIdx = maxIdx + WereadClient.paidPageSize();
                // 暂停在页边界生效：本页若在暂停请求之后才写完，状态必须落 PAUSED，
                // 否则会被这里的 RUNNING 覆盖，重启后看起来像「没暂停」。
                stateRepository.save(filterCategory, p.filterCategoryName, nextMaxIdx, p.page,
                        p.totalCount, paused.get() ? "PAUSED" : "RUNNING");

                Integer hasMore = root.getInteger("hasMore");
                if (hasMore == null || hasMore.intValue() != 1) {
                    stateRepository.save(filterCategory, p.filterCategoryName, nextMaxIdx, p.page,
                            p.totalCount, "COMPLETED");
                    p.message = "已完成";
                    break;
                }
                maxIdx = nextMaxIdx;
            }
        } catch (RuntimeException e) {
            log.error("付费精选：执行出错", e);
            stateRepository.updateStatus(filterCategory, "FAILED");
            p.errorMsg = e.getMessage();
            p.message = "出错";
        } finally {
            p.running = false;
            p.finishedAtMs = System.currentTimeMillis();
            publish(p);
            log.info("付费精选：结束（{}，{} 页，解析 {} 本，新增 {}，更新 {}）",
                    filterCategory, p.page, p.booksSaved, p.booksInserted, p.booksUpdated);
        }
    }

    private void publish(Progress p) {
        live.set(p.toVO());
    }

    private static PaidCollectVO idle(String message) {
        return PaidCollectVO.builder().running(false).message(message).build();
    }

    private static final class Progress {
        final String filterCategory;
        final String filterCategoryName;
        boolean running;
        boolean paused;
        Integer totalCount;
        int page;
        int booksSaved;
        long booksInserted;
        long booksUpdated;
        String message;
        long startedAtMs;
        long finishedAtMs;
        String errorMsg;

        Progress(String filterCategory, String filterCategoryName) {
            this.filterCategory = filterCategory;
            this.filterCategoryName = filterCategoryName;
        }

        PaidCollectVO toVO() {
            return PaidCollectVO.builder()
                    .running(running)
                    .paused(paused)
                    .filterCategory(filterCategory)
                    .filterCategoryName(filterCategoryName)
                    .totalCount(totalCount)
                    .page(page)
                    .booksSaved(booksSaved)
                    .booksInserted(booksInserted)
                    .booksUpdated(booksUpdated)
                    .message(message)
                    .startedAtMs(startedAtMs)
                    .finishedAtMs(finishedAtMs)
                    .errorMsg(errorMsg)
                    .build();
        }
    }
}
