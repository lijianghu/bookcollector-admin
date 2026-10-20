package com.bookcollector.publish.service.impl;

import com.bookcollector.common.PageResult;
import com.bookcollector.publish.entity.PublishRecord;
import com.bookcollector.publish.repository.PublishRecordRepository;
import com.bookcollector.publish.service.PublishRecordService;

import org.springframework.stereotype.Service;

/**
 * {@link PublishRecordService} 的实现。
 *
 * <p>薄薄一层：分页口径（{@code page < 1 → 1}、{@code size} 夹在 [1, 200]）
 * 统一由 {@code PageQueryUtil} 归一化，这里不重复判断。
 */
@Service
public class PublishRecordServiceImpl implements PublishRecordService {

    private final PublishRecordRepository repository;

    public PublishRecordServiceImpl(PublishRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public PageResult<PublishRecord> page(Integer page, Integer size) {
        return repository.page(page, size);
    }
}
