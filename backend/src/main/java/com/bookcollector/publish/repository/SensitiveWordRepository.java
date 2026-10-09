package com.bookcollector.publish.repository;

import com.bookcollector.publish.entity.SensitiveWord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 读取敏感词表（{@code ai_sensitive_words}，用户手工维护，程序<b>只读</b>）。
 *
 * <h3>🔴 排序为什么在这里做，而不是交给 Mongo</h3>
 * 替换必须<b>按词长降序</b>（长词先替），否则会替换出半截词：
 * <pre>
 *   先替「极权」→「极权主义」变成「绝对权力主义」
 *   先替「斯大林」→「苏联斯大林时代」变成「苏联特定历史人物时代」
 * </pre>
 * 而 Mongo <b>不支持</b>按「字段值长度」排序（要写得用 {@code $strLenCP} 聚合）。
 * 词表只有几十条，拉回内存排一次最省事，也最好读。
 *
 * <p>{@code sort} 字段只决定页面展示顺序，<b>不参与</b>替换顺序。
 */
@Repository
public class SensitiveWordRepository {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordRepository.class);

    private final MongoTemplate mongoTemplate;

    public SensitiveWordRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 取全部启用的敏感词，<b>已按词长降序排好</b>，可直接顺序替换。
     *
     * <p>过滤规则：{@code enabled != false}（{@code null} 视为启用）；
     * {@code word} 为空或 {@code replacement} 为空的条目直接丢掉
     * （留着只会在替换时产生空串或空替换）。
     */
    public List<SensitiveWord> listEnabledByLengthDesc() {
        Query q = Query.query(Criteria.where("enabled").ne(false));
        List<SensitiveWord> all = mongoTemplate.find(q, SensitiveWord.class);

        List<SensitiveWord> usable = new ArrayList<SensitiveWord>();
        for (SensitiveWord w : all) {
            if (w == null || w.getWord() == null || w.getWord().trim().isEmpty()) {
                continue;
            }
            if (w.getReplacement() == null || w.getReplacement().isEmpty()) {
                log.warn("敏感词「{}」没有配置替换词，已跳过", w.getWord());
                continue;
            }
            usable.add(w);
        }

        // 词长降序；同长度按 word 字典序，保证结果稳定可复现
        Collections.sort(usable, new Comparator<SensitiveWord>() {
            @Override
            public int compare(SensitiveWord a, SensitiveWord b) {
                int byLen = Integer.compare(b.getWord().length(), a.getWord().length());
                return byLen != 0 ? byLen : a.getWord().compareTo(b.getWord());
            }
        });

        log.debug("已加载敏感词 {} 条（按词长降序）", usable.size());
        return usable;
    }
}
