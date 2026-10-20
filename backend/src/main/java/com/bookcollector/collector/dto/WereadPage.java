package com.bookcollector.collector.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;

/**
 * 平台接口的<b>响应信封</b>。
 *
 * <h3>两种形状，一个入口</h3>
 * <pre>
 * ① 列表接口（分类 / 榜单）：
 * {
 *   uiInfo:     { styleType: "displayNewRating" },   ← 响应级样式提示，与书无关，丢弃
 *   synckey:    1789989022,
 *   books:      [ { bookInfo: {...}, searchIdx, type, readingCount }, ... ],
 *   hasMore:    1,
 *   totalCount: 54078
 * }
 *
 * ② 书单接口（App 端）：
 * {
 *   synckey: 1791953800,
 *   booklist: { ... , booklist: { name, totalCount, collectCount, books: [ { bookInfo: {...} } ] } }
 * }
 * </pre>
 * 两者的书都在更深的路径下，且<b>没有分页</b>（一次给全，上限 500 本）。
 *
 * <p>🔴 <b>2026-10-15 定稿：书单响应的两种形态都合法，{@code BookParser} 两种都收。</b>
 * <ul>
 *   <li><b>包装形态</b>：{@code books[]} 元素是 {@code {bookInfo: {...}, searchIdx, type,
 *       readingCount}} —— 分类 / 榜单一直是这个形状</li>
 *   <li><b>拍平形态</b>：元素本身就是书籍对象，没有 {@code bookInfo} 包装，
 *       书字段直接挂在元素上，另多一个 {@code readingCount}（阅读人数），
 *       且缺 {@code intro} 与 {@code category}（2026-10-16 实测补记 ——
 *       早先这里只写了 {@code intro}）</li>
 * </ul>
 * 拍平形态的字段基本是齐的（只少 {@code bookInfo} 包装、{@code intro} 与
 * {@code category}），所以按「元素即书籍对象」解析，<b>不判为失败</b>；
 * 缺的那两个由 {@code /book/info} 事后补齐（见 {@code BookEnrichService}）。
 *
 * <p>⚠️ 这条走过两次弯路，别再改回去：
 * <ol>
 *   <li>最早写「两种形状一致」—— 被 11 本的小书单样本误导；</li>
 *   <li>后来写「缺 {@code bookInfo} = synckey 没带上，一律报错」—— 那个「根因」是
 *       错的（当天只读核查：带请求时刻的新鲜 synckey 照样拍平），
 *       结果 499 本全被逐条跳过、任务还报「synckey 已失效」，
 *       把排查引到了 skey 上。</li>
 * </ol>
 * 用户最终实测：**拍平与请求头里的 {@code User-Agent} 有关**（带 UA → 拍平，不带 → 完整）。
 * 解析侧的正确做法就是两种都收 —— 见 {@link com.bookcollector.collector.BookParser#parse}。
 *
 * <h3>🔴 synckey 字段现在只用于观测（2026-10-15 晚改口径）</h3>
 * 曾经 {@link #getSynckey()}（响应里的 {@code root.synckey}）会被
 * {@code CollectLoop} 收进凭证自动滚动，下一个书单请求带上。用户当晚拍板：
 * 「synckey 不是从 root.synckey 取，而是后端 new Date() 的秒值」——
 * 从此请求带的 synckey 由 {@code WereadClient#buildUrl} 在请求发出前按本地
 * 时间现取，与本字段无关；这里只剩「响应快照」一个用途：请求审计照记
 * （{@code ApiRequest.synckey}），排查时可以对照「请求带的」与「响应回的」。
 *
 * <h3>🔴 错误信封必须在这里拦下</h3>
 * 登录态失效时接口回的是：
 * <pre>
 * { "errcode": -2012, "errlog": "CBhY2a7", "errmsg": "登录超时" }
 * </pre>
 * 它<b>没有</b> {@code books} 字段。如果不在这里识别出来，就会走到
 * 「解析出 0 本书 → 空页 → 采到底 → 任务成功」，界面显示
 * 「采集成功、新增 0 本」—— 而真实原因是登录态过期。这是最难查的一类静默失败，
 * 所以这里直接抛异常，让上层把任务标成失败并把原因带进日志与请求审计。
 *
 * <p>把信封解析单独拎出来，是为了让 {@link com.bookcollector.collector.CollectLoop}
 * 只关心「翻页控制」，不用混着写 JSON 取值。
 */
@Getter
public class WereadPage {

    private final JSONArray books;
    private final long synckey;
    private final boolean hasMore;
    private final int totalCount;

    /**
     * 书单 ID —— <b>只有书单接口（形状②）才有</b>，列表/榜单接口为 {@code null}。
     *
     * <p>存在的理由：书单链路采到的每一本书都要记住「它属于哪个书单」
     * （{@code Book.bookLists}）。这个 ID/名称只出现在<b>信封</b>里
     * （{@code booklist.booklist.booklistId} / {@code .name}），
     * 不在 {@code bookInfo} 里 —— 所以必须在解析信封时顺手取出来，
     * 否则下游只能再去翻一次原始 JSON。
     */
    private final String booklistId;

    /** 书单名称。同 {@link #booklistId}，只有书单接口才有 */
    private final String booklistName;

