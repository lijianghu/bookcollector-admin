package com.bookcollector.publish.prompt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 书籍解读的<b>提示词模板</b>。
 *
 * <h3>模板来源</h3>
 * 用户 2026-10-09 给的固定结构：
 * <ul>
 *   <li>一级标题必须含作者名：{@code # 《书名》 作者名}</li>
 *   <li>8 个二级标题，每个带固定 icon</li>
 * </ul>
 * 用户明确：「文档要求和格式，全部都是 AI 生成的，Book 无正文也没事，这个 AI 知道，
 * 并且模拟用户输出内容」。所以「金句摘录」「我的思考」这些也由模型凭自身知识生成，
 * 我们<b>不</b>提供正文（库里也确实没有）。
 *
 * <h3>🔴 这个类只负责「告诉模型怎么写」，不负责检查</h3>
 * 产出<b>不做任何校验</b>（用户 2026-10-09 拍板）：<b>「AI 输出什么，就把什么输出到语雀即可」</b>。
 *
 * <p>历史上这里有过一个 {@code validate()}，先用 {@code firstLine.contains(author)} 卡作者名 ——
 * 那是<b>拿出版元数据当人名做字面比对</b>（{@code author} 形如
 * {@code [法]安托万·德·圣埃克苏佩里著}），而全库 4533 条里有 <b>1040 条（≈23%）</b>带
 * {@code [} / {@code 著编译绘} / {@code /}，会随机把生成卡死（《小王子》是第一条受害者）。
 * 后来连「有 H1 + 8 个 H2 齐全」也一并去掉了 —— 结构不完整也不该拦住发布。
 * <b>不要把它加回来。</b>
 *
 * <p>⚠️ 各节的「内容要求」是按标题含义拟的（用户给的表格里那列没有逐字抄到），
 * 需要调整就改 {@link #buildSystem()}。
 */
public final class InterpretationPrompt {

    /** 8 个二级标题（含 icon）。顺序即输出顺序，校验时逐个检查 */
    public static final List<String> SECTIONS = new ArrayList<String>(Arrays.asList(
            "📚 核心主题",
            "🧠 关键概念/模型",
            "💬 金句摘录",
            "💭 我的思考",
            "🚀 行动清单",
            "🔗 关联知识",
            "📝 后续计划",
            "⭐ 推荐指数"));

    private InterpretationPrompt() {
        throw new AssertionError("InterpretationPrompt 不允许实例化");
    }

    /**
     * 系统提示词 —— 定角色、定格式、定铁律。
     *
     * <p>刻意把「只输出 Markdown、不要任何额外说明」写进去：
     * 否则模型很容易在正文前后加一句「好的，以下是为您生成的解读：」，
     * 那句话会被原样写进本地文件、发到语雀。
     */
    public static String buildSystem() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深的图书解读作者，长期为中文读者撰写高质量书籍解读。\n\n");
        sb.append("请严格按照下面的结构输出一篇书籍解读，直接输出 Markdown 正文，")
                .append("不要有任何额外的说明、寒暄或代码块包裹。\n\n");
        sb.append("【标题格式】\n");
        sb.append("第一行必须是一级标题，且必须包含作者名，格式为：\n");
        sb.append("# 《书名》 作者名\n");
        sb.append("例如：# 《活着》余华\n\n");
        sb.append("【正文结构】\n");
        sb.append("一级标题之后，必须依次输出以下 8 个二级标题，每个都要带上对应的图标，")
                .append("一个都不能少、顺序不能变：\n");
        sb.append("## ").append(SECTIONS.get(0))
                .append("\n用 3~5 句话概括全书主旨与核心命题。不剧透关键情节的结局。\n");
        sb.append("## ").append(SECTIONS.get(1))
                .append("\n提炼书中最重要的 3~5 个概念、思维模型或论证框架，逐条列出并简要解释。\n");
        sb.append("## ").append(SECTIONS.get(2))
                .append("\n摘录 3~5 句书中令人印象深刻的句子，并标注页码或章节。\n");
        sb.append("## ").append(SECTIONS.get(3))
                .append("\n以读者第一人称写下读后的真实感受、疑问与联想，2~4 段。\n");
        sb.append("## ").append(SECTIONS.get(4))
                .append("\n给出读完这本书后可以立刻着手去做的 3~5 件具体的事，每条可执行。\n");
        sb.append("## ").append(SECTIONS.get(5))
                .append("\n指出这本书与其它书、其它学科或现实世界的关联，3 条以上。\n");
        sb.append("## ").append(SECTIONS.get(6))
                .append("\n写下接下来打算继续读什么、深入哪个方向，2~3 条。\n");
        sb.append("## ").append(SECTIONS.get(7))
                .append("\n给出 1~5 星评分并说明理由，同时注明这本书适合什么样的读者。\n\n");
        sb.append("【写作要求】\n");
        sb.append("1. 全中文，语气平实、具体，不要空话套话。\n");
        sb.append("2. 内容要贴合这本书本身，不要写成通用模板。\n");
        sb.append("3. 除了一级标题和这 8 个二级标题之外，不要添加任何其它标题。\n");
        return sb.toString();
    }

    /**
     * 用户提示词 —— 只喂「这本书是什么」，不喂正文。
     *
     * <p>库里确实没有正文，所以这里能给的只有元数据 + 简介。
     * 简介（{@code intro}）是唯一一段真实原文，带上它能明显提升准确度；
     * 缺失时也不影响 —— 模型会凭自身知识写。
     */
    public static String buildUser(String title, String author, String category, String intro) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为下面这本书写一篇解读：\n\n");
        sb.append("书名：").append(nvl(title)).append("\n");
        sb.append("作者：").append(nvl(author)).append("\n");
        if (category != null && !category.trim().isEmpty()) {
            sb.append("分类：").append(category.trim()).append("\n");
        }
        if (intro != null && !intro.trim().isEmpty()) {
            sb.append("\n以下是这本书的官方简介，供你参考（可能带有 [1] 这类脚注标记，请忽略）：\n");
            sb.append(intro.trim()).append("\n");
        }
        sb.append("\n请严格按系统提示里规定的结构输出。");
        return sb.toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }
}
