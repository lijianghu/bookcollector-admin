import { request } from './request'
import type { TaxonomyItem, TaxonomyRequest, TaxonomyType } from '@/types'

/**
 * 分类 / 榜单字典接口（8 个 = 2 类 × 4 操作）。
 *
 * 两类的行为完全一样，只有 URL 段不同（`/categories` vs `/rankings`），
 * 所以这里用 `type → 段名` 的映射消掉重复代码。后端的 `TaxonomyController`
 * 也确实是两组几乎相同的方法。
 *
 * 🔴 **参数类型是 `TaxonomyType`（只有 CATEGORY / RANKING），不是 `TargetType`。**
 * 书单不进字典表（它是采集的产物、数量上千，有自己的模块）。把参数收窄，
 * 是为了让 `listTaxonomy('BOOKLIST')` 这种调用**编译不过** ——
 * 否则它会静默请求 `/taxonomy/categories`，把分类列表当成书单列表用。
 *
 * 三条业务规则（后端实现，前端要能解释给用户看）：
 * 1. **编辑不允许改 `code`** —— 它是采集目标的身份，游标按它存
 * 2. **有任务引用则拒绝删除** —— 提示「还有 N 个采集任务在引用它」
 * 3. **只有游标则顺带清理** —— 删字典项时把对应游标一起删掉，不留孤儿
 */

/** 把字典类型映射成 URL 段名。只有两种取值，所以这个三元是穷尽的 */
function segmentOf(type: TaxonomyType): string {
  return type === 'RANKING' ? 'rankings' : 'categories'
}

/** 列表。`enabledOnly=true` 只返回启用项（新建任务时的下拉框用它） */
export function listTaxonomy(type: TaxonomyType, enabledOnly?: boolean) {
  return request<TaxonomyItem[]>({
    url: `/taxonomy/${segmentOf(type)}`,
    method: 'get',
    params: enabledOnly === undefined ? {} : { enabledOnly },
  })
}

/** 新增字典项 */
export function createTaxonomy(type: TaxonomyType, data: TaxonomyRequest) {
  return request<TaxonomyItem>({
    url: `/taxonomy/${segmentOf(type)}`,
    method: 'post',
    data,
  })
}

/** 编辑字典项。传了和原值不同的 `code` → 后端返 code=400 拒绝 */
export function updateTaxonomy(type: TaxonomyType, id: string, data: TaxonomyRequest) {
  return request<TaxonomyItem>({
    url: `/taxonomy/${segmentOf(type)}/${encodeURIComponent(id)}`,
    method: 'put',
    data,
  })
}

/** 删除字典项。返回里带 `cursorsRemoved`，说明顺带清掉了几条游标 */
export function deleteTaxonomy(type: TaxonomyType, id: string) {
  return request<{ id: string; cursorsRemoved: number }>({
    url: `/taxonomy/${segmentOf(type)}/${encodeURIComponent(id)}`,
    method: 'delete',
  })
}

/** 分类与榜单一起拉，用于「字典对照表」 */
export async function listAllTaxonomy(enabledOnly?: boolean) {
  const [categories, rankings] = await Promise.all([
    listTaxonomy('CATEGORY', enabledOnly),
    listTaxonomy('RANKING', enabledOnly),
  ])
  return { categories, rankings }
}
