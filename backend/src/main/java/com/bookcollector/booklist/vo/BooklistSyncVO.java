package com.bookcollector.booklist.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 「同步书单」（链路 A）的进度快照。
 *
 * <h3>为什么是内存快照而不是一张表</h3>
 * 同 {@code publish} 模块的两条长任务：同步一次要跑 16 个分类、几百次请求，
 * 但它的产物是 {@code booklists} 表（那里才是真相），进度只是「给人看的」。
 * 为它建表会多出一张永远只有一行的表，而且进程重启后残留的「运行中」还得清理。
 * 内存快照的代价是重启即归零 —— 对一次几分钟的同步来说完全可以接受。
 *
 * <h3>为什么「提交即返回 + 轮询」而不是同步等</h3>
 * 一次全量同步可能几十秒到几分钟。同步等会让 HTTP 请求一直挂着，
 * 容易被网关/浏览器掐断。用户 2026-10-09 在 publish 模块上明确过这条要求
 * （「异步，并可以看到进度，不要卡住一直等待」），这里照同一套做法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BooklistSyncVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否正在跑。前端据此决定「同步书单」按钮要不要禁用、要不要继续轮询 */
    private boolean running;

    /**
     * 本次要遍历的分类总数 —— 用户可能只勾了其中几个，所以它<b>不固定是 16</b>。
     *
     * <p>「没在跑」的快照里它是 16（全量清单的条数），只作展示用；
     * 真正的分母以本次运行开始时的取值为准。
     */
    private int totalCategories;

    /** 已跑完的分类数 */
    private int categoriesDone;

    /** 当前正在跑的分类 ID。没在跑时为 null */
    private String currentCategoryId;

    /** 当前正在跑的分类名，如「历史」。没在跑时为 null */
    private String currentCategoryTitle;

    /** 已发出的请求页数 */
    private int pagesFetched;

    /** 扫到的书单条数（含被闸门挡掉的） */
    private int booklistsSeen;

    /** 入库条数（更新 + 新增） */
    private int booklistsSaved;

    /** 其中新增条数 */
    private int booklistsInserted;

    /** 被闸门挡掉的条数（书籍数 &lt; 50，或收藏人数 &lt; 10000） */
    private int booklistsSkipped;

    /**
     * 第 1 页就返回空列表的分类数 —— <b>正常应为 0</b>。
     *
     * <p>存在的理由：2026-10-14 用户点「历史」同步，得到「扫到 0 张书单」，
     * 而「历史」实际有 4264 张（根因是 tag 参数编码少了一个字）。
     * 那次失败之所以没被认出来，是因为「服务端返回空」与「该分类真的没书单」
     * 在界面上完全一样。这个字段把前者单独暴露出来，
     * 让「扫到 0 张」永远带着一句「有 N 个分类第 1 页就是空的」。
     */
    private int emptyFirstPageCategories;

    /** 当前状态的一句话描述，直接显示在页面上 */
    private String message;

    /** 本次开始时间（毫秒时间戳）。没跑过时为 null */
    private Long startedAtMs;

    /** 本次结束时间（毫秒时间戳）。没跑过 / 还在跑时为 null */
    private Long finishedAtMs;

    /** 失败原因。成功时为 null */
    private String errorMsg;
}
