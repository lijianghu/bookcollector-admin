package com.bookcollector.booklist.service;

import com.bookcollector.booklist.entity.Booklist;
import com.bookcollector.booklist.repository.BooklistRepository;
import com.bookcollector.booklist.vo.BooklistCollectVO;
import com.bookcollector.common.BizException;
import com.bookcollector.common.ResultBean;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.common.enums.TaskStatus;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskRun;
import com.bookcollector.task.repository.TaskRepository;
import com.bookcollector.task.service.TaskService;

import org.springframework.stereotype.Service;

/**
 * 「同步某张书单」—— 链路 B：把这张书单里的书采集入库。
 *
 * <p>不新造任务体系：直接复用 {@code collect_tasks}（{@code targetType=BOOKLIST}）。
 * 前端点「同步」时：没有任务就建，有就按状态 start / retry / resume。
 * 进度直接读最近一次 {@link TaskRun}，不另建进度表。
 */
@Service
public class BooklistCollectService {

    private final BooklistRepository booklistRepository;
    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final BooklistSyncService syncService;

    public BooklistCollectService(BooklistRepository booklistRepository,
                                  TaskRepository taskRepository,
                                  TaskService taskService,
                                  BooklistSyncService syncService) {
        this.booklistRepository = booklistRepository;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.syncService = syncService;
    }

    /**
     * 启动这张书单的采集（已有任务则按状态续跑）。
     *
     * <p>提交即返回；进度看 {@link #progress(String)}。
     */
    public BooklistCollectVO collect(String booklistId) {
        String id = requireId(booklistId);
        Booklist booklist = booklistRepository.findByBooklistId(id);
        if (booklist == null) {
            throw new BizException(ResultBean.code_warn, "书单不存在：" + id);
        }

        // 元数据回填（名称/简介/作者/计数/时间）是顺带动作：失败不阻断采集
        String name = syncService.ensureName(id);
        String displayName = firstNonBlank(name, booklist.getName(), id);

        CollectTask task = taskRepository.findByTarget(TargetType.BOOKLIST.name(), id);
        if (task == null) {
            task = taskService.create(displayName + "-采集", TargetType.BOOKLIST.name(),
                    id, displayName, 0, null, null);
        }

        TaskStatus status = TaskStatus.of(task.getStatus());
        if (status.isActive()) {
            if (status == TaskStatus.PAUSED) {
                taskService.resume(task.getId());
            }
            return toVO(id, taskRepository.findById(task.getId()));
        }
        if (status.isTerminal()) {
            taskService.retry(task.getId());
        } else {
            taskService.start(task.getId());
        }
        return toVO(id, taskRepository.findById(task.getId()));
    }

    /** 当前（或最近一次）采集进度。没跑过时返回「待执行」快照，不返回 null */
    public BooklistCollectVO progress(String booklistId) {
        String id = requireId(booklistId);
        CollectTask task = taskRepository.findByTarget(TargetType.BOOKLIST.name(), id);
        if (task == null) {
            return BooklistCollectVO.builder()
                    .booklistId(id)
                    .status(TaskStatus.PENDING.name())
                    .statusLabel(TaskStatus.PENDING.getLabel())
                    .running(false)
                    .build();
        }
        return toVO(id, task);
    }

    private BooklistCollectVO toVO(String booklistId, CollectTask task) {
        TaskRun run = taskRepository.findLatestRunByTarget(TargetType.BOOKLIST.name(), booklistId);
        TaskStatus status = TaskStatus.of(task.getStatus());
        BooklistCollectVO.BooklistCollectVOBuilder builder = BooklistCollectVO.builder()
                .booklistId(booklistId)
                .taskId(task.getId())
                .status(status.name())
                .statusLabel(status.getLabel())
                .running(status == TaskStatus.RUNNING);
        if (run != null) {
            builder.runId(run.getId())
                    .pagesDone(run.getPagesDone())
                    .booksSaved(run.getBooksSaved())
                    .booksInserted(run.getBooksInserted())
                    .booksExcluded(run.getBooksExcluded())
                    .totalCount(run.getTotalCount())
                    .startedAt(run.getStartedAt())
                    .finishedAt(run.getFinishedAt())
                    .errorMsg(run.getErrorMsg());
        }
        return builder.build();
    }

    private static String requireId(String booklistId) {
        String id = booklistId == null ? "" : booklistId.trim();
        if (id.isEmpty()) {
            throw new BizException(ResultBean.code_warn, "书单 ID 不能为空");
        }
        return id;
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return "";
    }
}
