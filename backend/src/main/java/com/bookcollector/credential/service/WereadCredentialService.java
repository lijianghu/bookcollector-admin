package com.bookcollector.credential.service;

import com.bookcollector.credential.req.WereadCredentialRequest;
import com.bookcollector.credential.vo.WereadCredentialVO;

/**
 * 接口凭证的读写。
 *
 * <p>只有一个单文档，动作只有「读」和「存」，所以接口刻意只留两个方法 ——
 * 不要往这里加「启用 / 停用」「多套凭证」这类东西：那会立刻需要一套
 * 「哪套在生效」的规则，而当前场景（本机单用户）只需要一套。
 */
public interface WereadCredentialService {

    /** 当前凭证（明文回显 —— 2026-10-15 用户口径，见 {@code WereadCredentialVO}） */
    WereadCredentialVO get();

    /**
     * 保存。🔴 九项书单凭证全量校验，缺任一项即拒（「书单配置信息不完整」）。
     * {@code listUrl} 不在书单表单里，留空 = 保持原值。
     */
    WereadCredentialVO save(WereadCredentialRequest req);
}
