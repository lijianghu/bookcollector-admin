package com.bookcollector.booklist.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.bookcollector.book.dto.UpsertStat;
import com.bookcollector.booklist.BooklistCategories;
import com.bookcollector.booklist.BooklistParser;
import com.bookcollector.booklist.dto.BooklistPage;
import com.bookcollector.booklist.entity.Booklist;
import com.bookcollector.booklist.repository.BooklistRepository;
import com.bookcollector.booklist.service.BooklistSyncService;
import com.bookcollector.booklist.vo.BooklistBackfillVO;
import com.bookcollector.booklist.vo.BooklistSyncVO;
import com.bookcollector.collector.SimpleRateLimiter;
import com.bookcollector.collector.WereadClient;
import com.bookcollector.collector.dto.WereadRawResponse;
import com.bookcollector.common.BizException;
import com.bookcollector.common.ResultBean;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.config.WereadProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 链路 A（同步书单）的实现。流程与取舍见 {@link BooklistSyncService} 的类注释。
 *
 * <h3>🔴 互斥的唯一权威是内存里的 {@link #running}</h3>
 * 用 {@code compareAndSet} 而不是「先 get 再 set」：这个动作同时会被
 * 页面点击触发，两次点击并发时后者必须被明确拒绝，而不是两个线程同时往
 * {@code booklists} 里写（虽然 upsert 幂等，但进度计数会互相覆盖，
 * 页面上看到「已扫 37 张」然后跳到「12 张」）。
 *
 * <p>不落库、不建表：进度是给人看的，产物是 {@code booklists} 表。
 * 进程重启后进度归零 —— 对一次几分钟的同步可以接受
 * （与 {@code publish} 模块的两条长任务同一取舍）。
 */
@Service
public class BooklistSyncServiceImpl implements BooklistSyncService {

    private static final Logger log = LoggerFactory.getLogger(BooklistSyncServiceImpl.class);

    /** 闸门 1：书单里至少这么多本才入库 */
    public static final int MIN_BOOK_COUNT = 50;

    /**
     * 闸门 2：收藏人数至少这么多。低于它<b>不只是跳过这一条，而是整个分类停止翻页</b>
     * —— 接口带 {@code tag=最多收藏} 时按收藏人数降序返回。
     */
    public static final int MIN_COLLECT_COUNT = 10000;

    /**
     * 单个分类最多翻多少页 —— 纯粹的安全阀。
     *
     * <p>正常情况跑不到：提前终止会在阈值附近就停下。但如果哪天
     * {@code tag} 失效导致排序乱了（于是「提前终止」不再成立），
     * 没有这个上限就会一直翻到接口给空页为止 —— 那可能是几百次请求。
     * 200 页 × 10 条 = 2000 条，已覆盖抓包样例里最大的分类（2000）。
     */
    private static final int MAX_PAGES_PER_CATEGORY = 200;

    private final WereadClient client;
    private final BooklistRepository repository;
    private final BooklistParser parser;
    private final WereadProperties props;

    /** 限速器是有状态的，所以不是 Spring bean，跟着本类生命周期走（同 CollectLoop） */
    private final SimpleRateLimiter rateLimiter;

    /** 是否正在跑。见类注释 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 当前进度快照。每次变化都整体替换一份新的（不可变快照，同 publish 的做法） */
    private final AtomicReference<BooklistSyncVO> live = new AtomicReference<BooklistSyncVO>();

    /** 「一键完善书单信息」的进度快照（与同步分开，互不覆盖） */
    private final AtomicReference<BooklistBackfillVO> liveBackfill =
            new AtomicReference<BooklistBackfillVO>();

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public BooklistSyncServiceImpl(WereadClient client,
                                   BooklistRepository repository,
                                   BooklistParser parser,
                                   WereadProperties props) {
        this.client = client;
        this.repository = repository;
        this.parser = parser;
        this.props = props;
        this.rateLimiter = new SimpleRateLimiter(props.getRateLimitPerSecond());
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @PostConstruct
    public void init() {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // 队列只留 1 个位置：正常用不到（互斥位已挡住重复提交），
        // 留一个是为了极端并发下「明确拒绝」而不是「静默排队」
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("booklist-sync-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();

        live.set(idle(null));
    }

    @PreDestroy
    public void destroy() {
        executor.shutdown();
    }

    // ==================================================================
    // 对外
    // ==================================================================

    @Override
    public BooklistSyncVO sync(List<String> categoryIds) {
        // ★ 先解析分类清单（可能因「未知分类 ID」失败）。
        //   校验不过就<b>不占互斥位</b> —— 否则一次非法请求会把「正在同步」的牌子挂上，
        //   而实际什么都没跑，之后正常的点击全被这句「正在进行中」挡回去。
        final List<Booklist.PlatformCategory> categories = resolveCategories(categoryIds);

        if (!running.compareAndSet(false, true)) {
            throw new BizException(ResultBean.code_warn, "书单同步正在进行中，请等它跑完再试");
        }
        // 立刻发布一份「刚起步」的快照：页面此刻轮询过来应该看到 0/N
        Progress p = new Progress(categories.size());
        p.running = true;
        p.startedAtMs = System.currentTimeMillis();
        p.message = "准备开始";
        live.set(p.toVO());

        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    doSync(p, categories);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            live.set(idle("启动失败：" + brief(e)));
            log.error("书单同步：提交线程失败", e);
            throw new BizException(ResultBean.code_warn, "同步线程池繁忙，本次未能启动，请稍后重试");
        }
        log.info("书单同步：已提交（{} 个分类）", categories.size());
        return progress();
    }

    /**
     * 解析并校验本次要同步的分类清单。
     *
     * <p>未知的 ID 转成<b>业务异常</b>（HTTP 200 + {@code code=400}），
     * 而不是让它冒成 500 —— 这是「前端传了清单外的值」，
     * 属于调用方错误，不是服务端故障（同 {@code BooklistSyncService#sync} 的约定）。
     */
    private List<Booklist.PlatformCategory> resolveCategories(List<String> categoryIds) {
        try {
            return BooklistCategories.byIds(categoryIds);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultBean.code_warn, e.getMessage());
        }
    }

    @Override
    public BooklistSyncVO progress() {
        BooklistSyncVO vo = live.get();
        return vo == null ? idle(null) : vo;
    }

    // ==================================================================
    // 书单名称回填（链路 B 启动任务前的顺带动作）
    // ==================================================================

    /**
     * {@inheritDoc}
     *
     * <h3>🔴 全流程不抛异常</h3>
     * 它是「启动任务」路上的一个顺带动作：回填失败（网络抖动、登录超时、响应形状变了）
     * 不该让任务启不起来。所以每个失败分支都只是 {@code log.warn} + 返回 null，
     * 调用方拿 null 继续用原来的名称（书单 ID）。
     *
     * <p>唯一会「花掉一次请求」的情况是库里确实没有名称 —— 已经有名就直接返回，
     * 不会每次启动都发请求。
     */
    @Override
    public String ensureName(String booklistId) {
        return ensureMetadata(booklistId, false);
    }

    @Override
    public String refreshMetadata(String booklistId) {
        return ensureMetadata(booklistId, true);
    }

    private String ensureMetadata(String booklistId, boolean force) {
        if (booklistId == null || booklistId.trim().isEmpty()) {
            return null;
        }
        String id = booklistId.trim();

        // ---- ① 先看库里有没有。这是最常见的情况，不该为它发请求 ----
        Booklist existing = repository.findByBooklistId(id);
        if (existing == null) {
            log.warn("书单元数据回填：书单表里没有 booklistId={}，跳过", id);
            return null;
        }
        String current = existing.getName();
        boolean nameBlank = current == null || current.trim().isEmpty();
        // 名称 + 关键计数都在 → 视为完整，不再发请求（force = 强制刷新时跳过这个判断）
        if (!force && !nameBlank && existing.getBookCount() != null && existing.getCollectCount() != null) {
            return current.trim();
        }

        // ---- ② 元数据缺失 → 去接口要整条 ----
        // fetchRaw 自己会兜住网络异常与「sUrl 没配」，不往外抛
        WereadRawResponse resp = client.fetchRaw(TargetType.BOOKLIST, id, 0);
        if (!resp.isSuccess()) {
            log.warn("书单元数据回填：请求未成功 booklistId={}：{}", id, resp.describeFailure());
            return nameBlank ? null : current.trim();
        }

        Booklist parsed;
        try {
            JSONObject root = JSON.parseObject(resp.getBody());
            JSONObject outer = root == null ? null : root.getJSONObject("booklist");
            // 完整形状是 booklist.booklist；拍平形态则是 booklist 本身
            JSONObject node = outer == null ? null
                    : (outer.getJSONObject("booklist") != null
                            ? outer.getJSONObject("booklist") : outer);
            if (node == null) {
                log.warn("书单元数据回填：响应里没有 booklist 节点 booklistId={}", id);
                return nameBlank ? null : current.trim();
            }
            parsed = parser.parseOne(node, id);
        } catch (RuntimeException e) {
            log.warn("书单元数据回填：响应解析失败 booklistId={}：{}", id, e.getMessage());
            return nameBlank ? null : current.trim();
        }

        if (repository.updateMetadata(id, parsed)) {
            log.info("书单元数据回填成功：{} → 「{}」", id, parsed.getName());
        }
        String name = parsed.getName();
        if (name == null || name.trim().isEmpty()) {
            // 接口对某些书单本来就不给名称。不是错误 —— 下次再试一次。
            log.warn("书单元数据回填：接口也没给名称 booklistId={}", id);
            return nameBlank ? null : current.trim();
        }
        return name.trim();
    }

    // ==================================================================
    // 一键完善书单信息（2026-10-20）
    // ==================================================================

    @Override
    public BooklistBackfillVO backfillMetadata() {
        if (!running.compareAndSet(false, true)) {
            throw new BizException(ResultBean.code_warn, "书单同步/完善正在进行中，请等它跑完再试");
        }
        List<Booklist> targets = repository.findAllIds();
        BackfillProgress p = new BackfillProgress(targets.size());
        p.running = true;
        p.startedAtMs = System.currentTimeMillis();
        p.message = targets.isEmpty() ? "没有书单" : "准备开始";
        liveBackfill.set(p.toVO());
        if (targets.isEmpty()) {
            p.running = false;
            p.finishedAtMs = System.currentTimeMillis();
            liveBackfill.set(p.toVO());
            running.set(false);
            return backfillProgress();
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        doBackfill(p, targets);
                    } finally {
                        running.set(false);
                    }
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            liveBackfill.set(idleBackfill("启动失败：" + e.getMessage()));
            throw new BizException(ResultBean.code_warn, "线程池繁忙，本次未能启动，请稍后重试");
        }
        log.info("书单完善：已提交（{} 张待完善）", targets.size());
        return backfillProgress();
    }

    @Override
    public BooklistBackfillVO backfillProgress() {
        BooklistBackfillVO vo = liveBackfill.get();
        return vo == null ? idleBackfill(null) : vo;
    }

    private void doBackfill(BackfillProgress p, List<Booklist> targets) {
        try {
            for (Booklist target : targets) {
                String id = target.getBooklistId();
                if (id == null || id.trim().isEmpty()) {
                    p.processed++;
                    p.failed++;
                    publishBackfill(p);
                    continue;
                }
                p.currentBooklistId = id;
                publishBackfill(p);
                try {
                    rateLimiter.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                refreshMetadata(id);
                Booklist after = repository.findByBooklistId(id);
                boolean ok = after != null && after.getBookCount() != null
                        && after.getCollectCount() != null;
                if (ok) {
                    p.updated++;
                } else {
                    p.failed++;
                }
                p.processed++;
                publishBackfill(p);
            }
            p.message = "已完成";
        } catch (RuntimeException e) {
            log.error("书单完善：执行出错", e);
            p.errorMsg = e.getMessage();
            p.message = "出错：" + e.getMessage();
        } finally {
            p.running = false;
            p.finishedAtMs = System.currentTimeMillis();
            publishBackfill(p);
            log.info("书单完善：结束（共 {} 张，成功 {}，失败 {}）", p.total, p.updated, p.failed);
        }
    }

    private void publishBackfill(BackfillProgress p) {
        liveBackfill.set(p.toVO());
    }

    private static BooklistBackfillVO idleBackfill(String message) {
        return BooklistBackfillVO.builder().running(false).message(message).build();
    }

    private static final class BackfillProgress {
        final int total;
        int processed;
        int updated;
        int failed;
        String currentBooklistId;
        String message;
        String errorMsg;
        boolean running;
        long startedAtMs;
        long finishedAtMs;

        BackfillProgress(int total) {
            this.total = total;
        }

        BooklistBackfillVO toVO() {
            return BooklistBackfillVO.builder()
                    .running(running)
                    .total(total)
                    .processed(processed)
                    .updated(updated)
                    .failed(failed)
                    .currentBooklistId(currentBooklistId)
                    .message(message)
                    .startedAtMs(startedAtMs)
                    .finishedAtMs(finishedAtMs)
                    .errorMsg(errorMsg)
                    .build();
        }
    }

    // ==================================================================
    // 执行体
    // ==================================================================

    /**
     * 真正干活的地方。<b>不抛异常</b> —— 异常穿透出去只会变成线程池里一条没人看的堆栈，
     * 页面上的进度会永远停在半路。所有失败都装进进度快照的 {@code errorMsg}。
     *
     * @param categories 本次要跑的分类，由 {@link #sync(java.util.List)} 解析好传进来
     *                   （构造 {@link Progress} 时已用它的 size 当分母，这里不再改）
     */
    private void doSync(Progress p, List<Booklist.PlatformCategory> categories) {
        try {
            log.info("书单同步开始：{} 个分类，闸门 = 书数 >= {} 且 收藏人数 >= {}",
                    categories.size(), MIN_BOOK_COUNT, MIN_COLLECT_COUNT);

            for (Booklist.PlatformCategory category : categories) {
                p.currentCategoryId = category.getCategoryId();
                p.currentCategoryTitle = category.getTitle();
                p.message = "正在同步「" + category.getTitle() + "」";
                live.set(p.toVO());

                syncOneCategory(category, p);

                p.categoriesDone++;
                p.message = "已完成 " + p.categoriesDone + "/" + p.totalCategories
                        + " 个分类（扫到 " + p.booklistsSeen + " 张，入库 "
                        + p.booklistsSaved + " 张）";
                live.set(p.toVO());
            }

            if (p.booklistsSeen == 0) {
                // 🔴 一个分类都没扫到 —— 在正常数据下几乎不可能：
                //    「分类书单」的 15 个数字分类每个都有一两千张书单（首页接口的
                //    categories[].totalCount 实测：历史 4264、精品小说 6472）。
                //    所以这几乎总是「请求参数 / 登录态不对」，而不是「平台上没有书单」。
                //    但两者在界面上原来长得一模一样（都只显示「扫到 0 张」），
                //    所以这里必须显式报成失败，而不是「同步完成」。
                p.errorMsg = "未扫到任何书单：扫描 " + p.totalCategories + " 个分类、"
                        + p.pagesFetched + " 页，一条都没返回（其中 " + p.emptyFirstPageCategories
                        + " 个分类第 1 页就是空的）。这通常意味着请求参数或登录态有问题"
                        + "（tag 编码 / skey 是否过期 / 书单接口地址 sUrl），"
                        + "而不是「平台上没有书单」。详见后端日志。";
                p.message = "同步异常：" + p.errorMsg;
                log.error("书单同步未扫到任何书单：{} 个分类、{} 页、{} 个分类第 1 页为空",
                        p.totalCategories, p.pagesFetched, p.emptyFirstPageCategories);
                return;
            }

            p.message = "同步完成：扫描 " + p.totalCategories + " 个分类，扫到 "
                    + p.booklistsSeen + " 张书单，入库 " + p.booklistsSaved
                    + " 张（新增 " + p.booklistsInserted + " 张，跳过 "
                    + p.booklistsSkipped + " 张）";
            log.info("书单同步结束：{}", p.message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.errorMsg = "同步线程被中断";
            p.message = "已中断";
            log.warn("书单同步被中断（已扫 {} 张）", p.booklistsSeen);
        } catch (Exception e) {
            p.errorMsg = brief(e);
            p.message = "同步失败：" + p.errorMsg;
            log.error("书单同步失败（已扫 {} 张）", p.booklistsSeen, e);
        } finally {
            running.set(false);
            p.running = false;
            p.finishedAtMs = System.currentTimeMillis();
            live.set(p.toVO());
        }
    }

    /**
     * 同步一个分类下的书单。
     *
     * <p>终止条件（任一命中即结束本分类）：
     * <ol>
     *   <li><b>第 1 页</b>为空 —— 不算正常结束，见下；</li>
     *   <li>第 2 页及以后某页为空 —— 正常结束；</li>
     *   <li>本页条数 &lt; count（说明是最后一页）；</li>
     *   <li>响应里 {@code hasMore} 明确为假；</li>
     *   <li>命中「收藏人数不达标」—— 降序排列下后面都不用看了；</li>
     *   <li>翻到 {@value #MAX_PAGES_PER_CATEGORY} 页（安全阀）。</li>
     * </ol>
     *
     * <p>⚠️ 第 3 条与第 4 条是<b>并集</b>，不是二选一：{@code hasMore} 的字段名
     * 属于「接口可能变」的那部分（见 {@code BooklistPage} 的注释），
     * 只靠它会让「字段名变了」表现成「只翻了第一页就结束」——
     * 一次没有任何报错的静默截断。反过来只靠「条数 &lt; count」也是同理。
     *
     * <h3>🔴 第 1 条为什么单独拎出来</h3>
     * 「翻完了」与「一个都没返回」在结果上完全一样（都是空页），
     * 但含义完全相反：前者是正常收尾，后者几乎总是<b>请求参数或登录态有问题</b>
     * —— 2026-10-14 用户点「历史」同步得到「扫到 0 张」，根因就是
     * {@code tag} 常量编码少了一个字，服务端认不出 tag、回了个空 {@code datas}。
     * 所以第 1 页为空时：响应若还声明该分类有书单，直接抛异常；
     * 否则计数（{@code emptyFirstPageCategories}）并记 WARN，不再静默 return。
     */
    private void syncOneCategory(Booklist.PlatformCategory category, Progress p)
            throws InterruptedException {
        int pageSize = WereadClient.booklistCategoryPageSize();
        int maxIdx = 0;
        int page = 0;

        while (true) {
            page++;
            if (page > MAX_PAGES_PER_CATEGORY) {
                log.warn("分类「{}」已翻到 {} 页上限，停止（可能是 tag 排序失效）",
                        category.getTitle(), MAX_PAGES_PER_CATEGORY);
                p.message = "「" + category.getTitle() + "」翻页达上限 "
                        + MAX_PAGES_PER_CATEGORY + " 页，已停止";
                live.set(p.toVO());
                return;
            }

            WereadRawResponse resp = client.fetchBooklistCategory(category.getCategoryId(), maxIdx);
            p.pagesFetched++;
            if (!resp.isSuccess()) {
                throw new IllegalStateException("分类「" + category.getTitle() + "」第 " + page
                        + " 页请求失败：" + resp.describeFailure());
            }

            // 响应体原文留着 —— 下面「空页」分支要报它的长度
            // （一个 863 字符的空信封，是判断「服务端到底答了什么」的唯一线索）
            String body = resp.getBody();

            BooklistPage data;
            try {
                data = BooklistPage.parse(body);
            } catch (IllegalArgumentException e) {
                // 带上「哪个分类、第几页」—— 单独一句「响应里没有 datas」没法定位
                throw new IllegalStateException("分类「" + category.getTitle() + "」第 " + page
                        + " 页响应解析失败：" + e.getMessage(), e);
            }

            if (data.isEmpty()) {
                // 🔴 「第 1 页为空」绝不能当成「这个分类没有书单」静默收场 ——
                //    2026-10-14 那次「历史扫到 0 张」正是被这里掩盖的：
                //    tag 参数写错 → 服务端不报错、只回空 datas → 页面显示「同步完成」。
                //    要区分「服务端说有 N 张却一张没给」和「这个分类本来就是空的」，
                //    唯一可用的证据是响应里的 totalCount。
                if (page > 1) {
                    log.info("分类「{}」第 {} 页返回空列表，结束", category.getTitle(), page);
                    return;
                }
                Integer declaredTotal = data.getTotalCount();
                if (declaredTotal != null && declaredTotal.intValue() > 0) {
                    // 响应自相矛盾：明确说该分类有 N 张，却一条都没返回。
                    // 这一定是请求有问题，没有第二种解释 —— 直接失败，不要让它看起来像「跑完了」。
                    throw new IllegalStateException("分类「" + category.getTitle() + "」（id="
                            + category.getCategoryId() + "）第 1 页返回空列表，但响应声明该分类共有 "
                            + declaredTotal + " 张书单。请检查请求参数与登录态：tag 编码、"
                            + "categoryId、skey 是否过期、书单接口地址（sUrl）。响应 "
                            + body.length() + " 字符");
                }
                // 服务端没说自己有书单（totalCount 缺失或为 0）。不能断定是错误，
                // 但也不能当成正常 —— 单独计数并在页面上展示，让它永远可见。
                p.emptyFirstPageCategories++;
                log.warn("分类「{}」（id={}）第 1 页返回空列表：响应 totalCount={}、hasMore={}、"
                                + "{} 字符。若该分类本该有书单，请检查 tag 编码 / skey / sUrl",
                        category.getTitle(), category.getCategoryId(), declaredTotal,
                        data.getHasMore(), body.length());
                p.message = "「" + category.getTitle() + "」第 1 页为空（响应声明总数 "
                        + declaredTotal + "）";
                live.set(p.toVO());
                return;
            }

            List<Booklist> parsed = parser.parseAll(data.getDatas());
            p.booklistsSeen += data.size();

            // ---- 闸门 ----
            List<Booklist> accepted = new ArrayList<Booklist>(parsed.size());
            boolean collectGateHit = false;
            for (Booklist booklist : parsed) {
                Integer collectCount = booklist.getCollectCount();
                if (collectCount == null || collectCount.intValue() < MIN_COLLECT_COUNT) {
                    // 降序排列：这一条不达标，后面的只会更低 → 本分类到此为止。
                    // ⚠️ 只把「触发终止的这一条」记进 skipped —— 后面那些压根没看，
                    //    报一个猜出来的数字比不报更糟。
                    collectGateHit = true;
                    p.booklistsSkipped++;
                    break;
                }
                Integer bookCount = booklist.getBookCount();
                if (bookCount == null || bookCount.intValue() < MIN_BOOK_COUNT) {
                    p.booklistsSkipped++;
                    continue;
                }
                accepted.add(booklist);
            }

            if (!accepted.isEmpty()) {
                UpsertStat stat = repository.upsertMany(accepted, category);
                p.booklistsSaved += stat.total();
                p.booklistsInserted += stat.getInserted();
            }
            p.message = "「" + category.getTitle() + "」第 " + page + " 页：扫到 "
                    + data.size() + " 张，入库 " + accepted.size() + " 张";
            live.set(p.toVO());

            if (collectGateHit) {
                log.info("分类「{}」第 {} 页命中「收藏人数 < {}」，提前终止（该分类已扫 {} 张）",
                        category.getTitle(), page, MIN_COLLECT_COUNT, p.booklistsSeen);
                return;
            }
            if (data.size() < pageSize) {
                return;
            }
            Boolean hasMore = data.getHasMore();
            if (hasMore != null && !hasMore.booleanValue()) {
                return;
            }

            maxIdx += pageSize;
            rateLimiter.acquire();
        }
    }

    // ==================================================================
    // 进度快照
    // ==================================================================

    /**
     * 进度累加器。只被同步线程读写，快照 {@link #toVO()} 之后才交给别的线程看 ——
     * 所以它自己不需要任何同步。
     */
    private static final class Progress {

        /**
         * 本次要跑的分类数。构造时定死，跑的全程不变 ——
         * 前端拿它当进度条的分母。
         */
        final int totalCategories;
        int categoriesDone;
        String currentCategoryId;
        String currentCategoryTitle;
        int pagesFetched;
        int booklistsSeen;
        int booklistsSaved;
        int booklistsInserted;
        int booklistsSkipped;
        /**
         * 第 1 页就返回空列表的分类数。
         *
         * <p>正常情况应该是 0。它不是「失败」计数（那种情况直接抛异常了），
         * 而是「说不清但可疑」的计数：服务端没声明该分类有书单，却一张也没给。
         * 单独留一个字段是为了让它<b>永远在页面上可见</b> —— 不再被
         * 「同步完成、扫到 0 张」这句话掩盖掉。
         */
        int emptyFirstPageCategories;
        String message = "";
        long startedAtMs;
        Long finishedAtMs;
        String errorMsg;
        boolean running;

        Progress(int totalCategories) {
            this.totalCategories = totalCategories;
        }

        BooklistSyncVO toVO() {
            return BooklistSyncVO.builder()
                    .running(running)
                    .totalCategories(totalCategories)
                    .categoriesDone(categoriesDone)
                    .currentCategoryId(currentCategoryId)
                    .currentCategoryTitle(currentCategoryTitle)
                    .pagesFetched(pagesFetched)
                    .booklistsSeen(booklistsSeen)
                    .booklistsSaved(booklistsSaved)
                    .booklistsInserted(booklistsInserted)
                    .booklistsSkipped(booklistsSkipped)
                    .emptyFirstPageCategories(emptyFirstPageCategories)
                    .message(message)
                    .startedAtMs(startedAtMs == 0L ? null : Long.valueOf(startedAtMs))
                    .finishedAtMs(finishedAtMs)
                    .errorMsg(errorMsg)
                    .build();
        }
    }

    /**
     * 一份「没在跑」的快照。服务刚起来、或本次已结束时用。
     *
     * <p>{@code totalCategories} 取全量清单（16）—— 这里没有「本次跑几个」的信息，
     * 给 0 会让页面显示「0/0」。反正这个值只在 {@code running=false} 时出现，
     * 页面那时不显示进度条。
     */
    private static BooklistSyncVO idle(String message) {
        return BooklistSyncVO.builder()
                .running(false)
                .totalCategories(BooklistCategories.all().size())
                .categoriesDone(0)
                .pagesFetched(0)
                .booklistsSeen(0)
                .booklistsSaved(0)
                .booklistsInserted(0)
                .booklistsSkipped(0)
                .message(message)
                .build();
    }

    /** 异常压成一句话。只取类型 + message，不带堆栈（失败原因要展示给用户） */
    private static String brief(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName()
                + (msg == null || msg.trim().isEmpty() ? "" : "：" + msg);
    }
}
