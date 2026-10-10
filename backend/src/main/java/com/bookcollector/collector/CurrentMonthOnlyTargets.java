package com.bookcollector.collector;

import com.bookcollector.common.enums.TargetType;

import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 「只采本月」的目标清单 —— 目前是<b>期刊杂志</b>这一类。
 *
 * <h3>为什么需要它</h3>
 * 期刊杂志按期上新，一次全量重采会把<b>历史上所有月份</b>的期号都拉回来。
 * 用户 2026-10-10 先把口径定为「只采今年」，随后<b>收紧为「只采本月」</b>。
 * 数据源接口<b>没有日期参数</b>（URL 只有 {@code maxIndex}），
 * 只能在采集链路里按 {@code Book.publishTime} 过滤 —— 见 {@code CollectCommand#publishMonth}。
 *
 * <h3>为什么收在这一份清单里，而不是写在两个调用方</h3>
 * 需要它的是两个地方：定时刷新的「期刊杂志」（{@code CATEGORY:1700000}）与
 * 「任务配置」里的「期刊杂志-财经」任务（{@code CATEGORY:1700001}）。
 * 同一个 code 抄两遍必然漂移，所以在这里做<b>单一真相</b>。
 *
 * <p>⚠️ 清单是<b>写死</b>的（同 {@code RefreshTarget} 的思路）：做成可配置的话，
 * 改错了只会表现为「某期期刊悄悄少采了」，那是最难查的一类静默失败。
 * 要增减目标，改 {@link #CODES}（改完必须重启后端）。
 *
 * <p>⚠️ 本类刻意<b>不依赖 refresh / task 模块</b> —— refresh 是叶子模块
 * （没有任何模块依赖它），反过来依赖它会形成包循环。这里只依赖
 * {@code common.enums.TargetType}。
 */
public final class CurrentMonthOnlyTargets {

    /** 只采本月的目标 code：期刊杂志（一级 1700000）与期刊杂志-财经（二级 1700001） */
    private static final Set<String> CODES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("1700000", "1700001")));

    private CurrentMonthOnlyTargets() {
    }

    /** 这个目标是不是「只采本月」 */
    public static boolean matches(TargetType type, String targetId) {
        return type == TargetType.CATEGORY && targetId != null && CODES.contains(targetId);
    }

    /**
     * 该目标要过滤的月份：命中清单时返回<b>当前月</b>（形如 {@code "2026-10"}），
     * 否则 {@code null}（不过滤）。
     *
     * <p>直接喂给 {@code CollectCommand.publishMonth}。
     */
    public static String publishMonthOrNull(TargetType type, String targetId) {
        return matches(type, targetId) ? YearMonth.now().toString() : null;
    }
}