    private WereadPage(JSONArray books, long synckey, boolean hasMore, int totalCount,
                       String booklistId, String booklistName) {
        this.books = books;
        this.synckey = synckey;
        this.hasMore = hasMore;
        this.totalCount = totalCount;
        this.booklistId = booklistId;
        this.booklistName = booklistName;
    }

    /**
     * 解析响应体。
     *
     * <p>注意 {@code hasMore}：原 Python 写的是 {@code data.get('hasMore', False)}，
     * 而接口实际返回的是 <b>数字 1</b>（不是布尔 true）。fastjson2 的
     * {@code getBoolean} 对数字 0/1 能正确转换，这里用它。
     *
     * @throws IllegalArgumentException body 不是合法 JSON 对象、或接口回的是错误信封
     */
    public static WereadPage parse(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("响应体为空");
        }
        JSONObject root;
        try {
            root = JSON.parseObject(body);
        } catch (Exception e) {
            // 把原始响应的开头带进异常，方便判断是不是被拦了（返回了 HTML 验证页之类）
            String head = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            throw new IllegalArgumentException("响应不是合法 JSON，开头为：" + head, e);
        }
        if (root == null) {
            throw new IllegalArgumentException("响应解析为 null");
        }

        // ---- 0. 错误信封优先（登录超时等）----
        Integer errcode = root.getInteger("errcode");
        if (errcode != null && errcode.intValue() != 0) {
            throw new IllegalArgumentException(describeError(root, errcode.intValue()));
        }

        JSONArray books = root.getJSONArray("books");
        boolean hasMore = root.getBooleanValue("hasMore", false);
        int totalCount = root.getIntValue("totalCount", 0);
        String booklistId = null;
        String booklistName = null;

        if (books == null) {
            // 可能是书单接口的形状：books 在 booklist.booklist.books 下，且不分页
            JSONObject booklist = root.getJSONObject("booklist");
            if (booklist != null) {
                JSONObject node = booklistNode(booklist);
                JSONArray nodes = node.getJSONArray("books");
                if (nodes == null) {
                    // 认得出是书单响应，却没有 books 数组 —— 这是「形状变了」，
                    // 不能当成空页放过：那会变成「采集成功、新增 0 本」，
                    // 而真实原因是接口结构变了。把顶层字段名带进异常，便于定位。
                    throw new IllegalArgumentException(
                            "书单响应里没有 books 数组，顶层字段：" + root.keySet());
                }
                books = nodes;
                // 书单一次给全 —— 不能沿用顶层 hasMore（书单响应里根本没有这个字段，
                // 默认 false 恰好正确，但显式写出来，免得以后有人「顺手」改成 true）
                hasMore = false;
                totalCount = node.getIntValue("totalCount", books.size());
                // ★ 书单自己的 ID 与名称：下游要用它给每本书打上「属于哪个书单」的标记
                //   （Book.bookLists）。这里<b>原样返回</b>不做清洗 ——
                //   清洗（去空白、把字面量 "null" 转 null）统一由 BookParser 负责，
                //   一个概念只在一个地方定义。
                booklistId = node.getString("booklistId");
                booklistName = node.getString("name");
            }
        }
        if (books == null) {
            books = new JSONArray();
        }
        return new WereadPage(books, root.getLongValue("synckey", 0L), hasMore, totalCount,
                booklistId, booklistName);
    }

    public boolean isEmpty() {
        return books == null || books.isEmpty();
    }

    public int size() {
        return books == null ? 0 : books.size();
    }

    /**
     * 本页最后一条记录的 {@code searchIdx} —— <b>这就是下一页的 {@code maxIndex}</b>。
     *
     * <p>返回 {@code null} 表示取不到（本页为空、最后一条缺字段、或书单这种
     * 本来就没有 {@code searchIdx} 的响应）。调用方必须处理这种情况：
     * 取不到游标就不能推进，否则会死循环。
     */
    public Integer lastSearchIdx() {
        if (isEmpty()) {
            return null;
        }
        JSONObject last = books.getJSONObject(books.size() - 1);
        if (last == null) {
            return null;
        }
        return last.getInteger("searchIdx");
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 取真正的书单对象。
     *
     * <p>它嵌在 {@code booklist.booklist}（外层 {@code booklist} 还挂着
     * {@code likeVids} / {@code bookItems} 等）。拿不到内层时退回外层 ——
     * 这是为了容错「内层被拍平」的极端情况，调用方会自己判断 {@code books} 在不在。
     */
    private static JSONObject booklistNode(JSONObject booklist) {
        JSONObject inner = booklist.getJSONObject("booklist");
        return inner == null ? booklist : inner;
    }

    /**
     * 把错误信封拼成一句人话。
     *
     * <p>带上 {@code errcode} 是刻意的：{@code errmsg} 是给用户看的（「登录超时」），
     * {@code errcode} 是给排障用的（-2012 是登录态失效，别的码是别的原因）。
     * 两者缺一个，排查时都得靠猜。
     */
    private static String describeError(JSONObject root, int errcode) {
        String msg = root.getString("errmsg");
        return "接口返回错误 errcode=" + errcode + (msg == null || msg.trim().isEmpty()
                ? "" : "：" + msg.trim());
    }
}
