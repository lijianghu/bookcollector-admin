package com.bookcollector.publish.repository;

import com.bookcollector.publish.entity.AiModel;
import com.bookcollector.publish.entity.AiTaxonomyItem;
import com.bookcollector.publish.entity.AiYuqueToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 读取用户手工维护的三张 AI 配置表（程序<b>只读</b>）：
 * {@code ai_model} / {@code ai_yuque_token} / {@code ai_taxonomy_config}。
 *
 * <h3>🔴 敏感信息：这个类里永远不许出现 key / token 的内容</h3>
 * 用户 2026-10-09 明确要求。本类所有日志只允许出现
 * {@code name} / {@code model} / {@code baseUrl} 这类非敏感字段，
 * token 只允许打<b>长度</b>。{@link AiModel} / {@link AiYuqueToken} 上也加了
 * {@code @ToString.Exclude} 作为第二道防线。
 *
 * <p>另外这些值<b>只在这里读出来、在内存里传给客户端</b>，绝不写进任何
 * VO / 日志 / 异常信息。异常包装时也只带状态码，不带请求头。
 */
@Repository
public class AiConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(AiConfigRepository.class);

    /** {@code ai_model.type} 的固定取值。表里将来可能放别的类型，所以读的时候要筛 */
    private static final String MODEL_TYPE = "ai_model";

    private final MongoTemplate mongoTemplate;

    public AiConfigRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // ai_model
    // ==================================================================

    /**
     * 读当前启用的模型配置。
     *
     * <p>规则（用户 2026-10-09 定）：{@code type = "ai_model"} 且 {@code enable = true}
     * → 按 {@code sort} 正序 → 取第一条。
     *
     * @return 没有可用配置时返回 {@code null}，由调用方给出明确报错
     */
    public AiModel loadEnabledModel() {
        Query q = Query.query(Criteria.where("type").is(MODEL_TYPE)
                        .and("enable").is(true))
                .with(Sort.by(Sort.Direction.ASC, "sort"))
                .limit(1);
        AiModel model = mongoTemplate.findOne(q, AiModel.class);
        if (model == null) {
            // ⚠️ 用 debug 而不是 warn：页面每 2 秒轮询一次状态，warn 会变成刷屏日志
            log.debug("ai_model 里没有 enable=true 的配置，AI 生成将不可用");
        } else {
            // ⚠️ 只打非敏感字段。绝不打 apiKey
            log.debug("已加载 AI 模型配置：name={}, model={}, baseUrl={}",
                    model.getName(), model.getModel(), model.getBaseUrl());
        }
        return model;
    }

    // ==================================================================
    // ai_yuque_token
    // ==================================================================

    /**
     * 读语雀 Token。按 {@code sort} 正序取第一条（这张表没有 enable 字段）。
     *
     * @return 没有配置时返回 {@code null}
     */
    public AiYuqueToken loadYuqueToken() {
        Query q = new Query()
                .with(Sort.by(Sort.Direction.ASC, "sort"))
                .limit(1);
        AiYuqueToken token = mongoTemplate.findOne(q, AiYuqueToken.class);
        if (token == null) {
            // 同 loadEnabledModel：页面轮询会高频走到这里，所以用 debug
            log.debug("ai_yuque_token 里没有配置，语雀发布将不可用");
        } else {
            // ⚠️ 只打长度，绝不打内容
            int len = token.getToken() == null ? 0 : token.getToken().length();
            log.debug("已加载语雀 Token（长度 {}）", len);
        }
        return token;
    }

    // ==================================================================
    // ai_taxonomy_config
    // ==================================================================

    /**
     * 读分类白名单。
     *
     * <p>规则：{@code enabled != false}（{@code null} 视为参与），按 {@code sort} 正序。
     * <b>不筛 {@code type}</b> —— 表名已表达语义，少一个条件少一个失败点。
     *
     * <p>⚠️ Mongo 的 {@code $ne: false} 会匹配「字段不存在」的文档，
     * 所以没写 {@code enabled} 的行也会被算进来 —— 这正是我们要的。
     */
    public List<AiTaxonomyItem> listEnabledTaxonomy() {
        Query q = Query.query(Criteria.where("enabled").ne(false))
                .with(Sort.by(Sort.Direction.ASC, "sort"));
        return mongoTemplate.find(q, AiTaxonomyItem.class);
    }

    // ==================================================================
    // pick_taxonomy_config（发布范围）
    // ==================================================================

    /** 发布范围白名单集合名（结构同 {@code ai_taxonomy_config}） */
    private static final String PICK_TAXONOMY_COLLECTION = "pick_taxonomy_config";

    /**
     * 读「发布范围」白名单（集合 {@code pick_taxonomy_config}）。
     *
     * <p>规则（用户 2026-10-19 定）：{@code enabled = true}（严格等于 true，null / false 都不参与），
     * 按 {@code sort} 正序。实体 {@link AiTaxonomyItem} 的 {@code @Document} 指向
     * {@code ai_taxonomy_config}，这里用带 {@code collectionName} 的 {@code find} 重载读另一张同构表。
     */
    public List<AiTaxonomyItem> listEnabledPickTaxonomy() {
        Query q = Query.query(Criteria.where("enabled").is(true))
                .with(Sort.by(Sort.Direction.ASC, "sort"));
        return mongoTemplate.find(q, AiTaxonomyItem.class, PICK_TAXONOMY_COLLECTION);
    }
}
