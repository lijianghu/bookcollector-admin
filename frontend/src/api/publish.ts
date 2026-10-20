import { request } from './request'
import type {
  BatchDeleteResult,
  CandidateBookVO,
  CandidateBuildRequest,
  InterpretationQuery,
  InterpretationResetTarget,
  InterpretationVO,
  PageResult,
  PublishConfigRequest,
  PublishJobVO,
  PublishRecord,
} from '@/types'

/**
 * 发布配置 / 发布记录接口（原 `api/ai.ts`）。
 *
 * <h3>2026-10-10 改名与搬家</h3>
 * 菜单从「AI → 书籍解读」拆成两个一级菜单（发布配置 / 发布记录），
 * 所以这个文件从 `api/ai.ts` 改名为 `api/publish.ts` —— 与后端 `publish` 包同名，
 * 也和路由前缀 `/publish/*` 对齐。**引用它的是 `views/publish/config/index.vue`、
 * `views/publish/record/index.vue`，以及 `views/book/featured/index.vue` 与它的
 * 「精选配置」弹窗 `views/book/featured/ConfigDialog.vue`**。
 *
 * <h3>一次「执行」只处理一本书</h3>
 * 无论手动点还是定时触发，一次就是「挑一本 → 生成 → 存本地 → 发语雀」。
 * 想连续做多本，就多点几次。
 *
 * <h3>🔴 长任务都是「提交即返回」</h3>
 * {@link initCandidates} 和 {@link triggerPublish} **不等待任务跑完**：
 * 生成一本要 30~120 秒，同步等会让页面卡在请求里、还容易被超时掐断。
 * 所以它们返回后 `initRunning` / `genRunning` 会是 true，
 * 调用方据此开始轮询 {@link getPublishJob}。
 *
 * <h3>候选清单是什么</h3>
 * 图书库有几万条，每次都全表扫「符合高质量条件且还没解读过」的书代价很大。
 * 所以先把符合条件的书**快照**进一张小表（`book_interpretations`，带「待解读」标记），
 * 挑书时只查这张表。已有的一律跳过，所以这些操作可以反复点。
 *
 * <p>往清单里挑书有两条路：
 * <ul>
 *   <li>{@link initCandidates} —— 旧入口，按 `ai_taxonomy_config` 白名单全量重建
 *       （前端已不再调用，端点保留着）；</li>
 *   <li>{@link previewCandidates} + {@link collectCandidates} —— 2026-10-12 新增的
 *       「精选配置」：先预览命中哪些书，再批量或全部精选。</li>
 * </ul>
 */

// ======================================================================
// 发布配置
// ======================================================================

/** 页面全量数据：配置 + 脱敏的模型信息 + 两条任务的进度 + 清单统计 */
export function getPublishJob() {
  return request<PublishJobVO>({
    url: '/publish/job',
    method: 'get',
  })
}

/**
 * 改配置。
 *
 * ⚠️ 两个字段都可选，**不传 = 不改这一项**。所以「只切启用开关」不要顺手把
 * cron 也传回去 —— 页面上那份可能是旧的，会把刚改的 cron 悄悄覆盖掉。
 *
 * cron 必须是 **6 段式**（秒 分 时 日 月 周），这是 Spring 的格式，不是 Linux 的 5 段式。
 * 非法表达式后端会返回 code=400，不会静默存进去。
 */
export function updatePublishConfig(data: PublishConfigRequest) {
  return request<PublishJobVO>({
    url: '/publish/job',
    method: 'put',
    data,
  })
}

/**
 * 初始化候选清单（提交即返回）。
 *
 * 按「分类白名单 + 评级档位 + 评价人数下限」从图书库里挑出高质量图书，
 * 写进候选清单。**已有的 bookId 一律跳过**，所以可以反复点。
 *
 * 进度看返回体里的 `initRunning` / `initScanned` / `initInserted`。
 */
export function initCandidates() {
  return request<PublishJobVO>({
    url: '/publish/candidates/init',
    method: 'post',
  })
}

/**
 * 精选预览（只读）。
 *
 * 按「精选配置」的条件从**图书库**查一页 —— 查的是 `books`，不是候选清单。
 * 条件与 {@link collectCandidates} 完全共用，保证「预览到什么、精选的就是什么」。
 *
 * ⚠️ 平台分类（`categoryIds`）必填；评价标签 / 推荐值下限 / 书名 / 作者 至少给一个。
 * 条件不完整时后端返回 code=400（拦截器已弹提示）。
 */
export function previewCandidates(
  data: CandidateBuildRequest,
  params: { page?: number; size?: number },
) {
  return request<PageResult<CandidateBookVO>>({
    url: '/publish/candidates/preview',
    method: 'post',
    data,
    params,
  })
}

