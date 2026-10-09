package com.bookcollector.publish.entity;

/**
 * AI 书籍解读的<b>单条记录状态</b>。
 *
 * <h3>为什么用一个 {@code status} 而不是「一个 boolean 标记是否已解读」</h3>
 * 用户 2026-10-09 的原话是「用一个字段标记是否已解读」。但 boolean 表达不了
 * 「正在生成」和「失败」—— 而那两种状态在页面上是要区分展示的：
 * <ul>
 *   <li>{@link #PENDING}：已进候选清单，等着被挑中；</li>
 *   <li>{@link #GENERATING}：正在生成/发布（进程被杀会残留，用户要求<b>不做</b>启动清理，
 *       页面上能看见就行 —— 行内「重置为待解读」按钮可以把它捞回来）；</li>
 *   <li>{@link #PUBLISHED}：已成功发布到语雀；</li>
 *   <li>{@link #FAILED}：生成或发布失败。<b>不再自动重试</b>（用户 2026-10-08 定），
 *       原因记在 {@code Interpretation.errorMsg} 里，想重来就手动点「重置为待解读」。</li>
 * </ul>
 * 加一个 boolean 只会多出一个可能与 {@code status} 不一致的来源。
 *
 * <p>⚠️ 「挑书」不依赖本枚举的 {@link #of(String)}，而是直接用字符串精确匹配
 * {@code status = "PENDING"} —— 只有「待解读」这一条路径会被挑中。
 */
public enum PublishStatus {

    /** 已进入候选清单，等待解读 */
    PENDING("待解读"),

    /** 正在生成 / 发布 */
    GENERATING("生成中"),

    /** 已成功发布到语雀 */
    PUBLISHED("已发布"),

    /** 生成或发布失败（不自动重试） */
    FAILED("失败");

    private final String label;

    PublishStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 宽松解析，<b>只用于展示</b>。
     *
     * <p>认不出（含 {@code null}）一律当 {@link #PENDING} —— 状态是给人看的，
     * 为一个读不出来的旧值抛异常、把整个列表打挂，得不偿失。
     */
    public static PublishStatus of(String value) {
        if (value == null || value.trim().isEmpty()) {
            return PENDING;
        }
        String v = value.trim().toUpperCase();
        for (PublishStatus s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return PENDING;
    }
}
