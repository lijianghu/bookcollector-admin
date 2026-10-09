package com.bookcollector.refresh.service;

import com.bookcollector.refresh.vo.RefreshJobVO;

/**
 * 定时刷新任务（7 个榜单 + 期刊杂志）。
 *
 * <h3>它和「采集任务」是什么关系</h3>
 * 两者<b>共享同一套采集内核</b>（{@code CollectLoop}）和同一把目标锁
 * （{@code TaskRegistry}，key = {@code targetType:targetId}），
 * 但<b>不共享</b>任务编排层：
 * <ul>
 *   <li>普通任务走 {@code TaskService → TaskRunner}：有 {@code CollectTask} 状态机、
 *       有 {@code TaskRun} 流水、有断点续传（游标是目标的属性）</li>
 *   <li>刷新任务走本类：没有任务实体、没有运行流水、<b>完全不碰游标</b>
 *       （{@code CollectCommand.skipCursor=true}）</li>
 * </ul>
 * 为什么不复用 {@code TaskRunner}：它和任务模型是硬绑定的 —— 它要写
 * {@code CollectTask.status}、写 {@code TaskRun}，而刷新任务<b>没有</b>这两样东西。
 * 为了复用去造一个「假任务」，等于把「目标 1:1 任务」这个前提弄脏
 * （{@code collect_tasks} 上还有 {@code (targetType, targetId)} 唯一索引）。
 *
 * <h3>🔴 为什么刷新任务不能有断点续传</h3>
 * 榜单每期换一批书。「从上次的位置继续翻」会<b>漏掉排在前面的新上榜书</b> ——
 * 而漏掉的那些恰恰是用户最想看到的。所以刷新每次都必须从 0 跑到底。
 *
 * <p>又因为游标是<b>目标</b>的属性（用户手工建的榜单任务也在用它），
 * 刷新任务如果去写游标，就会把手工任务的续传位置冲掉。所以是「全程不碰」，
 * 而不是「重置后再写」。
 *
 * <h3>三种终止方式</h3>
 * <ol>
 *   <li><b>跑完</b>：8 个目标依次采到底</li>
 *   <li><b>中止整批</b>：某个目标正被手动采集占用（或全局并发闸门已满）→
 *       立刻停止，不再尝试后面的目标。文案「定时任务被手动采集占用，已中止」</li>
 *   <li><b>部分失败</b>：单个目标网络失败<b>不</b>中止整批，记下来继续下一个</li>
 * </ol>
 *
 * <p>⚠️ 「中止整批」<b>不回滚</b>已经跑完的目标 —— 它们是幂等 upsert 的产物，
 * 数据是对的，回滚反而会造成「刷新了但没生效」的假象。
 * 这个批次不是原子的，是刻意如此。
 */
public interface RefreshService {

    /**
     * 页面全量数据：配置 + 内存态 + 目标清单 + 最近一次结果。
     *
     * <p>前端靠轮询它来跟进手动触发的进度。
     */
    RefreshJobVO getJob();

    /**
     * 改配置。两个参数都可为 null（表示「不改这一项」）。
     *
     * @throws com.bookcollector.common.BizException cron 非法，或两个参数都为 null
     */
    RefreshJobVO updateConfig(String cron, Boolean enabled);

    /**
     * 手动「立即执行」。
     *
     * <p><b>立即返回</b>（不等采集跑完）—— 采集要跑 1~2 分钟，
     * 让 HTTP 请求挂那么久既会撞 30 秒超时，也没法给用户任何进度反馈。
     * 所以这里只负责「抢占批次 + 写状态 + 丢进线程」，然后返回当前快照；
     * 前端拿到 {@code running=true} 就开始轮询 {@link #getJob()}。
     *
     * @throws com.bookcollector.common.BizException 已有批次在跑
     */
    RefreshJobVO trigger();

    // ==================================================================
    // 以下两个方法供 RefreshScheduler 使用，不对 HTTP 暴露
    // ==================================================================

    /**
     * 当前生效的 cron。
     *
     * <p>定时触发器<b>每次都重新读它</b>，所以改完配置不需要重启后端 ——
     * 下一次触发就用新表达式。
     */
    String currentCron();

    /**
     * cron 触发入口。
     *
     * <p>未启用、或已有批次在跑时直接返回（不抛异常）—— 定时触发是<b>无人值守</b>的，
     * 抛异常只会在日志里留一条没人看的堆栈。
     */
    void runScheduled();
}
