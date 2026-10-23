# taxonomy 模块

> `com.bookcollector.taxonomy` · 采集目标字典。

## 职责

维护分类 / 榜单字典（一级、二级、榜单）；决定新建任务下拉框能选什么；`targetType + targetId` 的名称来源。
一级与二级都是合法采集目标；上游对每个 ID 各自封顶 500 本。

## 关键类

| 类 | 职责 |
|---|---|
| `TaxonomyController` | 分类 / 榜单各 4 个端点（共 8 个），按 `type` 分派 |
| `TaxonomyService(Impl)` | 列表 / 新增 / 编辑 / 删除 |
| `TaxonomyRepository` | 按 `type` 查、`type + code` 查、`maxSort` |
| `TaxonomyItem` | `type` / `code` / `name` / `parentCode` / `enabled` / `sort` / `remark` |

## 数据

`taxonomy_config`：`uk_type_code (type, code)` 唯一（seed 幂等的依赖）、`idx_sort`。

## 接口

| 方法 | 路由 | 说明 |
|---|---|---|
| GET | `/api/taxonomy/categories` | 列表（`enabledOnly=true` 用于新建任务下拉） |
| POST | `/api/taxonomy/categories` | 新增 |
| PUT | `/api/taxonomy/categories/{id}` | 编辑 |
| DELETE | `/api/taxonomy/categories/{id}` | 删除（有任务引用则拒绝；只有游标则顺带清） |
| 同上 | `/api/taxonomy/rankings` | 榜单一套 |

## 不变量

1. `uk_type_code` 唯一；seed 只补缺失、不覆盖已有。
2. `code` / `targetType` / `targetId` 不可改（DTO 字段缺失）。
3. 删除前查 `collect_tasks` 引用；有引用拒绝，只有游标则清掉。
4. `parentCode` 为空 = 一级；非空 = 二级。
5. 分类 `code` 是 String，`books.categories[].categoryId` 是 int，关联时显式转换。
