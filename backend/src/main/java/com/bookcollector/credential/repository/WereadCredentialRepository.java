package com.bookcollector.credential.repository;

import com.bookcollector.credential.entity.WereadCredential;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * 接口凭证的仓储。
 *
 * <p>只有一条记录，主键固定（见 {@code WereadCredentialStore.DOC_ID}），
 * 所以除了继承来的 {@code findById} / {@code save} 之外不需要任何自定义方法。
 *
 * <p><b>刻意不加唯一索引</b>：单文档靠固定主键保证唯一，加索引没有意义。
 * 唯一索引只加在「业务上必须唯一、而主键表达不了」的地方（如 {@code books.bookId}）。
 */
public interface WereadCredentialRepository extends MongoRepository<WereadCredential, String> {
}
