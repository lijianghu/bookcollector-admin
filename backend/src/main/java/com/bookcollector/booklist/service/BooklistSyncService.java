package com.bookcollector.booklist.service;

import com.bookcollector.booklist.vo.BooklistBackfillVO;
import com.bookcollector.booklist.vo.BooklistSyncVO;

import java.util.List;

/**
 * 「同步书单」—— 链路 A：按平台分类分页发现书单，写进 {@code booklists}。
 *
 * <h3>它做什么</h3>
 * 遍历指定的平台分类（默认固定的 16 个），对每个分类：
 * <pre>
 * maxIdx = 0
 * loop:
 *   GET /booklist/category?categoryId={cid}&amp;count=10&amp;maxIdx={maxIdx}
 *                           &amp;rnVersion=1&amp;tag=%E6%9C%80%E6%94%B6%E8%97%8F   ← 「最多收藏」
 *   if errcode == -2012: 中止，报「登录超时」                       ← skey 失效
 *   for 每条 datas[]（接口按收藏人数降序返回）:
 *       if collectCount &lt; 10000:  →  🔴 本分类的翻页到此为止（后面都不会达标）
 *       if totalCount  &lt; 50:      →  跳过这一条
 *       upsert 进 booklists（按 booklistId 幂等，platformCategories 累加）
 *   if 本页条数 &lt; count: break
 *   maxIdx += count
 * </pre>
 *
 * <h3>🔴 提前终止是它跑得动的前提</h3>
 * 单个分类的书单总数可以到 2000（抓包样例），按 {@code count=10} 要翻 200 页，
 * 16 个分类就是 3200 次请求。带 {@code tag=最多收藏} 后接口按收藏人数降序返回，
 * 所以「某条不达标 → 后面都不达标」成立，实际只翻到阈值附近就停。
 * 这条推理<b>依赖「有序」</b>：{@code tag} 一旦漏掉，就会漏采中间达标的大书单
 * 且没有任何报错（见 {@code WereadClient.BOOKLIST_CATEGORY_TAG} 的说明）。
 *
 * <h3>⚠️ 这个闸门<b>只</b>作用于「分类书单」</h3>
 * 「我的书单」（用户自己的 124 张，由一次性脚本预置）是用户指定的，
 * 不筛「≥50 本 / ≥10000 收藏」—— 那些书单本来就不大。
 *
 * <h3>🔴 不建任务、不落游标</h3>
 * {@code maxIdx} 是「本次扫到第几条」，而且有提前终止，每次扫的深度都不一样 ——
 * 跨次复用没有意义。所以它是书单配置页上的一个「开始同步」按钮，
 * 提交即返回、靠轮询看进度（同 {@code PublishService#collectCandidates} 的做法）。
 *
 * <h3>它还负责一件事：书单名称回填（{@link #ensureName}）</h3>
 * 链路 B（按书单采书）启动前要用书单名称。而「我的书单」那 124 张是由一次性脚本
 * 按 ID 预置的，<b>库里只有 ID、没有名称</b> —— 用户 2026-10-14 要求
 * 「任务中点击启动的时候，如果书单只有 id 没有名称，回填更新」。
 * 这个动作要发一次 {@code /booklist/single} 请求 + 写一次库，与本接口现有的
 * 依赖（{@code WereadClient} + {@code BooklistRepository}）完全重合，
 * 所以放在这里而不是新开一个只有两个方法的服务。
 */
public interface BooklistSyncService {

    /**
     * 启动一次同步。已有同步在跑时抛业务异常。
     *
     * <p><b>提交即返回</b>，不等跑完 —— 进度看 {@link #progress()}。
     *
     * @param categoryIds 本次要同步的平台分类 ID（形如 {@code "200000"} / {@code "topic"}）。
     *                    {@code null} / 空 = 全部 16 个。
     *                    <p>⚠️ 出现清单外的 ID 时<b>抛业务异常</b>（不是静默忽略）——
     *                    见 {@code BooklistCategories#byIds(java.util.Collection)}。
     */
    BooklistSyncVO sync(List<String> categoryIds);

    /** 当前进度快照。没跑过时返回一份「全零、未运行」的对象，不返回 null */
    BooklistSyncVO progress();

    /**
     * 确保书单元数据已回填 —— 名称或关键计数缺失时去 {@code /booklist/single} 取一次，
     * 把名称 / 简介 / 作者 / 计数 / 时间整条写回库（2026-10-20 起不再只写 name）。
     *
     * <h3>什么时候会缺名称</h3>
     * 「我的书单」那 124 张由 {@code tools/seed_booklists.py} 按 ID 预置，
     * 库里只有 ID。链路 A 发现的书单自带名称，不会缺。
     *
     * <h3>🔴 它<b>不抛异常</b>，取不到就返回 null</h3>
     * 它是「启动任务」路上的一个<b>顺带动作</b>，不是启动的前提条件 ——
     * 回填失败（网络抖动、登录超时、响应形状变了）不该让任务启不起来。
     * 调用方拿到 null 时继续用原来的名称（ID）即可。
     *
     * <p>另外：<b>已经名称缺失也不算失败</b>。接口对某些书单可能本来就不给名称，
     * 那时会把 {@code name} 写成空 —— 下次启动再试一次，代价是一次请求。
     *
     * @param booklistId 书单 ID
     * @return 回填后的名称；本来就有名称时原样返回；查不到 / 取不到时返回 {@code null}
     */
    String ensureName(String booklistId);

    /**
     * 强制刷新书单元数据（2026-10-20）：不判断是否已完整，直接拉接口并覆盖写回。
     * 供「一键完善书单信息」用（用户要求已有名称/计数也要覆盖更新）。
     */
    String refreshMetadata(String booklistId);

    /**
     * 一键完善所有书单（2026-10-20）。
     *
     * <p>异步：提交即返回，进度看 {@link #backfillProgress()}。
     * 与同步共用互斥位（同一时刻只跑一个）。
     */
    BooklistBackfillVO backfillMetadata();

    /** 「一键完善书单信息」的进度快照。没跑过时返回「未运行」的对象 */
    BooklistBackfillVO backfillProgress();
}
