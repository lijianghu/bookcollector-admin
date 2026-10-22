package com.bookcollector.paid.repository;

import com.bookcollector.paid.entity.PaidCollectState;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/**
 * 付费精选断点 / 状态的读写（2026-10-20）。
 *
 * <p>{@code _id = filterCategory}，直接按主键读写，不需要额外索引。
 */
@Repository
public class PaidCollectStateRepository {

    private final MongoTemplate mongoTemplate;

    public PaidCollectStateRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public PaidCollectState find(String filterCategory) {
        if (isBlank(filterCategory)) {
            return null;
        }
        return mongoTemplate.findById(filterCategory.trim(), PaidCollectState.class);
    }

    public List<PaidCollectState> listAll() {
        return mongoTemplate.findAll(PaidCollectState.class);
    }

    /** 整条保存（启动 / 每页 / 完成时用） */
    public void save(String filterCategory, String name, int maxIdx, int page,
                     Integer totalCount, String lastStatus) {
        if (isBlank(filterCategory)) {
            return;
        }
        PaidCollectState state = PaidCollectState.builder()
                .id(filterCategory.trim())
                .filterCategory(filterCategory.trim())
                .filterCategoryName(name)
                .lastStatus(lastStatus)
                .maxIdx(maxIdx)
                .page(page)
                .totalCount(totalCount)
                .updatedAt(new Date())
                .build();
        mongoTemplate.save(state);
    }

    /** 只改状态（暂停 / 继续 / 停止 / 失败时用），不动断点 */
    public void updateStatus(String filterCategory, String lastStatus) {
        if (isBlank(filterCategory)) {
            return;
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(filterCategory.trim())),
                Update.update("lastStatus", lastStatus).set("updatedAt", new Date()),
                PaidCollectState.class);
    }

    /**
     * 启动迁移：把上次进程遗留的「运行中」改成「已中断」。
     *
     * <p>兼容旧版：旧代码只写 maxIdx、不写 lastStatus，所以
     * 「lastStatus 为空且 maxIdx &gt; 0」也视为中断。
     */
    public void markInterruptedOnStartup() {
        Query q = Query.query(new Criteria().orOperator(
                Criteria.where("lastStatus").is("RUNNING"),
                new Criteria().andOperator(
                        Criteria.where("lastStatus").is(null),
                        Criteria.where("maxIdx").gt(0))));
        mongoTemplate.updateMulti(q, Update.update("lastStatus", "INTERRUPTED"),
                PaidCollectState.class);
    }

    public void delete(String filterCategory) {
        if (isBlank(filterCategory)) {
            return;
        }
        mongoTemplate.remove(
                Query.query(Criteria.where("_id").is(filterCategory.trim())),
                PaidCollectState.class);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
