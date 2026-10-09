import { request } from './request'
import type { RefreshConfigRequest, RefreshJobVO } from '@/types'

/**
 * 定时刷新接口（3 个）。
 *
 * <h3>它和「任务中心」是什么关系</h3>
 * 刷新任务采的是同一批数据（7 个榜单 + 期刊杂志），但**不是**采集任务：
 * - 它**不建任务**、**不产生运行记录**（所以任务中心里看不到它）
 * - 它**完全不碰采集游标**（`skipCursor=true`）—— 用户手工建的榜单任务
 *   该怎么续传还怎么续传，两者互不干扰
 * - 它**没有断点续传**：每次从第 0 页跑到底。因为榜单每期换一批书，
 *   「从上次的位置继续翻」会漏掉排在前面的新上榜书
 *
 * <h3>为什么只有「最近一次」的结果</h3>
 * `lastDetails` 只保留最近一次执行。想看历史趋势应该去图书库按采集时间筛，
 * 而不是指望这里有一份流水台账。
 */

/** 页面全量数据：配置 + 是否执行中 + 8 个目标清单 + 最近一次结果 */
export function getRefreshJob() {
  return request<RefreshJobVO>({
    url: '/refresh/job',
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
export function updateRefreshConfig(data: RefreshConfigRequest) {
  return request<RefreshJobVO>({
    url: '/refresh/job',
    method: 'put',
    data,
  })
}

/**
 * 立即执行。
 *
 * ⚠️ **提交后立即返回，不代表已经跑完** —— 采集要跑 1~2 分钟。
 * 返回体里的 `running` 会是 true，调用方据此开始轮询 {@link getRefreshJob}。
 *
 * 两种失败会直接返回 code=400（拦截器已弹提示）：
 * - 已有批次在跑
 * - 某个目标正被手动采集占用 —— 此时是「中止整批」，不会部分执行
 */
export function triggerRefresh() {
  return request<RefreshJobVO>({
    url: '/refresh/trigger',
    method: 'post',
  })
}
