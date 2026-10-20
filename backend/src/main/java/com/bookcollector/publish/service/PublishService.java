package com.bookcollector.publish.service;

import com.bookcollector.common.PageResult;
import com.bookcollector.publish.req.CandidateBuildRequest;
import com.bookcollector.publish.req.InterpretationQuery;
import com.bookcollector.publish.vo.CandidateBookVO;
import com.bookcollector.publish.vo.InterpretationVO;
import com.bookcollector.publish.vo.PublishJobVO;

import java.util.List;

/**
 * AI 书籍解读（挑一本 → 生成 → 落本地 → 发语雀）。
 *
 * <h3>一次「执行」最多处理一本书</h3>
 * 用户 2026-10-09 明确「每次一本书」。所以没有「批次」的概念 ——
 * 无论手动点还是定时触发，一次调用就是「挑一本、做一本、结束」。
 * 想连续做多本，就多点几次（或让 cron 密集一点）。
 *
 * <h3>🔴 两类操作都是「提交即返回」</h3>
 * {@link #initializeCandidates()} 和 {@link #trigger()} 都<b>不</b>等任务跑完：
 * 它们只把任务丢进线程池，立刻把当前状态返回给前端，前端再靠 2 秒轮询
 * {@link #getJob()} 跟进进度。理由是生成一本要 30~120 秒，同步等会让页面
 * 卡在请求里、还容易被网关/浏览器超时掐断。用户 2026-10-09 明确要求
 * 「异步，并可以看到进度，不要卡住一直等待」。
 *
 * <p>唯一的例外是 {@link #runScheduled()}：它由定时器调用，本来就没有 HTTP 请求在等，
 * 但实现里仍然丢线程池 —— 见实现类的注释（要避开 Spring 的调度线程）。
 */
public interface PublishService {

    // ==================================================================
    // 查询
    // ==================================================================

    /** 页面全部数据（配置 + 脱敏的模型信息 + 进度 + 统计），一个接口拿齐 */
    PublishJobVO getJob();

    /** 当前 cron，供定时器读取。未配置时返回默认值 */
    String currentCron();

    /** 候选清单分页。筛选条件见 {@link InterpretationQuery}（留空的条件不参与筛选） */
    PageResult<InterpretationVO> pageInterpretations(InterpretationQuery query);

    /**
     * 批量删除候选清单条目（2026-10-10 新增）。
     *
     * <p>⚠️ <b>不限状态</b> —— 已发布到语雀的也能删。删的是清单里的这一行，
     * 不是语雀上的文章（那个得去语雀删），也不影响发布记录里的历史流水。
     *
     * @return 实际删除条数（可能与请求条数不同：有的 bookId 已经不在了）
     */
    long deleteInterpretations(List<String> bookIds);

    // ==================================================================
    // 配置
    // ==================================================================

    /** 改 cron / enabled（{@code null} = 不改这一项）。非法 cron 直接拒绝 */
    PublishJobVO updateConfig(String cron, Boolean enabled);

    // ==================================================================
    // 精选配置（2026-10-12 新增）
    // ==================================================================

    /**
     * 精选预览：按条件从图书库（{@code books}）查一页，<b>不写任何东西</b>。
     *
     * <p>它的定位是「精选之前先看一眼」—— 用户在前端调好筛选条件，
     * 先看命中哪些书、有多少本，再决定批量精选还是全部精选。
     * 因为全程只读，所以可以随便点、随便翻页。
     *
     * <p>条件与 {@link #collectCandidates(CandidateBuildRequest)} <b>完全共用</b>，
     * 保证「预览到什么，精选的就是什么」。
     *
     * @throws com.bookcollector.common.BizException 条件不完整时（见
     *         {@link #collectCandidates(CandidateBuildRequest)} 的校验口径）
     */
    PageResult<CandidateBookVO> previewCandidates(CandidateBuildRequest req, Integer page, Integer size);

    /**
     * 精选：把符合条件的书<b>快照进候选清单</b>（{@code book_interpretations}，
     * {@code status = PENDING}）。已有的 bookId 一律跳过，所以可以反复执行。
     *
     * <h3>两种模式</h3>
     * <ul>
     *   <li><b>批量精选</b>（{@code all = false}）：只处理 {@code bookIds} 里那几个
     *       —— 就是预览列表里勾选的当页行；</li>
     *   <li><b>全部精选</b>（{@code all = true}）：忽略 {@code bookIds}，
     *       按筛选条件把<b>命中全部</b>写进去。</li>
     * </ul>
     *
     * <h3>🔴 异步执行，立刻返回</h3>
     * 「全部精选」可能命中几千本，写入要几十秒 —— 同
     * {@link #initializeCandidates()}，这里只把任务丢进线程池，
     * 进度看 {@link #getJob()} 的 {@code initRunning / initScanned / initInserted}。
     *
     * <p>与 {@link #initializeCandidates()} <b>共用同一个互斥位与线程池</b>：
     * 两者都在写候选清单，同时跑会互相踩（而且进度字段是同一组，
     * 分开计数会让页面上的数字跳来跳去）。已有任务在跑时抛业务异常。
     */
    PublishJobVO collectCandidates(CandidateBuildRequest req);

    // ==================================================================
    // 执行
    // ==================================================================

    /**
     * 初始化候选清单：按条件从 {@code books} 捞书，快照进 {@code book_interpretations}
     * （{@code status = PENDING}）。<b>已有的 bookId 一律跳过</b>，所以可以反复点。
     *
     * <p>异步执行，立刻返回。进度看 {@link #getJob()}。
     */
    PublishJobVO initializeCandidates();

    /** 清空清单里「待解读」的条目（不碰已发布 / 失败 / 生成中的） */
    PublishJobVO clearCandidates();

    /**
     * 手动触发：挑一本待解读的书，生成解读并发布到语雀。
     *
     * <p>异步执行，立刻返回。已有任务在跑时抛业务异常。
     */
    PublishJobVO trigger();

    /** 定时触发入口。未启用、或已有任务在跑时<b>静默跳过</b>（不抛异常） */
    void runScheduled();

    // ==================================================================
    // 单条操作
    // ==================================================================

    /**
     * 把某一条重置回「待解读」。
     *
     * <p>用途有二：捞回卡住的「生成中」（进程被杀会残留）；把失败的重新排队
     * ——「失败不重试」指的是不<b>自动</b>重试。
     *
     * @return 是否改到了记录（{@code false} = 这个 bookId 不在清单里）
     */
    boolean resetToPending(String bookId);

    /**
     * 「重置为」——把某一条的状态改成指定值（2026-10-16 新增，配套精选图书
     * 列表的「重置为」下拉：待解读 / 已发布）。
     *
     * <p>与 {@link #resetToPending(String)} 的分工：那个是「无条件回待解读」的
     * 老路径（语义不变）；本方法是新的通用入口，Controller 直接调它。
     *
     * @param bookId   图书 ID
     * @param statusRaw 目标状态名；{@code null}/空 = {@code PENDING}（老调用零感知）。
     *                  只接受 {@code PENDING} / {@code PUBLISHED} —— 白名单理由见
     *                  {@code InterpretationRepository#resetStatus}（GENERATING
     *                  必须由挑书流程设置；FAILED 必须带 errorMsg）。
     * @return 是否改到了记录（{@code false} = 这个 bookId 不在清单里）
     */
    boolean resetTo(String bookId, String statusRaw);
}
