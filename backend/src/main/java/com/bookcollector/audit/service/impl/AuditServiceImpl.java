package com.bookcollector.audit.service.impl;

import com.bookcollector.audit.dto.ReplayResult;
import com.bookcollector.audit.dto.ReplayRequest;
import com.bookcollector.audit.dto.ReplayResult;
import com.bookcollector.audit.dto.RequestDetail;
import com.bookcollector.audit.dto.RequestSummary;
import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.audit.repository.ApiRequestQueryRepository;
import com.bookcollector.audit.service.AuditService;
import com.bookcollector.audit.service.RequestReplayer;
import com.bookcollector.common.BizException;
import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.task.repository.TaskRepository;
import com.bookcollector.util.PageQueryUtil;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * {@link AuditService} 的实现。
 *
 * <p>这里只做参数归一化 + 转发，查询构造与排序在
 * {@link ApiRequestQueryRepository} 里（那才是「怎么和 Mongo 说话」）。
 * 归一化口径（默认 20、上限 200）统一取自 {@link PageQueryUtil}，全项目一个来源。
 */
@Service
public class AuditServiceImpl implements AuditService {

    /**
     * 详情里最多带多少条步骤日志。
     *
     * <p>一次运行的日志量级：分类采集一页 2~3 条、几百页满打满算 1000 条上下，
     * 但排查时关注的是「出问题那一刻前后」，取最近的 300 条足够。
     * 写死而不做分页：详情是一次性快照，给它做翻页是过度设计。
     */
    private static final int DETAIL_LOG_LIMIT = 300;

    private final ApiRequestQueryRepository repository;
    private final TaskRepository taskRepository;
    /** 回放（最原始 HTTP 重发）。与查询无关，单独一个组件 */
    private final RequestReplayer replayer;

    public AuditServiceImpl(ApiRequestQueryRepository repository,
                            TaskRepository taskRepository,
                            RequestReplayer replayer) {
        this.repository = repository;
        this.taskRepository = taskRepository;
        this.replayer = replayer;
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

    /**
     * 单条详情（2026-10-15 新增）。
     *
     * <p>组装逻辑：按 ID 取审计记录 → 按它的 {@code runId} 取那次运行的
     * 步骤日志（时间正序，最多 {@value #DETAIL_LOG_LIMIT} 条）。
     *
     * <p>为什么日志查询放在 service 而不是让前端调任务接口自己拼：
     * 「按 runId 关联」是这条链路的业务知识，前端不该知道；
     * 而且两个请求之间数据可能变化（run 被删），分开拿会看到对不上的现场。
     */
    @Override
    public RequestDetail findDetail(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new BizException(ResultBean.code_warn, "审计记录 ID 不能为空");
        }
        ApiRequest record = repository.findById(id.trim());
        if (record == null) {
            throw BizException.notFound("审计记录不存在：" + id);
        }
        List<TaskLog> logs = Collections.emptyList();
        String runId = record.getRunId();
        if (runId != null && !runId.trim().isEmpty()) {
            logs = taskRepository.findLogsByRunId(runId.trim(), DETAIL_LOG_LIMIT);
        }
        return new RequestDetail(record, logs);
    }

    /**
     * 回放（2026-10-15 晚新增，当晚 redesign：入参从「记录 ID」改成「要发的请求」）。
     *
     * <p>本方法只做「URL 非空」一道校验；HTTP 与头处理都在 {@link RequestReplayer}。
     * 为什么不合并：回放依赖凭证与 HTTP，与这个类的查询职责无关，
     * 拆开后查询侧的阅读与测试都不被打扰。
     *
     * <p>🔴 回放<b>不写审计</b>：它是诊断动作。记进 {@code api_requests} 会让
     * 「这个目标被采了多少次」的口径里混进人工重发的次数。
     */
    @Override
    public ReplayResult replay(ReplayRequest req) {
        if (req == null || req.getUrl() == null || req.getUrl().trim().isEmpty()) {
            throw new BizException(ResultBean.code_warn, "请求 URL 不能为空");
        }
        try {
            return replayer.replay(req);
        } catch (IllegalStateException e) {
            // 书单域名但凭证八项缺一（RequestReplayer 里过的 requireBooklistComplete）——
            // 转成弹给人看的提示，与保存接口、采集链路同一句口径。
            throw BizException.warn(e.getMessage());
        }
    }
}
