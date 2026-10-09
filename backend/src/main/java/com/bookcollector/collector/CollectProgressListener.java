package com.bookcollector.collector;

/**
 * 采集进度的<b>逐页</b>回调。
 *
 * <h3>为什么要有它（而不是复用 {@link ProgressReporter}）</h3>
 * {@code ProgressReporter} 走的是「往 {@code collect_task_runs} 写进度快照」这条路 ——
 * 只有<b>真实任务</b>（有 runId）才用得上。定时刷新没有 runId，也没有任务实体，
 * 但它同样需要「跑到哪了」的实时视图。
 *
 * <p>所以这里开一条<b>纯内存</b>的旁路：不落库、不产生任何记录，
 * 由调用方自己决定怎么消费（定时刷新是换进一个内存快照供接口读取）。
 *
 * <h3>⚠️ 实现约定</h3>
 * <ul>
 *   <li>在<b>采集线程</b>里同步调用，实现必须<b>快</b>（每次只换一个对象引用，
 *       不要在里面查库、发 HTTP）</li>
 *   <li>不要抛异常 —— {@code CollectLoop} 虽然兜住了 {@code RuntimeException} 并记 warn，
 *       但异常意味着这一页的进度丢了</li>
 *   <li>回调只在<b>真正写入了一页</b>之后触发；空页 / 请求失败的那一页不触发
 *       （那一页没有任何写入，报进度没有意义）</li>
 * </ul>
 *
 * @see com.bookcollector.collector.dto.CollectCommand#getProgressListener()
 */
public interface CollectProgressListener {

    /**
     * 每采完一页调一次。
     *
     * @param pagesDone     已完成的页数
     * @param booksSaved    累计写入/更新本数（含新增）
     * @param booksInserted 其中累计新增的本数。更新数 = {@code booksSaved - booksInserted}
     * @param totalCount    接口报告的该目标总书数，可能为 null
     */
    void onProgress(int pagesDone, int booksSaved, int booksInserted, Integer totalCount);
}
