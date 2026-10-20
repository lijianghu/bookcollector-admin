package com.bookcollector.booklist;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bookcollector.booklist.entity.Booklist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 把书单发现接口的 {@code datas[]} 元素映射成 {@link Booklist}。
 *
 * <p>与 {@code collector.BookParser} 同一分工：信封解析在
 * {@link com.bookcollector.booklist.dto.BooklistPage}，字段映射在这里。
 *
 * <h3>🔴 只映射「接口给的」字段</h3>
 * 本类<b>刻意不设置</b> {@code collectStatus} / {@code bookCountCollected} /
 * {@code lastCollectedAt} —— 它们是链路 B（采书）的字段。
 * {@code BooklistRepository} 的 upsert 依赖「没赋值的字段不进 {@code $set}」
 * （{@code MappingMongoConverter} 不写 null），所以这里一旦「顺手」给它们塞个默认值，
 * 每次同步都会把已经采过的书单打回「待采」。
 *
 * <h3>⚠️ 时间字段是 unix <b>秒</b></h3>
 * 接口给秒（{@code 1791620913}），这里统一换算成 {@link Date}。
 * {@code 0} 视为「未设置」→ 存 {@code null}（接口在 {@code likedTime} 这类字段上
 * 用 0 表示空，直接换算会得到一堆 1970-01-01）。
 */
@Component
public class BooklistParser {

    private static final Logger log = LoggerFactory.getLogger(BooklistParser.class);

    /** 单个字段名：接口的 {@code totalCount} 是「书单里有几本书」，映射到实体的 {@code bookCount} */
    private static final String KEY_BOOKLIST_ID = "booklistId";
    private static final String KEY_NAME = "name";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_USER_VID = "userVid";
    private static final String KEY_AUTHOR = "author";
    private static final String KEY_TOTAL_COUNT = "totalCount";
    private static final String KEY_COLLECT_COUNT = "collectCount";
    private static final String KEY_LIKED_COUNT = "likedCount";
    private static final String KEY_SHARE_COUNT = "shareCount";
    private static final String KEY_CREATE_TIME = "createTime";
    private static final String KEY_UPDATE_TIME = "updateTime";
    private static final String KEY_LAST_READ_TIME = "lastReadTime";

    /**
     * 批量映射。
     *
     * <p>缺 {@code booklistId} 的条目<b>跳过并记 WARN</b>（不抛异常）——
     * 一条坏数据不该让整次同步失败；但也不能静默，
     * 调用方会把 {@code page.size() - 返回条数} 记进「跳过」计数里。
     */
    public List<Booklist> parseAll(JSONArray datas) {
        List<Booklist> result = new ArrayList<Booklist>();
        if (datas == null || datas.isEmpty()) {
            return result;
        }
        for (int i = 0; i < datas.size(); i++) {
            JSONObject node = datas.getJSONObject(i);
            if (node == null) {
                continue;
            }
            String booklistId = trim(node.getString(KEY_BOOKLIST_ID));
            if (booklistId.isEmpty()) {
                log.warn("书单发现响应第 {} 条没有 booklistId，已跳过", i);
                continue;
            }
            result.add(parseOne(node, booklistId));
        }
        return result;
    }

    /**
     * 解析单条书单节点（{@code /booklist/single} 的 {@code booklist.booklist}，
     * 或分类接口 {@code datas[]} 的一条）。2026-10-20：供「同步」回填整条元数据用。
     */
    public Booklist parseOne(JSONObject node, String booklistId) {
        Booklist.BooklistBuilder builder = Booklist.builder()
                .booklistId(booklistId)
                .name(trim(node.getString(KEY_NAME)))
                .description(trim(node.getString(KEY_DESCRIPTION)))
                .userVid(node.getLong(KEY_USER_VID))
                .bookCount(node.getInteger(KEY_TOTAL_COUNT))
                .collectCount(node.getInteger(KEY_COLLECT_COUNT))
                .likedCount(node.getInteger(KEY_LIKED_COUNT))
                .shareCount(node.getInteger(KEY_SHARE_COUNT))
                .createTime(toDate(node.getLong(KEY_CREATE_TIME)))
                .updateTime(toDate(node.getLong(KEY_UPDATE_TIME)))
                .lastReadTime(toDate(node.getLong(KEY_LAST_READ_TIME)));

        JSONObject author = node.getJSONObject(KEY_AUTHOR);
        if (author != null) {
            builder.authorName(trim(author.getString("name")))
                    .authorAvatar(trim(author.getString("avatar")));
        }
        return builder.build();
    }

    /**
     * unix 秒 → {@link Date}。
     *
     * @return {@code null} / 非正数 → {@code null}（接口用 0 表示「未设置」）
     */
    private static Date toDate(Long seconds) {
        if (seconds == null || seconds.longValue() <= 0L) {
            return null;
        }
        return new Date(seconds.longValue() * 1000L);
    }

    /** 去首尾空白；{@code null} → {@code null}（保持「接口没给」与「给了空串」的区别） */
    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
