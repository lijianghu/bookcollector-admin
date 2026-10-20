package com.bookcollector.booklist.entity;

/**
 * 书单的<b>采书状态</b> —— 只回答一个问题：「这张书单里的书，采完了没」。
 *
 * <h3>🔴 为什么刻意<b>没有</b> {@code COLLECTING}</h3>
 * 方案文档 §3.1 里列的是四态（含 {@code COLLECTING}），这里收成三态，理由是
 * 「正在采」这件事已经有<b>唯一真相来源</b> —— 任务表（{@code collect_tasks.status
 * = RUNNING}）与采集权（{@code TaskRegistry}）。在书单表里再记一份，就是同一件事
 * 存在两个副本，而这带来一个必然的故障模式：
 *
 * <blockquote>
 * 应用在采集途中被杀（强杀 / 断电）→ 任务表那边由 {@code InterruptedTaskDetector}
 * 在下次启动时把假死的 {@code RUNNING} 标成 {@code INTERRUPTED}，
 * 但书单表里那条 {@code COLLECTING} <b>没有任何人会去清</b> ——
 * 于是这张书单永远显示「采书中」，而且界面据此把「采书」按钮禁掉，
 * 变成一个再也点不动的死结。
 * </blockquote>
 *
 * <p>所以：<b>「正在采」看任务，「采完了没」看本表</b>。两个维度各自只有一个写入者。
 *
 * <h3>存进 MongoDB 的是<b>枚举名</b></h3>
 * 字段类型是 {@code String}（同 {@code reading_list.status}），
 * 查询用字符串等值，合法性由 {@link #parse(String)} 守住。
 */
public enum BooklistCollectStatus {

    /** 待采：刚被链路 A 发现，还没采过书 */
    PENDING("待采"),

    /** 已采：链路 B 跑成功过。⚠️ 不代表「采到的书数 == 接口声明的书数」，两者对不上时看 {@code bookCountCollected} */
    COLLECTED("已采"),

    /** 失败：链路 B 跑挂了（网络 / 登录态 / 响应形状变了）。下次启动任务会重试 */
    FAILED("失败");

    /** 新发现的书单一律从这里起步 */
    public static final BooklistCollectStatus DEFAULT = PENDING;

    private final String label;

    BooklistCollectStatus(String label) {
        this.label = label;
    }

    /** 中文文案，给前端直接显示 */
    public String getLabel() {
        return label;
    }

    /**
     * <b>严格</b>解析 —— 用于「从库里读出来的值要拿去比较」这种地方。
     *
     * @return 认不出（含 {@code null} / 空白）返回 {@code null}，<b>不</b>回落成默认值
     */
    public static BooklistCollectStatus parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toUpperCase();
        if (v.isEmpty()) {
            return null;
        }
        for (BooklistCollectStatus s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }

    /**
     * <b>宽松</b>解析，只用于展示。
     *
     * <p>认不出（含 {@code null}）一律当 {@link #PENDING} —— 状态是给人看的，
     * 为一个读不出来的旧值抛异常、把整个列表打挂，得不偿失（同 {@code PublishStatus.of()}）。
     */
    public static BooklistCollectStatus of(String value) {
        BooklistCollectStatus parsed = parse(value);
        return parsed == null ? DEFAULT : parsed;
    }
}
