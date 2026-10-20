import { request } from './request'
import type { WereadCredentialRequest, WereadCredentialVO } from '@/types'

/**
 * 接口凭证（「书单配置」页顶部的表单）。
 *
 * <h3>为什么要做成接口，而不是写在前端常量里</h3>
 * 两个基础地址与登录态都属于「会变、且不该进版本库」的东西：
 * 登录态换一次就失效。前端写死的话，改一次就要重新构建一次前端。
 * 后端把它们读进内存，**保存后立即生效**，连重启都不需要。
 *
 * <h3>🔴 2026-10-15：八项凭证，明文回显 + 全量校验</h3>
 * 用户实测确认书单 URL 不带 `synckey` 时响应是拍平的（缺 bookInfo 与 intro），
 * 凭证因此从 3 项扩到 8 项（synckey 不用配置：当晚拍板「自动生成」，
 * 且取值口径是「后端 new Date() 的秒值」，每次书单请求发出前现取）。
 * 表单明文回显（他要回填）；缺任一项后端保存即拒（「书单配置信息不完整」）。
 * skey / synckey 的防线：**不进注释、不进日志**（后端有 @ToString.Exclude）。
 */

/** 读取凭证（明文回显） */
export function getWereadCredential() {
  return request<WereadCredentialVO>({
    url: '/weread/credential',
    method: 'get',
  })
}

/** 保存凭证。八项全量提交，缺任一项会被后端拒绝 */
export function saveWereadCredential(data: WereadCredentialRequest) {
  return request<WereadCredentialVO>({
    url: '/weread/credential',
    method: 'post',
    data,
  })
}
