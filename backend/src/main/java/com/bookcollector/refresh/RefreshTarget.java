package com.bookcollector.refresh;

import com.bookcollector.common.enums.TargetType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 定时刷新任务的<b>目标清单</b> —— 固定的 8 个，写死在代码里。
 *
 * <h3>为什么是这 8 个</h3>
 * 只有「会定期整体更新」的数据源才需要反复采：
 * <ul>
 *   <li><b>7 个榜单</b>：按天滚动，今天的飙升榜和昨天不是同一批书；</li>
 *   <li><b>期刊杂志（{@code CATEGORY:1700000}）</b>：按期上新。</li>
 * </ul>
 * 而普通分类（如「文学」）的历史数据基本不动，采一次就够了，所以不在清单里。
 * 用户 2026-10-08 明确圈定范围：「只这 8 个」，并且<b>不含</b>
 * {@code CATEGORY:1700001}（期刊杂志-财经）。
 *
 * <h3>为什么写死而不是做成字典 / 表单</h3>
 * 这是一个<b>冻结的清单</b>。做成可配置的话，用户改坏了范围只会表现为
 * 「刷新跑完了，但某个榜单一整期没更新」—— 这种静默失败最难查。
 * 要增减目标，改 {@link #ALL} 即可（改完必须重启后端）。
 *
 * <h3>🔴 为什么不用普通采集任务去做</h3>
 * 普通任务带<b>断点续传</b>语义（游标是「目标」的属性）。榜单每期换一批书，
 * 「续传」不但没意义，还会让第二次刷新只从上次的位置往后翻，
 * <b>漏掉排在前面的新上榜书</b>。
 *
 * <p>所以刷新走 {@code CollectCommand.skipCursor=true}：每次从 0 跑到底，
 * 且全程<b>不读、不写、不重置</b>游标 —— 用户手工建的榜单任务该怎么续传还怎么续传，
 * 两者互不干扰。详见 {@code CollectCommand#skipCursor}。
 */
public final class RefreshTarget {

    /** 采集目标类型：RANKING / CATEGORY */
    private final TargetType type;

    /** 目标 ID。榜单是英文串（rising），分类是数字串（1700000） */
    private final String targetId;

    /** 展示名，与字典 {@code taxonomy_config.name} 保持一致 */
    private final String name;

    private RefreshTarget(TargetType type, String targetId, String name) {
        this.type = type;
        this.targetId = targetId;
        this.name = name;
    }

    public TargetType getType() {
        return type;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getName() {
        return name;
    }

    /**
     * 固定清单：7 个榜单 + 期刊杂志。
     *
     * <p>顺序 = 刷新时的执行顺序 = 页面上表格的行序。榜单放在前面，
     * 因为它们是「每天都要看」的，越早跑完越早能看出新数据。
     *
     * <p>⚠️ 榜单的 {@code code} 必须与 {@code SeedRunner.RANKINGS} 逐字一致 ——
     * 它们会被原样拼进 URL（{@code /bookListInCategory/{code}?rank=1}）。
     */
    public static final List<RefreshTarget> ALL = Collections.unmodifiableList(Arrays.asList(
            new RefreshTarget(TargetType.RANKING, "rising", "飙升榜"),
            new RefreshTarget(TargetType.RANKING, "hot_search", "热搜榜"),
            new RefreshTarget(TargetType.RANKING, "newbook", "新书榜"),
            new RefreshTarget(TargetType.RANKING, "general_novel_rising", "小说飙升榜"),
            new RefreshTarget(TargetType.RANKING, "all", "总榜"),
            new RefreshTarget(TargetType.RANKING, "newrating_publish", "神作榜"),
            new RefreshTarget(TargetType.RANKING, "newrating_potential_publish", "潜力榜"),
            new RefreshTarget(TargetType.CATEGORY, "1700000", "期刊杂志")
    ));

    @Override
    public String toString() {
        return name + "(" + type.name() + ":" + targetId + ")";
    }
}
