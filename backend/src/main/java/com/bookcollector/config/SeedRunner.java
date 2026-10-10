package com.bookcollector.config;

import com.bookcollector.auth.entity.SysUser;
import com.bookcollector.auth.repository.SysUserRepository;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.setting.entity.Setting;
import com.bookcollector.taxonomy.entity.TaxonomyItem;
import com.bookcollector.util.Md5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动时初始化字典数据。
 *
 * <h3>数据来源</h3>
 * 一级分类 22 个 + 二级分类 166 条（{@link #SUB_CATEGORIES}）+ 榜单 7 个。
 * 一级分类与榜单<b>逐字抄自原 {@code config.py}</b> 的 {@code CATEGORIES} /
 * {@code RANKING_LISTS}，顺序也保持一致（决定前端下拉框顺序），
 * <b>唯二的偏离见 {@link #CATEGORIES} 的注释</b>（1900001/2000001 修正 + 补漫画）。
 * 二级分类上游没有分类树接口，清单由用户人工整理（2026-09-24），
 * <b>2026-10-10 又拿库里 {@code books.categories[]} 的真实数据做了一次全量校对</b>
 * —— 补上 53 个漏掉的 code，并按 code 的真实 title 纠正了 7 条错位。
 *
 * <h3>幂等策略：只补缺失，不覆盖已有</h3>
 * 不是 upsert，而是「先查已有 key，再只插没有的」。这么做是因为：
 * <ul>
 *   <li>用户在界面上把某个分类改成 {@code enabled=false}（停用）后，
 *       重启服务<b>不应该</b>被重置回启用状态 —— upsert 就会踩这个坑</li>
 *   <li>字典是低频变更数据，代码里改名字后需要生效的场景几乎不存在；
 *       真发生了，手工改库比「每次启动覆盖用户设置」更可控</li>
 * </ul>
 *
 * <p>🔴 <b>这条策略的代价</b>：本类<b>改不动已有行</b>。所以 2026-10-10 的那次纠错
 * 只能把「新表」写对，旧库里已经存在的那 14 条错行（7 条错名 + 7 条无效 code 待停用）
 * 得靠一次性脚本 {@code tools/fix_taxonomy.py} 处理。见 {@link #SUB_CATEGORIES} 的注释。
 *
 * <p>执行顺序排在 {@link MongoIndexInitializer} 之后，保证 {@code uk_type_code}
 * 唯一索引已经就位 —— 否则并发/重复启动时可能插出重复字典。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    /**
     * 一级分类。抄自 config.py 的 {@code CATEGORIES}，顺序也一致（决定前端下拉顺序）。
     *
     * <h3>🔴 2026-10-10 修正：{@code 1900001} / {@code 2000001} 不是一级</h3>
     * 原文件（以及照抄它的本表）把 {@code 1900001} / {@code 2000001} 当成一级分类，
     * 但实测它们其实是<b>二级</b> code：
     * <pre>
     * code      当 categoryId 出现   当 subCategoryId 出现
     * 1900000          572                  0     ← 真正的一级
     * 1900001            0                499     ← 其实是二级（男生小说-东方玄幻）
     * 2000000          523                  0     ← 真正的一级
     * 2000001            0                500     ← 其实是二级（女生小说-穿越架空）
     * </pre>
     * 照抄的后果：字典里「男生小说」下面<b>一条子分类都挂不上</b>，
     * 而用户按字典建的任务（{@code targetId=1900001}）实际采的是「东方玄幻」这一个二级分类。
     *
     * <p>现在两个 code 已改回一级 {@code 1900000} / {@code 2000000}，
     * 而 {@code 1900001} / {@code 2000001} 移入 {@link #SUB_CATEGORIES} 当二级。
     * 另外补上整个缺失的一级分类「漫画」({@code 2400000})。
     *
     * <p>⚠️ <b>已有库上这一步不会自动生效</b>：{@code 1900001} / {@code 2000001}
     * 是以一级身份存在的老数据，而本类是「只补缺失、不覆盖已有」——
     * 它们的 {@code parentCode} 要靠一次性脚本 {@code tools/fix_taxonomy.py} 补。
     */
    private static final String[][] CATEGORIES = {
            {"300000", "文学"},
            {"100000", "精品小说"},
            {"200000", "历史"},
            {"400000", "艺术"},
            {"500000", "人物传记"},
            {"600000", "哲学宗教"},
            {"700000", "计算机"},
            {"800000", "心理"},
            {"900000", "社会文化"},
            {"1000000", "个人成长"},
            {"1100000", "经济理财"},
            {"1200000", "政治军事"},
            {"1300000", "童书"},
            {"1400000", "教育学习"},
            {"1500000", "科学技术"},
            {"1600000", "生活百科"},
            {"1700000", "期刊杂志"},
            {"1800000", "原版书"},
            {"1900000", "男生小说"},
            {"2000000", "女生小说"},
            {"2100000", "医学健康"},
            {"2400000", "漫画"}
    };

    /** 7 个榜单。抄自 config.py 的 {@code RANKING_LISTS}。 */
    private static final String[][] RANKINGS = {
            {"rising", "飙升榜"},
            {"hot_search", "热搜榜"},
            {"newbook", "新书榜"},
            {"general_novel_rising", "小说飙升榜"},
            {"all", "总榜"},
            {"newrating_publish", "神作榜"},
            {"newrating_potential_publish", "潜力榜"}
    };

    /**
     * 二级分类。格式 {@code {parentCode, code, name}}。
     *
     * <h3>为什么二级也要进字典</h3>
     * 采集 URL 是 {@code /bookListInCategory/{code}}，而 {@code targetId} 只是被
     * <b>原样拼进路径</b>（见 {@code WereadClient#buildUrl}）—— 传二级 ID 同样成立。
     * 上游对<b>每个 ID 各自</b>封顶 25 页（500 本），所以「历史」+「历史-历史读物」…
     * 等于把可采总量放大一个量级。二级同样要进字典，才有下拉框、才有名称映射、
     * 才受「有任务引用不许删」的保护。
     *
     * <h3>name 一律用「一级-二级」形式</h3>
     * 与平台 {@code bookInfo.categories[].title} / {@code Book.category} 的格式
     * <b>完全一致</b>（如 {@code "精品小说-社会小说"}），这样字典页、任务名、
     * 分类分布饼图三处口径自动对齐。
     *
     * <h3>清单来源：用户人工整理（2026-09-24）+ 2026-10-10 按库里真实数据补齐</h3>
     * 上游没有分类树接口，二级清单只能人工整理。填法：
     * <pre>
     * {"200000", "200003", "历史-历史读物"},
     * </pre>
     * <b>这张表为空 = 不播种任何二级分类</b>，功能相当于关闭。
     *
     * <h3>🔴 2026-10-10：按「库里书真实出现过的分类」补齐并纠错</h3>
     * 做法是拿 {@code books.categories[]} 反查——一本书挂的
     * {@code {categoryId, subCategoryId, title}} 是上游给的<b>权威事实</b>，
     * 比人工整理可靠。比对结果发现人工清单有两类问题：
     * <ol>
     *   <li><b>漏</b>：库里出现过但清单里没有的 code，共 53 个
     *       （精品小说 21 + 文学 2 + 艺术 1 + 期刊杂志 3 + 原版书 1 +
     *       男生小说 11 + 女生小说 10 + 漫画 4）。现已全部补上。</li>
     *   <li><b>错位</b>：清单里有 7 条的 code 与名字对不上（名字是对的，
     *       code 挪了一位）。例如清单写 {@code 300007 文学-戏剧文学}，
     *       但库里 {@code 300007} 的真实 title 是「文学-文学鉴赏」。
     *       ⚠️ 本类<b>只补不覆盖</b>，改不了已有行 ——
     *       这 7 条的改名与 7 条无效 code 的停用由 {@code tools/fix_taxonomy.py} 负责。</li>
     * </ol>
     *
     * <p>共 <b>166 条</b>，覆盖 <b>22 个一级分类</b>（除「医学健康」只有 3 条外，
     * 其余每个一级下面都有完整的二级清单）。
     *
     * <p>⚠️ 顺序即 {@code sort}，也就是前端分组内的展示顺序，<b>按用户给的原始顺序排</b>，不要重排。
     * 新增的组按同样的逻辑插在各自邻居旁边。
     */
    private static final String[][] SUB_CATEGORIES = {
            // ===== 精品小说 100000（21 条，2026-10-10 补）=====
            // ⚠️ 这一组是 2026-09-24 清单里「用户刻意跳过」的，2026-10-10 按库里真实数据补回
            {"100000", "100001", "精品小说-社会小说"},
            {"100000", "100002", "精品小说-情感小说"},
            {"100000", "100003", "精品小说-青春文学"},
            {"100000", "100004", "精品小说-科幻小说"},
            {"100000", "100005", "精品小说-战争军旅"},
            {"100000", "100006", "精品小说-玄幻小说"},
            {"100000", "100007", "精品小说-恐怖惊悚"},
            {"100000", "100008", "精品小说-悬疑推理"},
            {"100000", "100011", "精品小说-武侠小说"},
            {"100000", "100012", "精品小说-影视原著"},
            {"100000", "100013", "精品小说-优质网文"},
            {"100000", "100014", "精品小说-历史架空"},
            {"100000", "100015", "精品小说-家庭故事"},
            {"100000", "100016", "精品小说-职场小说"},
            {"100000", "100017", "精品小说-官场小说"},
            {"100000", "100018", "精品小说-古言精品"},
            {"100000", "100019", "精品小说-民俗志怪"},
            {"100000", "100020", "精品小说-女性小说"},
            {"100000", "100021", "精品小说-年代小说"},
            {"100000", "100022", "精品小说-治愈小说"},
            {"100000", "100023", "精品小说-纪实小说"},

            // ===== 历史 200000（9 条）=====
            {"200000", "200001", "历史-历史地理"},
            {"200000", "200002", "历史-历史典籍"},
            {"200000", "200003", "历史-历史读物"},
            {"200000", "200004", "历史-历史小说"},
            {"200000", "200005", "历史-口述史"},
            {"200000", "200006", "历史-世界史"},
            {"200000", "200007", "历史-中国古代"},
            {"200000", "200008", "历史-历史文化"},
            {"200000", "200009", "历史-中国近现代"},

            // ===== 文学 300000（12 条）=====
            // 🔴 2026-10-10 纠错：原清单把 300006 / 300011 也写进来了（这两个 code
            //    在库里从未出现过，是无效的），而 300007..300012 的<b>名字整体挪了一位</b>
            //    —— 例如 300007 的真实 title 是「文学-文学鉴赏」而不是「文学-戏剧文学」。
            //    这里按「code 在库里的真实 title」重写。无效的两条已从本表移除，
            //    它们在旧库里已存在，由 tools/fix_taxonomy.py 停用。
            {"300000", "300001", "文学-古典文学"},
            {"300000", "300002", "文学-纪实文学"},
            {"300000", "300003", "文学-民间文学"},
            {"300000", "300004", "文学-经典作品"},
            {"300000", "300005", "文学-散文杂著"},
            {"300000", "300007", "文学-文学鉴赏"},
            {"300000", "300008", "文学-戏剧文学"},
            {"300000", "300009", "文学-语言文字"},
            {"300000", "300010", "文学-现代诗歌"},
            {"300000", "300012", "文学-古代诗词"},
            {"300000", "300013", "文学-外国文学"},
            {"300000", "300014", "文学-世界名著"},

            // ===== 艺术 400000（13 条）=====
            // 🔴 2026-10-10 纠错：400011 是无效 code（已移除，旧库由脚本停用）；
            //    400012/400013 的名字同样挪了一位，补上真实存在的 400014。
            {"400000", "400001", "艺术-雕塑"},
            {"400000", "400002", "艺术-绘画"},
            {"400000", "400003", "艺术-建筑"},
            {"400000", "400004", "艺术-书法"},
            {"400000", "400005", "艺术-工艺"},
            {"400000", "400006", "艺术-鉴赏"},
            {"400000", "400007", "艺术-理论"},
            {"400000", "400008", "艺术-影视"},
            {"400000", "400009", "艺术-摄影"},
            {"400000", "400010", "艺术-民艺"},
            {"400000", "400012", "艺术-设计"},
            {"400000", "400013", "艺术-舞蹈"},
            {"400000", "400014", "艺术-音乐"},

            // ===== 人物传记 500000（10 条）=====
            {"500000", "500001", "人物传记-财经人物"},
            {"500000", "500002", "人物传记-传记综合"},
            {"500000", "500003", "人物传记-军政领袖"},
            {"500000", "500004", "人物传记-科学家"},
            {"500000", "500005", "人物传记-历史人物"},
            {"500000", "500006", "人物传记-女性人物"},
            {"500000", "500007", "人物传记-文学家"},
            {"500000", "500008", "人物传记-学者"},
            {"500000", "500009", "人物传记-艺术家"},
            {"500000", "500010", "人物传记-娱乐明星"},

            // ===== 哲学宗教 600000（10 条）=====
            {"600000", "600001", "哲学宗教-东方哲学"},
            {"600000", "600002", "哲学宗教-伦理道德"},
            {"600000", "600003", "哲学宗教-逻辑学"},
            {"600000", "600004", "哲学宗教-马克思哲学"},
            {"600000", "600005", "哲学宗教-美学"},
            {"600000", "600006", "哲学宗教-思维科学"},
            {"600000", "600007", "哲学宗教-西方哲学"},
            {"600000", "600008", "哲学宗教-哲学读物"},
            {"600000", "600009", "哲学宗教-哲学著作"},
            {"600000", "600010", "哲学宗教-宗教"},

            // ===== 计算机 700000（7 条）=====
            {"700000", "700001", "计算机-软件学习"},
            {"700000", "700002", "计算机-编程设计"},
            {"700000", "700003", "计算机-计算机综合"},
            {"700000", "700004", "计算机-理论知识"},
            {"700000", "700005", "计算机-人工智能"},
            {"700000", "700006", "计算机-数据库"},
            {"700000", "700007", "计算机-图像视频"},

            // ===== 心理 800000（7 条）=====
            {"800000", "800001", "心理-积极心理学"},
            {"800000", "800002", "心理-社会心理学"},
            {"800000", "800003", "心理-认知与行为"},
            {"800000", "800004", "心理-心理学研究"},
            {"800000", "800005", "心理-心理学应用"},
            {"800000", "800006", "心理-发展心理学"},
            {"800000", "800007", "心理-亲密关系"},

            // ===== 社会文化 900000（3 条）=====
            {"900000", "900001", "社会文化-法律"},
            {"900000", "900002", "社会文化-社科"},
            {"900000", "900003", "社会文化-文化"},

            // ===== 个人成长 1000000（7 条）=====
            {"1000000", "1000001", "个人成长-沟通表达"},
            {"1000000", "1000002", "个人成长-励志成长"},
            {"1000000", "1000003", "个人成长-情绪心灵"},
            {"1000000", "1000004", "个人成长-人生哲学"},
            {"1000000", "1000005", "个人成长-人在职场"},
            {"1000000", "1000006", "个人成长-认知思维"},
            {"1000000", "1000007", "个人成长-女性成长"},

            // ===== 经济理财 1100000（4 条）=====
            {"1100000", "1100001", "经济理财-财经"},
            {"1100000", "1100002", "经济理财-管理"},
            {"1100000", "1100003", "经济理财-理财"},
            {"1100000", "1100004", "经济理财-商业"},

            // ===== 政治军事 1200000（2 条）=====
            // 🔴 2026-10-10 纠错（第二次）：这两条的名字**整体写反了** ——
            //    库里 books.categories[] 的真实 title 是 1200001「政治军事-军事」、
            //    1200002「政治军事-政治」。用户已核对官网确认。
            {"1200000", "1200001", "政治军事-军事"},
            {"1200000", "1200002", "政治军事-政治"},

            // ===== 童书 1300000（5 条）=====
            {"1300000", "1300001", "童书-儿童文学"},
            {"1300000", "1300002", "童书-漫画卡通"},
            {"1300000", "1300003", "童书-少儿英语"},
            {"1300000", "1300004", "童书-幼儿启蒙"},
            {"1300000", "1300005", "童书-阅读工具书"},

            // ===== 教育学习 1400000（6 条）=====
            {"1400000", "1400001", "教育学习-工具书"},
            {"1400000", "1400002", "教育学习-教材"},
            {"1400000", "1400003", "教育学习-教育"},
            {"1400000", "1400004", "教育学习-考试"},
            {"1400000", "1400005", "教育学习-外语"},
            {"1400000", "1400006", "教育学习-育儿"},

            // ===== 科学技术 1500000（5 条）=====
            {"1500000", "1500001", "科学技术-工业技术"},
            {"1500000", "1500002", "科学技术-建筑"},
            {"1500000", "1500003", "科学技术-科学科普"},
            {"1500000", "1500004", "科学技术-农林牧业"},
            {"1500000", "1500005", "科学技术-自然科学"},

            // ===== 生活百科 1600000（9 条）=====
            {"1600000", "1600001", "生活百科-居家"},
            {"1600000", "1600002", "生活百科-旅游"},
            {"1600000", "1600003", "生活百科-美食"},
            {"1600000", "1600004", "生活百科-命理"},
            {"1600000", "1600005", "生活百科-情感"},
            {"1600000", "1600006", "生活百科-时尚"},
            {"1600000", "1600007", "生活百科-手工"},
            {"1600000", "1600008", "生活百科-体育"},
            {"1600000", "1600009", "生活百科-游戏"},

            // ===== 期刊杂志 1700000（4 条）=====
            // 🔴 2026-10-10 纠错：原清单写 1700002/1700003/1700004，
            //    但这三个 code 在库里从未出现过（建任务也采不到书）。
            //    真实的 code 是 1700010/1700015/1700016，名字一一对应。
            {"1700000", "1700001", "期刊杂志-财经"},
            {"1700000", "1700010", "期刊杂志-生活"},
            {"1700000", "1700015", "期刊杂志-文学"},
            {"1700000", "1700016", "期刊杂志-其他"},

            // ===== 原版书 1800000（2 条）=====
            // 🔴 2026-10-10 纠错：原清单写 1800002，真实 code 是 1800003
            {"1800000", "1800001", "原版书-英文原版"},
            {"1800000", "1800003", "原版书-日文原版"},

            // ===== 男生小说 1900000（12 条，2026-10-10 补）=====
            // ⚠️ 一级 code 是 1900000 而不是 1900001（见 CATEGORIES 的说明）。
            //    1900001 本身就是二级 code，排在这里的第一个。
            {"1900000", "1900001", "男生小说-东方玄幻"},
            {"1900000", "1900002", "男生小说-都市小说"},
            {"1900000", "1900003", "男生小说-仙侠武侠"},
            {"1900000", "1900005", "男生小说-异术超能"},
            {"1900000", "1900006", "男生小说-奇幻异界"},
            {"1900000", "1900007", "男生小说-同人衍生"},
            {"1900000", "1900008", "男生小说-历史架空"},
            {"1900000", "1900009", "男生小说-游戏竞技"},
            {"1900000", "1900010", "男生小说-科幻未来"},
            {"1900000", "1900011", "男生小说-时空穿梭"},
            {"1900000", "1900012", "男生小说-悬疑灵异"},
            {"1900000", "1900015", "男生小说-年代幻想"},

            // ===== 女生小说 2000000（11 条，2026-10-10 补）=====
            {"2000000", "2000001", "女生小说-穿越架空"},
            {"2000000", "2000002", "女生小说-豪门世家"},
            {"2000000", "2000003", "女生小说-现代言情"},
            {"2000000", "2000004", "女生小说-玄幻言情"},
            {"2000000", "2000005", "女生小说-古代言情"},
            {"2000000", "2000007", "女生小说-宫闱宅斗"},
            {"2000000", "2000010", "女生小说-经商种田"},
            {"2000000", "2000011", "女生小说-青春言情"},
            {"2000000", "2000012", "女生小说-科幻神秘"},
            {"2000000", "2000015", "女生小说-纯爱小说"},
            {"2000000", "2000018", "女生小说-悬疑推理"},

            // ===== 漫画 2400000（4 条，2026-10-10 补）=====
            {"2400000", "2400001", "漫画-经典改编"},
            {"2400000", "2400008", "漫画-科普漫画"},
            {"2400000", "2400009", "漫画-治愈漫画"},
            {"2400000", "2400010", "漫画-儿童漫画"},

            // ===== 医学健康 2100000（3 条）=====
            {"2100000", "2100001", "医学健康-健康"},
            {"2100000", "2100002", "医学健康-两性"},
            {"2100000", "2100003", "医学健康-医学"}
    };

    /**
     * 系统配置默认值。抄自 config.py 的 {@code COLLECTION_CONFIG.default_max_pages}
     * 与 {@code API_CONFIG.delay_between_requests}。
     */
    private static final String[][] SETTING_DEFAULTS = {
            {"defaultMaxPages", "10", "新建采集任务时的默认最大页数"},
            {"requestDelayMs", "1000", "每页请求之间的间隔（毫秒）"}
    };

    private final MongoTemplate mongoTemplate;
    private final AuthProperties authProperties;
    private final SysUserRepository sysUserRepository;

    public SeedRunner(MongoTemplate mongoTemplate,
                      AuthProperties authProperties,
                      SysUserRepository sysUserRepository) {
        this.mongoTemplate = mongoTemplate;
        this.authProperties = authProperties;
        this.sysUserRepository = sysUserRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("========== 开始初始化基础数据 ==========");

        SeedStat catStat = seedTaxonomy(TargetType.CATEGORY, CATEGORIES);
        SeedStat subStat = seedSubCategories();
        SeedStat rankStat = seedTaxonomy(TargetType.RANKING, RANKINGS);
        SeedStat setStat = seedSettings();
        boolean adminSeeded = seedSysUser();

        log.info("基础数据初始化完成：");
        log.info("  一级分类 taxonomy_config(CATEGORY)：新增 {} 条，已存在 {} 条，共 {} 条",
                catStat.inserted, catStat.skipped, catStat.total());
        log.info("  二级分类 taxonomy_config(二级)     ：新增 {} 条，已存在 {} 条，共 {} 条",
                subStat.inserted, subStat.skipped, subStat.total());
        log.info("  榜单 taxonomy_config(RANKING)     ：新增 {} 条，已存在 {} 条，共 {} 条",
                rankStat.inserted, rankStat.skipped, rankStat.total());
        log.info("  配置 settings                   ：新增 {} 条，已存在 {} 条",
                setStat.inserted, setStat.skipped);
        log.info("  管理员 sys_user                 ：{}",
                adminSeeded ? "已创建初始账号" : "已存在同名账号，未改动");

        int expect = CATEGORIES.length + SUB_CATEGORIES.length + RANKINGS.length;
        long actual = mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(),
                TaxonomyItem.class);
        if (actual != expect) {
            log.warn("⚠️ taxonomy_config 实际条数 {} 与预期 {} 不一致，请检查是否有残留脏数据", actual, expect);
        }
        log.info("========== 基础数据初始化结束 ==========");
    }

    /**
     * 播种初始管理员账号（{@code sys_user}）。
     *
     * <h3>幂等：库里有同名用户就什么都不做</h3>
     * 这一条是<b>这次改造的关键</b>。如果写成「每次启动都按 yml 覆盖密码」，
     * 那「改密码」就退化成「改 yml + 重启」—— 正好是原来写死登录的老毛病，
     * 白折腾一场。所以这里的语义是「<b>只在库为空时建</b>」，
     * 之后 yml 里的 {@code initial-password} 就与运行状态无关了。
     *
     * <h3>落库的是 MD5 摘要，不是明文</h3>
     * 明文只在 {@code application.yml} 和这一次 {@code Md5Util.md5(...)} 调用里存在。
     * 库里存摘要的直接好处：数据库备份、日志、以及随手
     * {@code db.sys_user.find()} 的屏幕上都看不到密码。
     *
     * @return 是否真的创建了账号（false = 已存在，跳过）
     */
    private boolean seedSysUser() {
        String username = authProperties.getInitialUsername();
        if (username == null || username.trim().isEmpty()) {
            log.warn("bookcollector.auth.initial-username 为空，跳过初始管理员播种");
            return false;
        }
        String password = authProperties.getInitialPassword();
        if (password == null || password.isEmpty()) {
            // 不建一个「密码为 null、永远登不进去」的账号 —— 那比不建更糟：
            // 它会占住 username，让后来配好密码的重启也播不进去
            log.warn("bookcollector.auth.initial-password 为空，跳过初始管理员播种（否则会建出一个永远登不进去的账号）");
            return false;
        }

        if (sysUserRepository.existsByUsername(username)) {
            return false;
        }

        SysUser admin = SysUser.builder()
                .username(username.trim())
                // ★ 明文在这里结束，库里只有摘要
                .password(Md5Util.md5(password))
                .nickname(authProperties.getInitialNickname())
                // 占位角色，不参与任何鉴权（见 auth/AGENTS.md 铁律 8）。
                // 不放进 yml：它没有行为，配置它只是自欺欺人的「可配置」。
                .roles(Collections.singletonList("admin"))
                .enabled(Boolean.TRUE)
                .createdAt(new Date())
                .build();
        sysUserRepository.save(admin);

        log.info("已创建初始管理员账号：username={}（密码为 bookcollector.auth.initial-password 的 MD5 摘要；"
                + "之后改密码请直接改库，本配置不再生效）", admin.getUsername());
        return true;
    }

    /** 按 (type, code) 补齐缺失的字典项，已有的一律不动。rows 格式 {@code {code, name}} */
    private SeedStat seedTaxonomy(TargetType type, String[][] rows) {
        return seedRows(type, rows, false, 1);
    }

    /**
     * 播种二级分类。rows 格式 {@code {parentCode, code, name}}。
     *
     * <p><b>🔴 sort 必须从「当前 CATEGORY 的最大 sort + 1」开始，不能从 1。</b>
     * 一级分类已经占了 1..21，从 1 重排会和它们撞车；而 seed 是「只补缺失、不覆盖已有」，
     * 所以已有库上二级的 sort <b>注定排在全部一级之后</b>，无法插回各自上级的后面。
     * → 因此前端<b>按 {@code parentCode} 分组渲染</b>，不依赖全局 sort 的连续性。
     */
    private SeedStat seedSubCategories() {
        return seedRows(TargetType.CATEGORY, SUB_CATEGORIES, true,
                maxSortOf(TargetType.CATEGORY) + 1);
    }

    /** 当前类型下已有的最大 sort（一条都没有则返回 0） */
    private int maxSortOf(TargetType type) {
        int max = 0;
        for (TaxonomyItem item : mongoTemplate.findAll(TaxonomyItem.class)) {
            if (type.name().equals(item.getType())
                    && item.getSort() != null && item.getSort() > max) {
                max = item.getSort();
            }
        }
        return max;
    }

    /**
     * 按 (type, code) 补齐缺失的字典项，已有的一律不动。
     *
     * @param rows        {@code withParent ? {parentCode, code, name} : {code, name}}
     * @param withParent  true 表示 rows 是三列（二级分类），false 是两列
     * @param startSort   本次新增条目的起始排序号
     */
    private SeedStat seedRows(TargetType type, String[][] rows, boolean withParent, int startSort) {
        Set<String> existing = new HashSet<String>();
        List<TaxonomyItem> all = mongoTemplate.findAll(TaxonomyItem.class);
        for (TaxonomyItem item : all) {
            if (item.getType() != null && item.getCode() != null) {
                existing.add(item.getType() + "|" + item.getCode());
            }
        }

        List<TaxonomyItem> toInsert = new ArrayList<TaxonomyItem>();
        int sort = startSort;
        for (String[] row : rows) {
            String parentCode = withParent ? row[0] : null;
            String code = withParent ? row[1] : row[0];
            String name = withParent ? row[2] : row[1];

            String key = type.name() + "|" + code;
            if (existing.contains(key)) {
                sort++;
                continue;
            }
            toInsert.add(TaxonomyItem.builder()
                    .type(type.name())
                    .code(code)
                    .name(name)
                    .parentCode(parentCode)
                    .enabled(Boolean.TRUE)
                    .sort(sort)
                    .build());
            sort++;
        }

        if (!toInsert.isEmpty()) {
            mongoTemplate.insertAll(toInsert);
        }
        return new SeedStat(toInsert.size(), rows.length - toInsert.size());
    }

    /** 补齐缺失的配置项，已有的一律不动（用户改过的值不能被重启冲掉） */
    private SeedStat seedSettings() {
        Set<String> existing = new HashSet<String>();
        for (Setting s : mongoTemplate.findAll(Setting.class)) {
            if (s.getKey() != null) {
                existing.add(s.getKey());
            }
        }

        List<Setting> toInsert = new ArrayList<Setting>();
        for (String[] row : SETTING_DEFAULTS) {
            if (existing.contains(row[0])) {
                continue;
            }
            toInsert.add(Setting.builder()
                    .key(row[0])
                    .value(row[1])
                    .remark(row[2])
                    .updatedAt(new Date())
                    .build());
        }

        if (!toInsert.isEmpty()) {
            mongoTemplate.insertAll(toInsert);
        }
        return new SeedStat(toInsert.size(), SETTING_DEFAULTS.length - toInsert.size());
    }

    /** 小工具：记录一次 seed 的新增/跳过数量 */
    private static final class SeedStat {
        final int inserted;
        final int skipped;

        SeedStat(int inserted, int skipped) {
            this.inserted = inserted;
            this.skipped = skipped;
        }

        int total() {
            return inserted + skipped;
        }
    }
}
