package com.bookcollector.publish.service;

import com.bookcollector.common.PageResult;
import com.bookcollector.publish.vo.InterpretationVO;
import com.bookcollector.publish.vo.PublishJobVO;

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

    /** 候选清单分页。{@code status} 为空表示全部 */
    PageResult<InterpretationVO> pageInterpretations(String status, Integer page, Integer size);

    // ==================================================================
    // 配置
    // ==================================================================

    /** 改 cron / enabled（{@code null} = 不改这一项）。非法 cron 直接拒绝 */
    PublishJobVO updateConfig(String cron, Boolean enabled);

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
}
