package com.bookcollector.booklist.req;

/**
 * 书单查询条件对象（书单配置页的列表筛选）。
 *
 * <p>与 {@code BookQuery} 同一个套路：Controller 收一个普通 POJO，
 * Spring MVC 按字段名把 {@code ?categoryId=200000&minCollectCount=10000} 自动绑上去；
 * 翻译成 Mongo {@code Criteria} 的活由 {@code BooklistRepository} 干。
 *
 * <h3>所有字段都是「与」关系（AND）</h3>
 * 多个条件之间没有 OR 语义 —— 唯一的例外是 {@link #keyword}，
 * 它自己内部是「名称 <b>或</b> ID 包含」的 OR（由 Repository 拼 {@code $or}）。
 *
 * <h3>⚠️ {@link #categoryId} 是<b>字符串</b></h3>
 * 平台分类 ID 形如 {@code "200000"} 甚至 {@code "topic"}，而
 * {@code books.categories[].categoryId} 是 <b>int</b>。两者不能直接互查 ——
 * 本项目踩过「拿字符串去匹配 int 字段 → 恒 0 条、不报错」的坑。
 * 这里匹配的是 {@code booklists.platformCategories[].categoryId}，它本身就是字符串。
 */
public class BooklistQuery {

    /** 每页最大条数，防止前端传 {@code size=100000} 把服务打爆 */
    public static final int MAX_SIZE = 200;

    /** 默认每页条数 */
    public static final int DEFAULT_SIZE = 20;

    /** 默认排序字段。收藏人数降序 = 「最有价值的书单排前面」 */
    public static final String DEFAULT_SORT_FIELD = "collectCount";

    /**
     * 关键词：<b>同时</b>匹配 {@code name} 与 {@code booklistId}，包含匹配（模糊搜索）。
     *
     * <p>为什么要搜 {@code booklistId}：链路 A 发现的书单自带名称，但用户自己那 124 张
     * 「我的书单」（一次性脚本预置）<b>只有 ID 没有名称</b>，名称要等链路 B 启动时回填。
     * 只搜名称的话，这批书单在回填之前根本搜不出来 —— 而「找出一张待回填的书单」
     * 恰恰是那时最常见的动作。
     */
    private String keyword;

    /** 平台分类 ID（字符串，精确匹配 {@code platformCategories.categoryId}）。空 = 不筛 */
    private String categoryId;

    /** 采书状态：{@code PENDING} / {@code COLLECTED} / {@code FAILED}。空 = 不筛 */
    private String collectStatus;

    /** 收藏人数下限（含） */
    private Integer minCollectCount;

    /** 收藏人数上限（含） */
    private Integer maxCollectCount;

    /** 书单书籍数下限（含）。闸门 1 是 50，想看「被闸门挡掉的那批」可以传 0 */
    private Integer minBookCount;

    /** 书单书籍数上限（含） */
    private Integer maxBookCount;

    /**
     * 只看「还没有名称」的书单（= 等待链路 B 启动时回填的那些）。
     *
     * <p>{@code true} 时匹配「{@code name} 为 null 或空串」；
     * 其它值（{@code false} / 不传）表示不筛这一项。
     *
     * <p>⚠️ 用 {@code Boolean} 而不是 {@code boolean}：{@code boolean} 的默认值是
     * {@code false}，前端「不传」和「传 false」就分不开了，而这个条件一旦恒为
     * {@code false} 还好，万一以后有人把它当「只看有名」用，默认值会把整个列表筛空。
     */
    private Boolean nameMissing;

    /** 排序字段。可选值见 {@link #resolveSortPath()}，非法值回退到收藏人数 */
    private String sortField = DEFAULT_SORT_FIELD;

    /** 是否升序。默认降序（收藏人数多的在前） */
    private boolean asc = false;

    /** 页码，从 1 开始 */
    private int page = 1;

    /** 每页条数 */
    private int size = DEFAULT_SIZE;

