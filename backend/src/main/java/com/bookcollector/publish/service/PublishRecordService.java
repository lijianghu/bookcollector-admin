package com.bookcollector.publish.service;

import com.bookcollector.common.PageResult;
import com.bookcollector.publish.entity.PublishRecord;

/**
 * 发布记录查询。
 *
 * <h3>只有「读」，没有「写」的公开入口</h3>
 * 记录的写入是 {@code PublishServiceImpl} 发布成功后<b>顺带</b>做的，
 * 不对外暴露「新增一条发布记录」的接口 —— 那种接口只能用来造假数据。
 * 历史数据补录走一次性脚本（见 {@code tools/backfill_publish_records.py}），
 * 也不经过这里。
 */
public interface PublishRecordService {

    /** 分页，按发布时间倒序（最新在前） */
    PageResult<PublishRecord> page(Integer page, Integer size);
}
