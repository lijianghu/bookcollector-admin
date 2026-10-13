package com.bookcollector.readinglist.entity;

/**
 * 阅读清单的<b>阅读状态</b>。
 *
 * <h3>为什么是三态而不是一个 boolean</h3>
 * 「想读 / 在读 / 已读」是阅读计划的自然阶段。用一个 boolean（读过/没读过）表达不了
 * 「已经在读」—— 而用户在「阅读清单」页面上要按这三种状态分别看。
 *
 * <h3>与 {@code PublishStatus} 的关系</h3>
 * 写法照抄 {@code publish.entity.PublishStatus}（label + 宽松/严格解析），
 * 但两者<b>毫无关系</b>：那是 AI 解读的进度，这是人的阅读进度。
 *
 * <p>⚠️ 存进 MongoDB 的是<b>枚举名</b>（{@code "WANT_TO_READ"}），不是 label。
 * 字段类型是 {@code String}（同 {@code shelf} 模块的简单类型风格），
 * 查询用字符串等值，不涉及枚举转换。
 */
public enum ReadingStatus {

    /** 想读：刚加进清单，还没开始 */
    WANT_TO_READ("想读"),

    /** 在读：正在读 */
    READING("在读"),

    /** 已读：读完了 */
    FINISHED("已读");

    /** 新加入阅读清单的书，状态一律从这里起步 */
    public static final ReadingStatus DEFAULT = WANT_TO_READ;

    private final String label;

    ReadingStatus(String label) {
        this.label = label;
    }

    /** 中文文案，给前端直接显示（VO 里的 {@code statusLabel}） */
    public String getLabel() {
        return label;
    }

    /**
     * <b>严格</b>解析 —— 只用于「用户提交的状态」这种必须校验的地方。
     *
     * <p>与 {@code PublishStatus.of()} 的宽松策略相反：那里是<b>读库里的旧值</b>用于展示，
     * 认不出也不能把列表打挂；这里是<b>入参校验</b>，非法就该报 400 而不是被静默改成「想读」
     * —— 否则用户点「标记为已读」失败时，会看到它变成了「想读」，比报错更让人困惑。
     *
     * @return 认不出（含 {@code null} / 空白）返回 {@code null}
     */
    public static ReadingStatus parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toUpperCase();
        if (v.isEmpty()) {
            return null;
        }
        for (ReadingStatus s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }

    /**
     * <b>宽松</b>解析，只用于展示。
     *
     * <p>认不出（含 {@code null}）一律当 {@link #WANT_TO_READ} —— 状态是给人看的，
     * 为一个读不出来的旧值抛异常、把整个列表打挂，得不偿失（同 {@code PublishStatus.of()}）。
     */
    public static ReadingStatus of(String value) {
        ReadingStatus parsed = parse(value);
        return parsed == null ? DEFAULT : parsed;
    }
}
