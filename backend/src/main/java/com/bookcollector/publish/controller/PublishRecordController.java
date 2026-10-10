package com.bookcollector.publish.controller;

import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;
import com.bookcollector.publish.entity.PublishRecord;
import com.bookcollector.publish.service.PublishRecordService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发布记录（每次成功发布到语雀留一条流水）。
 *
 * <h3>为什么与 {@code PublishController} 分成两个类</h3>
 * 虽然都在 {@code /api/publish} 前缀下，但两者的读写特征完全不同：
 * {@code PublishController} 管的是「候选清单 + 长任务」，有内存态、有互斥位；
 * 这里只有一张只读的流水表。分开之后 Swagger 上也是两个独立分组，
 * 排查「为什么某本书没发出去」时不会混在一起看。
 *
 * <p>⚠️ 路径 {@code /api/publish/records} 与 {@code PublishController} 的
 * {@code /api/publish/interpretations} 不冲突（没有重叠的映射）。
 */
@Tag(name = "12. 发布记录", description = "已发布到语雀的流水，按发布时间倒序")
@RestController
@RequestMapping("/api/publish/records")
public class PublishRecordController {

    private final PublishRecordService service;

    public PublishRecordController(PublishRecordService service) {
        this.service = service;
    }

    @Operation(summary = "发布记录分页",
            description = "按发布时间倒序（最新的在前）。返回的是实体直出 —— "
                    + "这张表没有大字段（正文只落本地与语雀，不进库）")
    @GetMapping
    public ResultBean<PageResult<PublishRecord>> page(
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {
        return ResultBean.ok(service.page(page, size));
    }
}
