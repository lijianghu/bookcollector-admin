import { request } from './request'
import type {
  InterpretationVO,
  PageResult,
  PublishConfigRequest,
  PublishJobVO,
  PublishStatus,
} from '@/types'

/**
 * AI 书籍解读接口。
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
 * 所以先由 {@link initCandidates} 把符合条件的书**快照**进一张小表
 * （`book_interpretations`，带「待解读」标记），挑书时只查这张表。
 * 已有的一律跳过，所以这个按钮可以反复点。
 */

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
 * 清空「待解读」的候选（提交即返回）。
 *
 * ⚠️ 只删「待解读」的，已发布 / 失败 / 生成中的不受影响。
 * 换过分类白名单之后，旧候选已经不再符合条件，需要先清空再重新初始化。
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

/** 候选清单分页。`status` 留空 = 全部 */
export function pageInterpretations(params: {
  status?: PublishStatus | ''
  page?: number
  size?: number
}) {
  return request<PageResult<InterpretationVO>>({
    url: '/publish/interpretations',
    method: 'get',
    params,
  })
}

/**
 * 把某一条重置回「待解读」。
 *
 * 两个用途：捞回卡住的「生成中」（进程被杀会残留）；把失败的重新排队
 * ——「失败不重试」指的是不**自动**重试。
 */
export function resetInterpretation(bookId: string) {
  return request<boolean>({
    url: `/publish/interpretations/${encodeURIComponent(bookId)}/reset`,
    method: 'post',
  })
}