/**
 * 精选：把符合条件的书写进候选清单（提交即返回）。
 *
 * - `all = true` → **全部精选**：忽略 `bookIds`，按条件把命中全部写进去；
 * - `all = false` → **批量精选**：只处理 `bookIds` 里那几个（预览列表里勾选的当页行）。
 *
 * ⚠️ **返回不代表已经跑完** —— 「全部精选」可能命中几千本、要几十秒。
 * 返回体里的 `initRunning` 会是 true，调用方据此开始轮询 {@link getPublishJob}，
 * 进度读 `initScanned` / `initInserted` / `initSkipped`（来自后端内存，刷新页面也还在）。
 *
 * 已有的 bookId 一律跳过（`$setOnInsert`），所以可以反复执行。
 */
export function collectCandidates(data: CandidateBuildRequest) {
  return request<PublishJobVO>({
    url: '/publish/candidates/collect',
    method: 'post',
    data,
  })
}

/**
 * 清空「待解读」的候选（提交即返回）。
 *
 * ⚠️ 只删「待解读」的，已发布 / 失败 / 生成中的不受影响。
 * 换过分类白名单之后，旧候选已经不再符合条件，需要先清空再重新初始化。
 *
 * <p>想删**任意状态**（含已发布）的条目，用 {@link batchDeleteInterpretations}。
 */
export function clearCandidates() {
  return request<PublishJobVO>({
    url: '/publish/candidates/clear',
    method: 'post',
  })
}

/**
 * 挑一本书生成解读并发布到语雀（提交即返回）。
 *
 * ⚠️ **返回不代表已经跑完** —— 生成 + 发布要 1~3 分钟。
 * 返回体里的 `genRunning` 会是 true，调用方据此开始轮询 {@link getPublishJob}。
 *
 * 已有任务在跑时后端返回 code=400（拦截器已弹提示）。
 */
export function triggerPublish() {
  return request<PublishJobVO>({
    url: '/publish/trigger',
    method: 'post',
  })
}

// ======================================================================
// 候选清单
// ======================================================================

/**
 * 候选清单分页。
 *
 * 筛选条件与图书库一致（状态 / 书名 / 作者 / 平台分类 / 推荐值区间 /
 * 阅读人数下限 / 出版时间区间）。
 *
 * <p>排序走 `sortField` + `asc`（2026-10-13 加）：6 个数值列可正反序，
 * 不传时后端默认 **推荐值降序**。字段名过 `InterpretationQuery` 的白名单，
 * 不认识的一律回落推荐值。
 *
 * ⚠️ 空白字符串**可以直接传**：后端 `InterpretationQuery` 会把空串归一成 null
 * 表示「不筛这一项」。所以这里不做过筛，URL 里可能出现 `title=` 这种空参数 ——
 * 结果是对的，只是不好看。
 */
export function pageInterpretations(query: InterpretationQuery) {
  return request<PageResult<InterpretationVO>>({
    url: '/publish/interpretations',
    method: 'get',
    params: query,
  })
}

/**
 * 批量删除候选清单条目。
 *
 * 🔴 **不限状态** —— 已发布到语雀的也能删。删的是清单里的这一行：
 * - **不会**删除语雀上的文章（那个要去语雀删）；
 * - **不会**影响「发布记录」里的历史流水。
 *
 * 返回**实际**删除数（请求里可能有 bookId 已经不在了）。
 */
export function batchDeleteInterpretations(bookIds: string[]) {
  return request<BatchDeleteResult>({
    url: '/publish/interpretations/batch-delete',
    method: 'post',
    data: { bookIds },
  })
}

/**
 * 「重置为」——把某一条的状态改成指定值（2026-10-16 从「重置为待解读」演化）。
 *
 * 三个用途：捞回卡住的「生成中」（进程被杀会残留）；把失败的重新排队
 * ——「失败不重试」指的是不**自动**重试；把清单里的状态改成实际发布情况
 * （比如手工发过之后标成「已发布」）。
 *
 * @param target 目标状态。只允许 `'PENDING'`（待解读）/ `'PUBLISHED'`（已发布）
 *   —— 后端对其它值返回 code=400。不传 = `PENDING`，与老调用行为一致。
 *
 * ⚠️ 标成 `PUBLISHED` 只改清单状态，**不会**在语雀上产生文章、也不写
 * `publish_record` 流水（那只有真的跑过发布流程才有）。
 */
export function resetInterpretation(bookId: string, target?: InterpretationResetTarget) {
  return request<boolean>({
    url: `/publish/interpretations/${encodeURIComponent(bookId)}/reset`,
    method: 'post',
    params: target ? { status: target } : undefined,
  })
}

// ======================================================================
// 发布记录
// ======================================================================

/** 发布记录分页。后端固定按发布时间倒序（最新在前） */
export function pagePublishRecords(params: { page?: number; size?: number }) {
  return request<PageResult<PublishRecord>>({
    url: '/publish/records',
    method: 'get',
    params,
  })
}
