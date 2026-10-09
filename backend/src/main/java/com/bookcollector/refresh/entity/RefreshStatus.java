package com.bookcollector.refresh.entity;

/**
 * 定时刷新任务的状态。
 *
 * <p>它<b>同时</b>用于两个层级，这是刻意的：
 * <ul>
 *   <li><b>批次级</b>（{@code RefreshJob.lastRunStatus}）：
 *       {@link #IDLE} / {@link #RUNNING} / {@link #SUCCESS} / {@link #PARTIAL} /
 *       {@link #FAILED} / {@link #ABORTED} / {@link #INTERRUPTED}</li>
 *   <li><b>目标级</b>（{@code RefreshDetail.status}）：
 *       {@link #SUCCESS} / {@link #FAILED} / {@link #CANCELED} / {@link #ABORTED}</li>
 * </ul>
 * 两级的取值是<b>包含关系</b>而不是两套枚举 —— 它们描述的是同一件事
 * （「这次刷新跑得怎么样」），只是粒度不同。拆成两个枚举的话，前端要维护两张
 * 标签表，而其中一半的值是重复的。
 *
 * <p>⚠️ 这里<b>没有</b> {@code PAUSED}：刷新批次不可暂停。它每次从 0 跑到底
 * （{@code skipCursor=true}，没有断点续传语义），暂停之后恢复只能从头再来，
 * 那还不如直接取消。
 *
 * <p>⚠️ 这里<b>没有</b> {@code PENDING}：刷新任务没有「待执行」这个状态。
 * 它要么在跑（{@link #RUNNING}），要么是上一次跑完留下的结果。
 *
 * <p>为什么不放进 {@code common.enums}（{@code TaskStatus} 在那儿）：
 * 它只在 refresh 模块内部使用，没有跨模块共享的需求。
 * 放进公共包只会让人误以为别处也在用。
 */
public enum RefreshStatus {

    /** 从未执行过（新建的配置文档的初始值） */
    IDLE("从未执行"),

    /** 批次正在执行 */
    RUNNING("执行中"),

    /** 全部目标都成功 */
    SUCCESS("成功"),

    /** 一部分目标成功、一部分失败 */
    PARTIAL("部分失败"),

    /** 全部目标都失败 */
    FAILED("失败"),

    /** 被中止：某个目标正被手动采集占用，整批提前结束 */
    ABORTED("已中止"),

    /** 被取消：进程关闭时 TaskRegistry 取消了采集权 */
    CANCELED("已取消"),

    /** 被中断：JVM 重启，上一次的 RUNNING 是假死的 */
    INTERRUPTED("已中断");

    private final String label;

    RefreshStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 宽松解析。
     *
     * <p>认不出（含 {@code null}）一律当 {@link #IDLE}：状态只用于展示，
     * 为了一个读不出来的旧值抛异常、把整个页面打挂，是得不偿失的。
     */
    public static RefreshStatus of(String value) {
        if (value == null || value.trim().isEmpty()) {
            return IDLE;
        }
        String v = value.trim().toUpperCase();
        for (RefreshStatus s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return IDLE;
    }
}
