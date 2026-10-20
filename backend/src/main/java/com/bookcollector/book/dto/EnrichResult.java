package com.bookcollector.book.dto;

import lombok.Data;

/**
 * 「完善书籍信息」的<b>进度快照</b>（2026-10-16）。
 *
 * <h3>为什么是内存快照而不是一张表</h3>
 * 同 {@code booklist} / {@code publish} 模块的长任务：产物是 {@code books} 表
 * （那里才是真相），进度只是「给人看的」。为它建表会多出一张永远只有一行的表，
 * 而且进程重启后残留的「运行中」还得清理。内存快照重启即归零 ——
 * 对一次几分钟的补齐完全可以接受。
 *
 * <h3>为什么「提交即返回 + 轮询」而不是同步等</h3>
 * 一页最多 200 本、5 并发 + 每请求随机 1~5 秒，可能要几分钟。同步等会让 HTTP
 * 一直挂着，容易被网关/浏览器掐断（用户 2026-10-16：「前端会超时的」）。
 * 所以照 {@code BooklistSyncService} 那套做：提交立即返回快照，前端轮询
 * {@code GET /api/books/enrich/progress}。
 *
 * <p>各个计数<b>互斥</b>，每本书只落进一个桶：
 *
 * <pre>
 * requested = 去重后的入参书数
 * ├─ skipped  库里没有 / 不是今天采集的 / 什么都不缺
 * ├─ fetched  需要调 book/info 的（下面的 updated / failed 是它的结果）
 * │   ├─ updated  接口拿到新数据并写库
 * │   └─ failed   接口失败 / 错误信封 / 解析不出来
 * └─ derived  不用调接口，只靠 categories[0].title 本地派生出 category
 * </pre>
 */
@Data
public class EnrichResult {

    /** 是否正在跑。前端据此决定按钮要不要禁用、要不要继续轮询 */
    private boolean running;

    /** 当前状态的一句话描述，直接显示在页面上 */
    private String message;

    /** 开始时间（毫秒时间戳）。从没跑过时为 0 */
    private long startedAtMs;

    /** 结束时间（毫秒时间戳）。运行中为 0 */
    private long finishedAtMs;

    /** 去重后的入参书数（进度条的<b>分母</b>） */
    private int requested;

    /** 已处理完的书数（进度条的<b>分子</b>）= skipped + derived + updated + failed */
    private int processed;

    /** 需要调 book/info 的书数（= updated + failed） */
    private int fetched;

    /** 调接口成功且写库的书数 */
    private int updated;

    /** 调接口失败的书数（网络 / 非 200 / 错误信封 / 解析失败） */
    private int failed;

    /** 未调接口、只本地派生 category 的书数 */
    private int derived;

    /** 跳过的书数（库里没有 / 不是今天采集的 / 不缺任何字段） */
    private int skipped;

    /** 总耗时（毫秒） */
    private long costMs;
}
