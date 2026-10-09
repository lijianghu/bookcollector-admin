package com.bookcollector.audit.service.impl;

import com.bookcollector.audit.dto.RequestSummary;
import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.audit.repository.ApiRequestQueryRepository;
import com.bookcollector.audit.service.AuditService;
import com.bookcollector.common.PageResult;
import com.bookcollector.util.PageQueryUtil;

import org.springframework.stereotype.Service;

/**
 * {@link AuditService} 的实现。
 *
 * <p>这里只做参数归一化 + 转发，查询构造与排序在
 * {@link ApiRequestQueryRepository} 里（那才是「怎么和 Mongo 说话」）。
 * 归一化口径（默认 20、上限 200）统一取自 {@link PageQueryUtil}，全项目一个来源。
 */
@Service
public class AuditServiceImpl implements AuditService {

    private final ApiRequestQueryRepository repository;

    public AuditServiceImpl(ApiRequestQueryRepository repository) {
        this.repository = repository;
    }

    @Override
    public PageResult<ApiRequest> page(String runId, String targetType, String targetId,
                                       Boolean onlyFailed, Integer page, Integer size) {
        int p = PageQueryUtil.normalizePage(page);
        int s = PageQueryUtil.normalizeSize(size);
        return repository.pageQuery(runId, targetType, targetId, onlyFailed, p, s);
    }

    /**
     * 摘要<b>没有分页参数</b>，所以这里不做任何归一化，直接转发。
     *
     * <p>顺带说明为什么它不写在 {@code page()} 里返回：摘要是聚合，
     * 与「取哪一页」无关；混进分页结果里会让前端每次翻页都白拿一份聚合。
     */
    @Override
    public RequestSummary summary(String runId, String targetType, String targetId, Boolean onlyFailed) {
        return repository.summary(runId, targetType, targetId, onlyFailed);
    }
}
