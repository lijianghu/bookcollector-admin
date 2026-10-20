package com.bookcollector.collector;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bookcollector.book.entity.Book;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台接口响应 → {@link Book} 实体。
 *
 * <h3>字段口径</h3>
 * 严格对齐原 {@code book_api_client.py} 的 {@code BookInfo}（46 个字段）。
 * 三个<b>容易搞错</b>的点：
 * <ol>
 *   <li>{@code searchIdx} / {@code type} / {@code readingCount} 取自<b>外层记录</b>，
 *       不在 {@code bookInfo} 里。其中外层的 {@code type} 映射到
 *       {@link Book#getTypeInfo()}，<b>不是</b> {@link Book#getType()}
 *       （后者来自 {@code bookInfo.type}）。</li>
 *   <li>{@code paperBook.skuId} 拍平成 {@link Book#getPaperBookSkuId()}。</li>
 *   <li>{@code newRatingDetail.title} 拍平成 {@link Book#getNewRatingTitle()}，
 *       同时整个 {@code newRatingDetail} 对象也保留为
 *       {@link Book#getNewRatingDetail()}。</li>
 * </ol>
 *
 * <h3>{@link #cleanValue} 的必要性</h3>
 * 平台接口<b>真的会返回字符串 {@code "null"} 和 {@code "undefined"}</b>
 * （不是 JSON null）。不做清洗就会在库里存一堆字面量 {@code "null"}，
 * 前端显示成 "null" 文本。
 *
 * <p>原 Python 的 {@code clean_value} 只把 {@code '' / 'null' / 'undefined'} 转成 None。
 * 这里<b>额外加了 trim</b>（原版没有），并<b>返回 trim 后的值</b>：
 * 接口数据里的首尾空白是噪声，去掉更干净。这是刻意偏离原实现的一处，
 * 回归测试 {@code BookParserTest} 把这个行为钉住了。
 */
@Component
public class BookParser {

    private static final Logger log = LoggerFactory.getLogger(BookParser.class);

    /** 字符串字面量 {@code "null"} —— 接口真的会返回它 */
    private static final String LITERAL_NULL = "null";
    /** 字符串字面量 {@code "undefined"} */
    private static final String LITERAL_UNDEFINED = "undefined";

    /**
     * 解析一页的全部图书（<b>不带书单信息</b>，供列表 / 榜单链路用）。
     *
     * @param nodes 响应里的 {@code books} 数组。元素是包装形态
     *              {@code {bookInfo: {...}, searchIdx, type, readingCount}}
     * @return 解析成功的图书列表（顺序与入参一致）
     */
    public List<Book> parseAll(JSONArray nodes) {
        return parseAll(nodes, null, null);
    }

    /**
     * 解析一页的全部图书，并给每本打上「属于哪个书单」的标记。
     *
     * <p>单本解析失败<b>只跳过该本并记 WARN</b>，不影响整页 ——
     * 不能因为一本书的脏数据搞挂整个采集任务。
     *
     * <p>书单链路（{@code /booklist/single}）用它：书单的 ID 与名称只出现在
     * <b>响应信封</b>里（见 {@code WereadPage#getBooklistId()}），
     * 不在每本书的 {@code bookInfo} 里，所以要在这里注入。
     *
     * @param nodes        响应里的 {@code books} 数组
     * @param bookListId   书单 ID。{@code null} = 本次不带书单信息（列表/榜单链路）
     * @param bookListName 书单名称。为 {@code null} 时用 {@code bookListId} 兜底
     * @return 解析成功的图书列表（顺序与入参一致）
     */
    public List<Book> parseAll(JSONArray nodes, String bookListId, String bookListName) {
        List<Book> result = new ArrayList<Book>();
        if (nodes == null || nodes.isEmpty()) {
            return result;
        }
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = null;
            try {
                node = nodes.getJSONObject(i);
                Book book = parse(node, bookListId, bookListName);
                if (book != null) {
                    result.add(book);
                }
            } catch (Exception e) {
                // 记 WARN 后继续，不要抛
                String hint = node == null ? "?" : String.valueOf(node.get("searchIdx"));
                log.warn("解析第 {} 条（searchIdx={}）失败，已跳过：{}", i + 1, hint, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 解析单条记录（<b>不带书单信息</b>）。
     *
     * @return 解析结果；{@code bookId} 缺失时返回 {@code null}（没有 bookId 无法 upsert）
     */
    public Book parse(JSONObject node) {
        return parse(node, null, null);
    }

    /**
     * 解析单条记录，并打上书单标记。
     *
     * <p>两种形态都收（2026-10-15）：
     * <ul>
     *   <li>包装形态（分类 / 榜单，以及不拍平时的书单）：
     *       {@code {bookInfo: {...}, searchIdx, type, readingCount}}</li>
     *   <li>拍平形态（书单接口在某些条件下）：元素本身就是书籍对象，
     *       没有 {@code bookInfo}，另多一个 {@code readingCount}</li>
     * </ul>
     *
     * @param node         记录，两种形态见上
     * @param bookListId   书单 ID；{@code null} = 不带书单信息
     * @param bookListName 书单名称；为 {@code null} 时用 {@code bookListId} 兜底
     * @return 解析结果；{@code bookId} 缺失时返回 {@code null}（没有 bookId 无法 upsert）
     */
    public Book parse(JSONObject node, String bookListId, String bookListName) {
        if (node == null) {
            return null;
        }
        JSONObject info = node.getJSONObject("bookInfo");
        if (info == null) {
            // 🔴 2026-10-15：两种形态都收 —— 有 bookInfo 用它，没有就把元素本身当书籍对象。
            //
            // 书单接口（App 端）在某些条件下返回的是**拍平形态**：
            // books 元素上没有 bookInfo 包装，书字段（bookId/title/author/cover/…）
            // 直接挂在元素上，另外**多一个 readingCount（阅读人数）**。
            // 用户当天实测：这与请求头里的 User-Agent 有关（带 UA → 拍平，不带 → 完整）。
            // 拍平形态的字段基本是齐的（只少 bookInfo 包装、intro 与 category ——
            // 后两者是 2026-10-16 实测确认的，早先这里只写了 intro；
            // 缺失的那两个由 /book/info 事后补齐，见 BookEnrichService），
            // 所以直接按「元素即书籍对象」解析，不再判为失败。
            //
            // ⚠️ readingCount 不用特判：它本来就取自**外层记录**（见下面 .readingCount(node…)），
            //    拍平时外层就是元素本身，自然读到。
            info = node;
        }

        String bookId = cleanValue(info.getString("bookId"));
        if (bookId == null) {
            log.warn("记录缺少 bookId，已跳过（title={}）", info.getString("title"));
            return null;
        }

        return Book.builder()
                // ---- 与 bookInfo 1:1 映射（40 个）----
                .bookId(bookId)
                .title(cleanValue(info.getString("title")))
                .author(cleanValue(info.getString("author")))
                .translator(cleanValue(info.getString("translator")))
                .cover(cleanValue(info.getString("cover")))
                .version(info.getLong("version"))
                .format(cleanValue(info.getString("format")))
                .type(info.getInteger("type"))
                .price(info.getInteger("price"))
                .originalPrice(info.getInteger("originalPrice"))
                .soldout(info.getInteger("soldout"))
                .bookStatus(info.getInteger("bookStatus"))
                .payingStatus(info.getInteger("payingStatus"))
                .payType(info.getLong("payType"))
                // ⚠️ intro 可为 null（响应里整个字段不存在时就是 null，书单拍平
                //    形态实测如此）。「不覆盖已有值」由写入侧负责：
                //    BookRepository#buildUpsertUpdate 见到 null 会把它从 $set
                //    里摘掉（用户 2026-10-15 要求），解析侧只管如实反映。
                .intro(cleanValue(info.getString("intro")))
                .centPrice(info.getInteger("centPrice"))
                .finished(info.getInteger("finished"))
                .maxFreeChapter(info.getInteger("maxFreeChapter"))
                .free(info.getInteger("free"))
                .mcardDiscount(info.getInteger("mcardDiscount"))
                .ispub(info.getInteger("ispub"))
                .extraType(info.getInteger("extra_type"))
                .cpid(info.getLong("cpid"))
                .publishTime(cleanValue(info.getString("publishTime")))
                .category(cleanValue(info.getString("category")))
                .categories(parseCategories(info.getJSONArray("categories")))
                .hasLecture(info.getInteger("hasLecture"))
                .lastChapterIdx(info.getInteger("lastChapterIdx"))
                .blockSaveImg(info.getInteger("blockSaveImg"))
                .language(cleanValue(info.getString("language")))
                .isTraditionalChinese(info.getBoolean("isTraditionalChinese"))
                .hideUpdateTime(info.getBoolean("hideUpdateTime"))
                .isEpubComics(info.getInteger("isEPUBComics"))
                .isVerticalLayout(info.getInteger("isVerticalLayout"))
                .isShowTts(info.getInteger("isShowTTS"))
                .webBookControl(info.getInteger("webBookControl"))
                .selfProduceIncentive(info.getBoolean("selfProduceIncentive"))
                .isAutoDownload(info.getInteger("isAutoDownload"))
                .newRating(info.getInteger("newRating"))
                .newRatingCount(info.getInteger("newRatingCount"))

                // ---- 由嵌套结构拍平 / 派生（3 个）----
                .paperBookSkuId(parsePaperBookSkuId(info))
                .newRatingTitle(parseNewRatingTitle(info))
                .newRatingDetail(parseRatingDetail(info.getJSONObject("newRatingDetail")))

                // ---- 取自外层记录，不在 bookInfo 里（3 个）----
                // ⚠️ typeInfo 取的是外层 type，别和上面的 .type(info.getInteger("type")) 搞混
                .searchIdx(node.getInteger("searchIdx"))
                .typeInfo(node.getInteger("type"))
                .readingCount(node.getInteger("readingCount"))

                // ---- 原 Python 丢弃、本次保留的 4 个扩展字段 ----
                .maxFreeInfo(parseMaxFreeInfo(info.getJSONObject("maxFreeInfo")))
                .copyrightChapterUids(parseIntList(info.getJSONArray("copyrightChapterUids")))
                .lPushName(cleanValue(info.getString("lPushName")))
                .authorVids(cleanValue(info.getString("authorVids")))

                // ---- book/info 接口补充字段（2026-10-16「完善书籍信息」新增）----
                // 这些字段**分类/榜单链路**（包装形态）本来就有；书单拍平形态大多没有，
                // 由 /book/info 事后补齐。解析口径与上面完全一致：数值不 cleanValue
                // （0 是有效值），字符串走 cleanValue。
                .isbn(cleanValue(info.getString("isbn")))
                .publisher(cleanValue(info.getString("publisher")))
                .totalWords(info.getInteger("totalWords"))
                .publishPrice(info.getInteger("publishPrice"))
                .bookSize(info.getInteger("bookSize"))
                .chapterSize(info.getInteger("chapterSize"))
                .encodeId(cleanValue(info.getString("encodeId")))
                .deepLink(cleanValue(info.getString("deepLink")))
                .inStoreBookId(cleanValue(info.getString("inStoreBookId")))
                .updateTime(info.getLong("updateTime"))
                .onTime(info.getLong("onTime"))
                .unitPrice(info.getDouble("unitPrice"))
                .marketType(info.getInteger("marketType"))
                .star(info.getInteger("star"))
                .ratingCount(info.getInteger("ratingCount"))
                .deepVRating(info.getInteger("deepVRating"))
                .showDeepVRatingLabel(info.getInteger("showDeepVRatingLabel"))
                .ratingDetail(parseScoreDetail(info.getJSONObject("ratingDetail")))
                .hasKeyPoint(info.getBoolean("hasKeyPoint"))
                .recommended(info.getInteger("recommended"))
                .lectureRecommended(info.getInteger("lectureRecommended"))
                .showLectureButton(info.getInteger("showLectureButton"))
                // ⚠️ 接口字段名是大写缩写，Java 字段名只大写首字母（同 isEpubComics）
                .isHideTtsButton(info.getInteger("isHideTTSButton"))
                .shouldHideTts(info.getInteger("shouldHideTTS"))
                .wxtts(info.getInteger("wxtts"))
                .askAiBook(info.getInteger("askAIBook"))
                .aiBookButtonType(info.getInteger("aiBookButtonType"))
                .autoBookRequest(info.getInteger("autoBookRequest"))
                .latestReviewSecretMode(info.getInteger("latestReviewSecretMode"))
                .follow(info.getInteger("follow"))
                .secret(info.getInteger("secret"))
                .offline(info.getInteger("offline"))
                .lectureOffline(info.getInteger("lectureOffline"))
                .finishReading(info.getInteger("finishReading"))
                .hideReview(info.getInteger("hideReview"))
                .hideFriendMark(info.getInteger("hideFriendMark"))
                .blacked(info.getInteger("blacked"))
                .hasSubject(info.getInteger("hasSubject"))
                .isAutoPay(info.getInteger("isAutoPay"))
                .availables(info.getInteger("availables"))
                .paid(info.getInteger("paid"))
                .isChapterPaid(info.getInteger("isChapterPaid"))
                .aiSummary(cleanValue(info.getString("AISummary")))
                .copyrightInfo(parseCopyrightInfo(info.getJSONObject("copyrightInfo")))
                .authorSeg(parseAuthorSegs(info.getJSONArray("authorSeg")))
                .translatorSeg(parseAuthorSegs(info.getJSONArray("translatorSeg")))
                .coverBoxInfo(parseCoverBoxInfo(info.getJSONObject("coverBoxInfo")))
                .skuInfo(parseSkuInfo(info.getJSONObject("skuInfo")))
                .shortTimeRead(parseShortTimeRead(info.getJSONObject("shortTimeRead")))
                .ranklist(parseRanklist(info.getJSONObject("ranklist")))

                // ---- 书单引用：来自**信封**而不是 bookInfo（只有书单链路有）----
                .bookLists(buildBookListRefs(bookListId, bookListName))
                .build();
    }

    // ==================================================================
    // 字符串清洗
    // ==================================================================

    /**
     * 清洗字符串字段。
     *
     * <pre>
     * null / "" / "null" / "undefined" / 纯空白   →  null
     * 其余                                        →  trim 后的值
     * </pre>
     *
     * <p><b>只对字符串字段调用</b>。数值字段（如 {@code price=0}）不能走这里 ——
     * 0 是有效值，转成 null 会丢数据。
     */
    public static String cleanValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()
                || LITERAL_NULL.equalsIgnoreCase(trimmed)
                || LITERAL_UNDEFINED.equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    // ==================================================================
    // 嵌套结构
    // ==================================================================

    /**
     * 信封里的「书单 ID / 名称」→ {@code Book.bookLists}（长度为 0 或 1 的数组）。
     *
     * <p>书单链路的每一次响应只对应<b>一个</b>书单，所以这里最多产出一个元素；
     * 一本书最终属于几个书单，是靠 {@code $addToSet} 在库里**跨次累加**出来的
     * （同一本书出现在多个书单里 → 多个元素）。
     *
     * <h3>两个刻意的决定</h3>
     * <ol>
     *   <li><b>没有 ID 就返回 {@code null}（不建引用）</b>：没有 ID 的引用没法用
     *       —— 既不能按书单筛书，也无法去重。存进去只会是垃圾数据。</li>
     *   <li><b>名称缺失时用 ID 兜底</b>：{@code $addToSet} 按<b>整个子文档</b>比较去重，
     *       同一个书单先写成 {@code {id, null}}、后写成 {@code {id, "真名"}} 会变成两条。
     *       用 ID 兜底（同「没有名称就用书单 ID 当名称」的口径）保证同一个书单
     *       两次写出来的子文档一致。</li>
     * </ol>
     *
     * @return {@code null} 表示不带书单信息（列表 / 榜单链路）
     */
    private List<Book.BookListRef> buildBookListRefs(String bookListId, String bookListName) {
        String id = cleanValue(bookListId);
        if (id == null) {
            return null;
        }
        String name = cleanValue(bookListName);
        List<Book.BookListRef> list = new ArrayList<Book.BookListRef>(1);
        list.add(Book.BookListRef.builder()
                .bookListId(id)
                .bookListName(name == null ? id : name)
                .build());
        return list;
    }

    /** {@code bookInfo.categories[]} —— 平台给这本书打的分类标签 */
    private List<Book.BookCategory> parseCategories(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<Book.BookCategory> list = new ArrayList<Book.BookCategory>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject c = arr.getJSONObject(i);
            if (c == null) {
                continue;
            }
            list.add(Book.BookCategory.builder()
                    .categoryId(c.getInteger("categoryId"))
                    .subCategoryId(c.getInteger("subCategoryId"))
                    .categoryType(c.getInteger("categoryType"))
                    .title(cleanValue(c.getString("title")))
                    .build());
        }
        return list.isEmpty() ? null : list;
    }

    /** {@code bookInfo.newRatingDetail} —— 评分分布 */
    private Book.RatingDetail parseRatingDetail(JSONObject rd) {
        if (rd == null || rd.isEmpty()) {
            return null;
        }
        return Book.RatingDetail.builder()
                .good(rd.getInteger("good"))
                .fair(rd.getInteger("fair"))
                .poor(rd.getInteger("poor"))
                .recent(rd.getInteger("recent"))
                .title(cleanValue(rd.getString("title")))
                .build();
    }

    /** {@code bookInfo.maxFreeInfo} —— 免费章节详情（原 Python 丢弃的字段之一） */
    private Book.MaxFreeInfo parseMaxFreeInfo(JSONObject mf) {
        if (mf == null || mf.isEmpty()) {
            return null;
        }
        return Book.MaxFreeInfo.builder()
                .maxFreeChapterIdx(mf.getInteger("maxFreeChapterIdx"))
                .maxFreeChapterUid(mf.getInteger("maxFreeChapterUid"))
                .maxFreeChapterRatio(mf.getInteger("maxFreeChapterRatio"))
                .build();
    }

    /** {@code bookInfo.paperBook.skuId} → 拍平成字符串 */
    private String parsePaperBookSkuId(JSONObject info) {
        JSONObject pb = info.getJSONObject("paperBook");
        if (pb == null) {
            return null;
        }
        return cleanValue(pb.getString("skuId"));
    }

    /** {@code bookInfo.newRatingDetail.title} → 拍平成字符串，如 "神作" */
    private String parseNewRatingTitle(JSONObject info) {
        JSONObject rd = info.getJSONObject("newRatingDetail");
        if (rd == null) {
            return null;
        }
        return cleanValue(rd.getString("title"));
    }

    /** {@code bookInfo.copyrightChapterUids} —— 整数数组（原 Python 丢弃的字段之一） */
    private List<Integer> parseIntList(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<Integer> list = new ArrayList<Integer>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Integer v = arr.getInteger(i);
            if (v != null) {
                list.add(v);
            }
        }
        return list.isEmpty() ? null : list;
    }

    // ==================================================================
    // book/info 补充字段的解析（2026-10-16）
    // ==================================================================

    /** {@code bookInfo.ratingDetail} —— 五档星级分布。⚠️ 与 newRatingDetail 不是一个对象 */
    private Book.ScoreDetail parseScoreDetail(JSONObject rd) {
        if (rd == null || rd.isEmpty()) {
            return null;
        }
        return Book.ScoreDetail.builder()
                .one(rd.getInteger("one"))
                .two(rd.getInteger("two"))
                .three(rd.getInteger("three"))
                .four(rd.getInteger("four"))
                .five(rd.getInteger("five"))
                .recent(rd.getInteger("recent"))
                .build();
    }

    /** {@code bookInfo.copyrightInfo} —— 版权方 */
    private Book.CopyrightInfo parseCopyrightInfo(JSONObject ci) {
        if (ci == null || ci.isEmpty()) {
            return null;
        }
        return Book.CopyrightInfo.builder()
                .id(ci.getLong("id"))
                .name(cleanValue(ci.getString("name")))
                .userVid(ci.getLong("userVid"))
                .role(ci.getInteger("role"))
                .avatar(cleanValue(ci.getString("avatar")))
                .cpType(ci.getInteger("cpType"))
                .build();
    }

    /** {@code bookInfo.authorSeg[]} / {@code translatorSeg[]} —— 带高亮的作者名分段 */
    private List<Book.AuthorSeg> parseAuthorSegs(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<Book.AuthorSeg> list = new ArrayList<Book.AuthorSeg>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject seg = arr.getJSONObject(i);
            if (seg == null) {
                continue;
            }
            list.add(Book.AuthorSeg.builder()
                    .words(cleanValue(seg.getString("words")))
                    .highlight(seg.getInteger("highlight"))
                    .authorId(cleanValue(seg.getString("authorId")))
                    .build());
        }
        return list.isEmpty() ? null : list;
    }

    /** {@code bookInfo.coverBoxInfo} —— 封面盒（含调色板） */
    private Book.CoverBoxInfo parseCoverBoxInfo(JSONObject cb) {
        if (cb == null || cb.isEmpty()) {
            return null;
        }
        return Book.CoverBoxInfo.builder()
                .blurhash(cleanValue(cb.getString("blurhash")))
                .colors(parseCoverColors(cb.getJSONArray("colors")))
                .dominateColor(parseDominateColor(cb.getJSONObject("dominate_color")))
                .customCover(cleanValue(cb.getString("custom_cover")))
                .customRecCover(cleanValue(cb.getString("custom_rec_cover")))
                .colorsPure(parseStringList(cb.getJSONArray("colorsPure")))
                .build();
    }

    /** {@code coverBoxInfo.colors[]} —— 调色板条目 */
    private List<Book.CoverColor> parseCoverColors(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<Book.CoverColor> list = new ArrayList<Book.CoverColor>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject c = arr.getJSONObject(i);
            if (c == null) {
                continue;
            }
            list.add(Book.CoverColor.builder()
                    .key(cleanValue(c.getString("key")))
                    .hex(cleanValue(c.getString("hex")))
                    .build());
        }
        return list.isEmpty() ? null : list;
    }

    /** {@code coverBoxInfo.dominate_color} —— 主色调 */
    private Book.DominateColor parseDominateColor(JSONObject dc) {
        if (dc == null || dc.isEmpty()) {
            return null;
        }
        JSONArray hsvArr = dc.getJSONArray("hsv");
        List<Double> hsv = null;
        if (hsvArr != null && !hsvArr.isEmpty()) {
            hsv = new ArrayList<Double>(hsvArr.size());
            for (int i = 0; i < hsvArr.size(); i++) {
                Double v = hsvArr.getDouble(i);
                if (v != null) {
                    hsv.add(v);
                }
            }
            if (hsv.isEmpty()) {
                hsv = null;
            }
        }
        return Book.DominateColor.builder()
                .hex(cleanValue(dc.getString("hex")))
                .hsv(hsv)
                .build();
    }

    /** {@code bookInfo.skuInfo} —— 小程序跳转 */
    private Book.SkuInfo parseSkuInfo(JSONObject si) {
        if (si == null || si.isEmpty()) {
            return null;
        }
        return Book.SkuInfo.builder()
                .miniProgramId(cleanValue(si.getString("miniProgramId")))
                .path(cleanValue(si.getString("path")))
                .build();
    }

    /** {@code bookInfo.shortTimeRead} —— 短时阅读开关 */
    private Book.ShortTimeRead parseShortTimeRead(JSONObject st) {
        if (st == null || st.isEmpty()) {
            return null;
        }
        return Book.ShortTimeRead.builder()
                .active(st.getInteger("active"))
                .build();
    }

    /**
     * {@code bookInfo.ranklist} —— 榜单信息。
     *
     * <p>接口实测给空对象 {@code {}}，此时返回 {@code null}（不写库）。
     * 转成普通 {@link LinkedHashMap} 而不是直接塞 fastjson 的 {@code JSONObject} ——
     * Mongo 转换器对「非 POJO 的自定义 Map 实现」处理起来更稳（同 {@code BookRepository}
     * 里手工转 Document 的理由）。
     */
    private Map<String, Object> parseRanklist(JSONObject obj) {
        if (obj == null || obj.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<String, Object>(obj);
    }

    /** 字符串数组（如 {@code colorsPure}），空数组返回 null */
    private List<String> parseStringList(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<String> list = new ArrayList<String>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            String v = cleanValue(arr.getString(i));
            if (v != null) {
                list.add(v);
            }
        }
        return list.isEmpty() ? null : list;
    }
}
