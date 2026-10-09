package com.bookcollector.book.dto;

/**
 * 一次批量 upsert 的结果拆分：<b>更新了多少本</b>、<b>新增了多少本</b>。
 *
 * <h3>为什么放在 {@code book.dto} 而不是 {@code collector.dto}</h3>
 * 它是 {@code BookRepository.upsertMany} 的返回值，由 {@code collector} 侧的
 * {@code BookWriter} / {@code CollectLoop} 消费。{@code collector} 本来就依赖
 * {@code book}（entity / repository），反过来则<b>一次都没有</b> ——
 * 放在这里依赖方向不变；放进 {@code collector.dto} 会让 {@code book} 反向依赖
 * {@code collector}，形成包循环。
 *
 * <h3>为什么要拆开</h3>
 * 原先 {@code BookRepository.upsertMany} 只返回 {@code matched + upserted} 一个总数。
 * 但整期榜单（200 本）会被重写一遍，其中绝大多数是<b>老书更新</b> ——
 * 只看总数会让人以为「这次采回来 200 本新书」，而实际新增可能只有几本。
 * 用户 2026-10-08 明确要求：<b>要看到增量，而不是拿总数去和「今日新增」对比</b>。
 *
 * <h3>数字从哪来（零额外成本）</h3>
 * {@code BulkWriteResult} 本来就同时给了两个数：
 * <ul>
 *   <li>{@code getMatchedCount()} —— 命中已有文档的条数（= 更新）</li>
 *   <li>{@code getUpserts().size()} —— 没命中、真正插入的条数（= 新增）</li>
 * </ul>
 * 原先是把两者相加后把区分丢掉了，并没有多做任何一次查询。
 *
 * <p>⚠️ <b>口径</b>：新增 = 库里原本没有这个 {@code bookId}。
 * 所以「手动删掉一本书、下次又采到它」会重新算作新增 —— 这符合直觉。
 *
 * @see com.bookcollector.book.repository.BookRepository#upsertMany
 */
public class UpsertStat {

    /** 命中了已有文档的条数（= 更新） */
    private final int updated;

    /** 没命中、实际插入的条数（= 新增） */
    private final int inserted;

    public UpsertStat(int updated, int inserted) {
        this.updated = updated;
        this.inserted = inserted;
    }

    /** 全零。空入参、跳过全部记录时用 */
    public static UpsertStat zero() {
        return new UpsertStat(0, 0);
    }

    public int getUpdated() {
        return updated;
    }

    public int getInserted() {
        return inserted;
    }

    /**
     * 本次处理的条数（更新 + 新增）。
     *
     * <p>与旧版 {@code upsertMany} 的返回值同口径 —— 「写入/更新」这个展示口径不变。
     */
    public int total() {
        return updated + inserted;
    }

    /**
     * 累加另一份结果。
     *
     * <p>{@code BookWriter} 按 500 本分片写入，用它把各批结果合并成一份。
     * {@code null} 视为「没发生」，原样返回自己（避免调用方到处判空）。
     */
    public UpsertStat plus(UpsertStat other) {
        if (other == null) {
            return this;
        }
        return new UpsertStat(updated + other.updated, inserted + other.inserted);
    }

    @Override
    public String toString() {
        return "UpsertStat{更新=" + updated + ", 新增=" + inserted + "}";
    }
}
