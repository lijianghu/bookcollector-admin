package com.bookcollector.audit.dto;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.task.entity.TaskLog;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 请求审计<b>详情</b>（2026-10-15 新增）—— 「一次请求的完整现场」。
 *
 * <h3>为什么日志要跟着审计记录一起返回</h3>
 * 用户诉求是「点一下详情，就能判断这次失败是<b>数据访问问题</b>还是<b>解析问题</b>」。
 * 单看一条审计记录还不够：错误发生在采集主循环的哪一步（发请求前 / 解析时 /
 * 写库时），要看那次运行的步骤日志（{@code collect_task_logs}）才知道。
 * 让前端发两个请求再自己拼，等于把「按什么关联」的业务知识推给前端 ——
 * 所以后端一次聚齐：审计记录 + 它所属 run 的日志（按时间正序）。
 *
 * <p>{@code logs} 可能为空：手工探针触发的请求没有 runId；那次运行
 * 恰好一条日志都没落也是空。前端按「没有」展示，不是按「出错」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequestDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审计记录本体（含 queryParams / requestHeaders / rawResponse） */
    private ApiRequest record;

    /** 该记录所属运行的核心步骤日志，按时间正序；无 runId 时为空列表 */
    private List<TaskLog> logs;
}