    public BooklistQuery() {
    }

    // ------------------------------------------------------------------
    // 归一化：外部传什么脏值都不能让查询出错
    // ------------------------------------------------------------------

    /** 页码至少为 1 */
    public int getSafePage() {
        return page < 1 ? 1 : page;
    }

    /** 每页条数夹在 [1, MAX_SIZE] */
    public int getSafeSize() {
        if (size < 1) {
            return DEFAULT_SIZE;
        }
        return size > MAX_SIZE ? MAX_SIZE : size;
    }

    /**
     * 把前端的排序字段名翻译成 Mongo 的字段路径。
     *
     * <p><b>白名单机制</b>：不合法的一律回退到 {@link #DEFAULT_SORT_FIELD}。
     * 绝不能把用户传的字符串直接拼进 Mongo 路径 —— 那是注入面。
     */
    public String resolveSortPath() {
        if (sortField == null) {
            return DEFAULT_SORT_FIELD;
        }
        switch (sortField.trim()) {
            case "bookCount":
                return "bookCount";
            case "bookCountCollected":
                return "bookCountCollected";
            case "lastSeenAt":
                return "lastSeenAt";
            case "firstSeenAt":
                return "firstSeenAt";
            case "lastCollectedAt":
                return "lastCollectedAt";
            case "collectCount":
            default:
                return DEFAULT_SORT_FIELD;
        }
    }

    /** 关键词。空白串归一成 null（「没填」而不是「找名字是空串的书单」） */
    public String resolveKeyword() {
        return blankToNull(keyword);
    }

    /** 平台分类 ID。空白串归一成 null */
    public String resolveCategoryId() {
        return blankToNull(categoryId);
    }

    /**
     * 采书状态。空白串归一成 null（= 不筛）。
     *
     * <p>⚠️ 这里<b>不</b>校验取值是否合法：库里存的是枚举名，
     * 传一个不存在的值只会筛出 0 条（不是错误）。合法性由
     * {@code BooklistCollectStatus.parse()} 在写入侧守住 —— 读取侧放宽，
     * 免得旧数据里一个脏值把整个列表打成 500（同 {@code BookQuery} 的口径）。
     */
    public String resolveCollectStatus() {
        return blankToNull(collectStatus);
    }

    /** 是否只看待回填名称的。{@code null} / {@code false} = 不筛 */
    public boolean isNameMissing() {
        return nameMissing != null && nameMissing.booleanValue();
    }

    private String blankToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }

    // ------------------------------------------------------------------
    // getter / setter
    // ------------------------------------------------------------------

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getCollectStatus() {
        return collectStatus;
    }

    public void setCollectStatus(String collectStatus) {
        this.collectStatus = collectStatus;
    }

    public Integer getMinCollectCount() {
        return minCollectCount;
    }

    public void setMinCollectCount(Integer minCollectCount) {
        this.minCollectCount = minCollectCount;
    }

    public Integer getMaxCollectCount() {
        return maxCollectCount;
    }

    public void setMaxCollectCount(Integer maxCollectCount) {
        this.maxCollectCount = maxCollectCount;
    }

    public Integer getMinBookCount() {
        return minBookCount;
    }

    public void setMinBookCount(Integer minBookCount) {
        this.minBookCount = minBookCount;
    }

    public Integer getMaxBookCount() {
        return maxBookCount;
    }

    public void setMaxBookCount(Integer maxBookCount) {
        this.maxBookCount = maxBookCount;
    }

    public Boolean getNameMissing() {
        return nameMissing;
    }

    public void setNameMissing(Boolean nameMissing) {
        this.nameMissing = nameMissing;
    }

    public String getSortField() {
        return sortField;
    }

    public void setSortField(String sortField) {
        this.sortField = sortField;
    }

    public boolean isAsc() {
        return asc;
    }

    public void setAsc(boolean asc) {
        this.asc = asc;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
