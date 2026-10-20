package com.bookcollector.credential.service.impl;

import com.bookcollector.common.BizException;
import com.bookcollector.credential.WereadCredentialStore;
import com.bookcollector.credential.entity.WereadCredential;
import com.bookcollector.credential.req.WereadCredentialRequest;
import com.bookcollector.credential.service.WereadCredentialService;
import com.bookcollector.credential.vo.WereadCredentialVO;

import org.springframework.stereotype.Service;

/**
 * 凭证读写的实现。
 *
 * <h3>🔴 保存即八项全量校验（2026-10-15）</h3>
 * 用户原话：「缺少任意一个，就提示书单配置信息不完整」。
 * 缺项消息走 {@link WereadCredentialStore#describeMissing} —— 与采集链路
 * （{@code IllegalStateException}）<b>共用同一句</b>，界面与日志不出现两种措辞。
 * 校验放在保存这一刻而不是留到采集时，是因为「当场缺什么当场看到」
 * 比「任务跑起来第 1 页失败后再翻日志」便宜得多。
 *
 * <p>⚠️ <b>synckey 不在校验范围</b>：它由后端在每次书单请求发出前按本地
 * 时间生成（{@link WereadCredentialStore#refreshSynckeyNow()}），表单没有这一项。
 * {@code listUrl} 页面不暴露（分类 / 榜单不归这一页管），留空 = 保持原值。
 */
@Service
public class WereadCredentialServiceImpl implements WereadCredentialService {

    private final WereadCredentialStore store;

    public WereadCredentialServiceImpl(WereadCredentialStore store) {
        this.store = store;
    }

    @Override
    public WereadCredentialVO get() {
        return toVO(store.current());
    }

    @Override
    public WereadCredentialVO save(WereadCredentialRequest req) {
        // 先把请求八项 trim 成「空值会被 describeMissing 算作缺项」的形态，
        // 复用同一条缺项消息 —— 前端哪怕漏传一个字段，提示也和界面缺填一致。
        trimAll(req);

        WereadCredential candidate = new WereadCredential();
        candidate.setBooklistUrl(req.getBooklistUrl());
        candidate.setSkey(req.getSkey());
        candidate.setChannelid(req.getChannelid());
        candidate.setVid(req.getVid());
        candidate.setPriority(req.getPriority());
        candidate.setAcceptLanguage(req.getAcceptLanguage());
        candidate.setBasever(req.getBasever());
        candidate.setV(req.getV());
        // ⚠️ synckey 不参与校验：请求前按本地时间生成，见 WereadCredentialStore#refreshSynckeyNow
        String missing = WereadCredentialStore.describeMissing(candidate);
        if (missing != null) {
            throw BizException.warn(missing);
        }

        WereadCredential saved = store.save(req);
        return toVO(saved);
    }

    /** 八项 + listUrl 全部 trim（null 保持 null，交给缺项校验说话） */
    private void trimAll(WereadCredentialRequest req) {
        req.setSkey(trimOrNull(req.getSkey()));
        req.setChannelid(trimOrNull(req.getChannelid()));
        req.setVid(trimOrNull(req.getVid()));
        req.setPriority(trimOrNull(req.getPriority()));
        req.setAcceptLanguage(trimOrNull(req.getAcceptLanguage()));
        req.setBasever(trimOrNull(req.getBasever()));
        req.setV(trimOrNull(req.getV()));
        req.setBooklistUrl(trimOrNull(req.getBooklistUrl()));
        req.setListUrl(trimOrNull(req.getListUrl()));
    }

    /**
     * 🔴 明文回显（用户 2026-10-15 定的口径，见 {@link WereadCredentialVO}）。
     * skey / synckey 的防线只有一处：不进注释、不进日志。
     *
     * <p>synckey 照常回给前端 —— 但它<b>只用于只读展示</b>（表单里没有它的
     * 输入框），显示的是「最近一次书单请求用的值」（请求发出前按本地时间生成）。
     */
    private WereadCredentialVO toVO(WereadCredential c) {
        return WereadCredentialVO.builder()
                .skey(c.getSkey())
                .synckey(c.getSynckey())
                .channelid(c.getChannelid())
                .vid(c.getVid())
                .priority(c.getPriority())
                .acceptLanguage(c.getAcceptLanguage())
                .basever(c.getBasever())
                .v(c.getV())
                .booklistUrl(c.getBooklistUrl())
                .listUrl(c.getListUrl())
                .booklistReady(store.booklistReady())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
