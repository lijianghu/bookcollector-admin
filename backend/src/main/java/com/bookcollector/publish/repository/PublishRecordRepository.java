package com.bookcollector.publish.repository;

import com.bookcollector.common.PageResult;
import com.bookcollector.publish.entity.PublishRecord;
import com.bookcollector.util.PageQueryUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Date;

/**
 * 发布记录（{@code publish_record}）的 Mongo 访问。
 *
 * <h3>写入点只有一个</h3>
 * {@code PublishServiceImpl} 在<b>发布成功</b>之后调 {@link #insert(PublishRecord)}。
 * 失败不留记录 —— 这个集合的语义是「已经发到语雀的东西」，
 * 失败的那次没发出去，不该出现在这里（失败原因在候选清单的 {@code errorMsg} 里）。
 *
 * <h3>排序为什么带 {@code _id} 兜底</h3>
 * 同 {@code InterpretationRepository.page}：定时批量发布时多条记录的
 * {@code publishedAt} 可能是同一毫秒，只按它排序翻页会出现重复/漏行。
 */
@Repository
public class PublishRecordRepository {

    private static final Logger log = LoggerFactory.getLogger(PublishRecordRepository.class);

    private final MongoTemplate mongoTemplate;

    public PublishRecordRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 插入一条发布记录。
     *
     * <p>用 {@code insert} 而不是 {@code save}：每次发布都该是<b>新的一行</b>。
     * {@code save} 在 {@code _id} 有值时会覆盖，语义是「更新」—— 这里永远不该更新历史。
     *
     * @return 写入后的实体（{@code id} 已由 Mongo 生成）
     */
    public PublishRecord insert(PublishRecord record) {
        if (record == null) {
            return null;
        }
        if (record.getPublishedAt() == null) {
            record.setPublishedAt(new Date());
        }
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(new Date());
        }
        mongoTemplate.insert(record);
        log.info("已写入发布记录：《{}》→ {}", record.getTitle(), record.getYuqueUrl());
        return record;
    }

    /** 分页，按发布时间倒序（最新的在前） */
    public PageResult<PublishRecord> page(Integer page, Integer size) {
        Sort sort = Sort.by(Sort.Direction.DESC, "publishedAt")
                .and(Sort.by(Sort.Direction.DESC, "_id"));
        return PageQueryUtil.getPageResult(mongoTemplate, page, size,
                new Criteria(), PublishRecord.class, sort);
    }
}
