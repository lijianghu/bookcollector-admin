package com.bookcollector.common.enums;

/**
 * 采集目标类型。
 *
 * <h3>它同时决定「用哪个接口」</h3>
 * 每种类型自带 {@link Endpoint}（接口族）与 {@link #getExtraQuery() 附加查询串}，
 * 由 {@code WereadClient#buildUrl} 按它拼 URL：
 * <ul>
 *   <li>{@link #CATEGORY} → {@code {listUrl}/{categoryId}?maxIndex={n}}</li>
 *   <li>{@link #RANKING}  → {@code {listUrl}/{rankingType}?maxIndex={n}&rank=1}</li>
 *   <li>{@link #BOOKLIST} → {@code {booklistUrl}/booklist/single?booklistId={id}}</li>
 * </ul>
 *
 * <h3>🔴 为什么 URL 知识要放在枚举里，而不是在 buildUrl 里写 if/else</h3>
 * 改造前 {@code buildUrl} 只有一个二元判断（{@code type == RANKING} 加 {@code &rank=1}，
 * 否则当分类处理）。新增第三种类型时，那种写法<b>不会报错</b> ——
 * 它会把书单当成分类，拼出一个格式合法但永远采不到数据的 URL，
 * 表现为「任务跑完了、新增 0 本」，属于最难查的静默失败。
 * 把接口族做成枚举的字段后，<b>漏配就是漏传构造参数，编译不过</b>。
 *
 * <p>放在 {@code common.enums} 而不是 {@code collector} 包，是为了让
 * {@code book.entity} / {@code task.entity} 也能引用它，而不产生
 * 「entity 反向依赖 collector」的循环。
 */
public enum TargetType {

    /** 分类榜，targetId 形如 "300000" */
    CATEGORY("分类", Endpoint.LIST, null),

    /** 榜单，targetId 形如 "rising" */
    RANKING("榜单", Endpoint.LIST, "rank=1"),

    /** 书单，targetId 形如 "391152476_7sKNjWqRN" */
    BOOKLIST("书单", Endpoint.BOOKLIST, null);

    /**
     * 接口族 —— 决定用哪个基础地址、拼哪套路径与参数。
     *
     * <p>基础地址本身<b>不在代码里</b>：两个族的地址都由
     * {@code WereadCredentialStore} 从库里读进内存（见该类的说明）。
     */
    public enum Endpoint {

        /** 列表接口（分类 / 榜单共用）。分页由 {@code maxIndex} 驱动 */
        LIST,

        /** 书单接口。一次返回整张书单（最多 500 本），<b>不分页</b> */
        BOOKLIST
    }

    private final String label;
    private final Endpoint endpoint;
    private final String extraQuery;

    TargetType(String label, Endpoint endpoint, String extraQuery) {
        this.label = label;
        this.endpoint = endpoint;
        this.extraQuery = extraQuery;
    }

    public String getLabel() {
        return label;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    /**
     * 追加到列表接口查询串后面的固定参数（没有则为 {@code null}）。
     *
     * <p>目前只有榜单需要 {@code rank=1}。做成数据而不是 {@code buildUrl} 里的
     * {@code if (type == RANKING)}，是为了让「哪种类型要带什么参数」一眼可查。
     */
    public String getExtraQuery() {
        return extraQuery;
    }

    /** 是否属于列表接口族（分类 / 榜单）。书单走另一条链路 */
    public boolean isList() {
        return endpoint == Endpoint.LIST;
    }

    /**
     * 宽松解析：忽略大小写与首尾空格。
     *
     * @throws IllegalArgumentException 值为空或不认识
     */
    public static TargetType of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("targetType 不能为空");
        }
        String v = value.trim().toUpperCase();
        for (TargetType t : values()) {
            if (t.name().equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的 targetType：" + value);
    }
}
