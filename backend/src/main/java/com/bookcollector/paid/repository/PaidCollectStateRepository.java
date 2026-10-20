package com.bookcollector.paid.repository;

import com.bookcollector.paid.entity.PaidCollectState;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Date;

/**
 * 付费精选断点的读写（2026-10-20）。
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
        if (filterCategory == null || filterCategory.trim().isEmpty()) {
            return null;
        }
        return mongoTemplate.findById(filterCategory.trim(), PaidCollectState.class);
    }

    public void save(String filterCategory, int maxIdx, int page, Integer totalCount) {
        if (filterCategory == null || filterCategory.trim().isEmpty()) {
            return;
        }
        PaidCollectState state = PaidCollectState.builder()
                .id(filterCategory.trim())
                .filterCategory(filterCategory.trim())
                .maxIdx(maxIdx)
                .page(page)
                .totalCount(totalCount)
                .updatedAt(new Date())
                .build();
        mongoTemplate.save(state);
    }

    public void delete(String filterCategory) {
        if (filterCategory == null || filterCategory.trim().isEmpty()) {
            return;
        }
        mongoTemplate.remove(
                Query.query(Criteria.where("_id").is(filterCategory.trim())),
                PaidCollectState.class);
    }
}
