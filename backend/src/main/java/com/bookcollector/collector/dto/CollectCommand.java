package com.bookcollector.collector.dto;

import com.bookcollector.collector.CollectControl;
import com.bookcollector.collector.CollectProgressListener;
import com.bookcollector.common.enums.TargetType;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Set;

/**
 * 一次采集的入参。
 *
 * <p>字段多到 6 个以上，用参数对象而不是方法签名 —— 否则调用处会变成
 * {@code run(a, b, c, null, d, false, e)} 这种读不懂的代码。
 */
@Getter
@Builder
public class CollectCommand {

    /** 归属的运行 ID（collect_task_runs.id）。用于进度上报与请求审计；可为 null */
    private String runId;

    /** 归属的任务 ID。用于日志归属；可为 null */
    private String taskId;

    /** 采集目标类型，必填 */
    private TargetType targetType;

    /** 采集目标 ID，必填。分类是 "300000"，榜单是 "rising" */
    private String targetId;

    /** 目标名称快照，如 "文学"。用于游标和日志展示 */
    private String targetName;

    /** 最大页数。{@code null} 或 {@code <= 0} 表示不限（一直翻到 hasMore=false） */
    private Integer maxPages;

    /**
     * 排除分类 code 集合。命中其中任一 code 的图书<b>不写库</b>。
     * {@code null} / 空集 = 不排除。
     *
     * <p>code 与 {@code Book.BookCategory} 的 {@code categoryId} / {@code subCategoryId}
     * 比对（一级二级混在同一份集合里，见 {@code collector.BookCategoryFilter}）。
     *
     * <p><b>为什么是 Set 而不是 List</b>：过滤是「每本书 × 它挂的每个分类标签」的查找，
     * 在采集主循环里逐页跑。一页 20 本、一本平均 2 个标签，用 List 也不是不能跑，
     * 但这是热路径，没理由不做成 O(1)。
     */
    private Set<String> excludeCategories;

    /** 是否忽略已有游标、从 0 开始重采。默认 false（续传） */
    private boolean resetCursor;

    /**
     * 是否<b>完全不碰游标</b>（不读、不重置、不推进）。默认 false。
     *
     * <p>定时刷新任务用这个模式：数据源（7 个榜单 + 期刊杂志）是<b>定期整体更新</b>的，
     * 没有「续传」这个概念 —— 每次都要从 0 跑到底。若沿用普通模式去写游标，
     * 会把用户正常采集留下的续传位置冲掉（游标是<b>目标</b>的属性，不是任务的属性）。
     *
     * <p>与 {@link #resetCursor} 的区别：{@code resetCursor} 是「先把游标归零，
     * 然后照常读写」—— 它会留下痕迹（{@code maxIndex=0}、{@code finished=false}、
     * {@code totalCollected=0}）；{@code skipCursor} 是「全程不碰游标」——
     * 采完之后 {@code collect_cursors} 里那一条<b>一个字节都没变</b>。
     */
    private boolean skipCursor;

    /** 暂停/取消控制信号。为 null 时等价于「不停不取消」 */
    private CollectControl control;

    /**
     * 逐页进度回调。为 null 时全程不做任何事。
     *
     * <p>定时刷新用它把「跑到第几页、写了多少本」实时回传 ——
     * 它没有 runId，走不了 {@code ProgressReporter} 那条落库的路
     * （见 {@link CollectProgressListener} 的说明）。
     */
    private CollectProgressListener progressListener;

    /** 采集来源标识，形如 {@code "category:300000"}。写入 Book.collectSource */
    public String sourceKey() {
        return targetType == null ? null
                : targetType.name().toLowerCase() + ":" + targetId;
    }

    /** 归一化后的最大页数：{@code <= 0} 一律当成「不限」，用 -1 表示 */
    public int effectiveMaxPages() {
        if (maxPages == null || maxPages <= 0) {
            return -1;
        }
        return maxPages;
    }

    public CollectControl controlOrDefault() {
        return control == null ? CollectControl.NOOP : control;
    }

    /**
     * 归一化后的排除集合，<b>永不为 null</b>。
     *
     * <p>采集主循环是热路径，让它少一次判空。空集时 {@code contains()} 恒 false，
     * 等价于「不排除」，不需要再额外判断「要不要过滤」。
     */
    public Set<String> excludeCategoriesOrEmpty() {
        return excludeCategories == null ? Collections.<String>emptySet() : excludeCategories;
    }

    /** 入参自检。缺失必填项时早失败，别跑到一半才发现 */
    public void validate() {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType 不能为空");
        }
        if (targetId == null || targetId.trim().isEmpty()) {
            throw new IllegalArgumentException("targetId 不能为空");
        }
    }
}
