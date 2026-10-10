/**
 * 菜单配置。
 *
 * 侧边栏和面包屑都从这里读，**唯一真相来源** —— 不要在 `BasicLayout` 里
 * 另写一份菜单数组，否则加一个页面要改两个地方，迟早不一致。
 *
 * `icon` 是 `@element-plus/icons-vue` 的**组件名**（main.ts 里全局注册了）。
 * 名字写错不会报错，只会渲染出一个空位 —— 这是 Element Plus 图标全局注册的代价，
 * 所以改这里之后要肉眼确认一眼侧边栏。
 */

export interface MenuItem {
  /** 路由路径，必须与 router 里的 path 一致。有 children 时它只是分组标识，不可点击 */
  path: string
  /** 菜单与面包屑显示的名字 */
  title: string
  /** Element Plus 图标组件名 */
  icon: string
  /** 一句话说明这个页面干什么。页面外壳会显示它，S6 实现后可以删 */
  desc: string
  /**
   * 二级菜单。**只在有子项的一级菜单上出现**。
   *
   * 用「有 children 就渲染 el-sub-menu」这一个判据，而不是再加一个 `type` 字段 ——
   * 两个来源迟早会不一致（`type: 'group'` 但没有 children 会渲染出空折叠项）。
   */
  children?: MenuItem[]
}

/**
 * 10 个一级菜单（当前没有任何二级分组）。
 *
 * <h3>顺序 = 用户 2026-10-10 指定</h3>
 * 仪表盘 → 图书库 → 藏书阁 → 任务配置 → 分类配置 → 增量配置 →
 * 发布配置 → 发布记录 → 采集游标 → 请求审计。
 *
 * <h3>🔴 2026-10-10 的结构变更</h3>
 * 「发布配置」「发布记录」原本是「AI → 书籍解读」一个二级菜单。
 * 用户要求<b>去掉 AI 这个一级分组</b>，把两个页面拆成平级菜单。
 * 所以现在 {@link MENUS} 里<b>没有任何 children</b> ——
 * `AppSidebar` 里 `el-sub-menu` 那条渲染分支暂时用不到，但保留着
 * （以后要加二级菜单不用改布局）。
 *
 * <p>菜单标题也按同一次要求改了名（任务中心→任务配置、分类榜单→分类配置、
 * 定时刷新→增量配置、数字书架→藏宝阁→藏书阁、书籍解读→发布配置）。
 * ⚠️ 改名只动展示文案与 `desc`，<b>不动路由路径</b>（除了 AI 那两个，
 * 因为它们从二级升成一级，路径必须跟着语义走）。
 */
export const MENUS: MenuItem[] = [
  {
    path: '/dashboard',
    title: '仪表盘',
    icon: 'DataLine',
    desc: '4 个指标卡（图书总数 / 分类数 / 任务数 / 今日新增）+ 4 张图（分类分布 / 评分分布 / 出版年份 / 近 7 天采集量）',
  },
  {
    path: '/books',
    title: '图书库',
    icon: 'Reading',
    desc: '筛选栏 + 12 列表格 + 分页 + 详情抽屉（46 字段分组）+ 批量删除；书名 / 作者支持模糊搜索',
  },
  {
    path: '/shelf',
    title: '藏书阁',
    icon: 'Star',
    desc: '沉浸式浏览：按一级类目看藏书，10 列网格下滑加载；图书库里可添加 / 移除藏书',
  },
  {
    path: '/tasks',
    title: '任务配置',
    icon: 'List',
    desc: '任务列表 + 新建向导 + 详情（手动刷新进度）+ 启动 / 暂停 / 恢复 / 取消 / 重试',
  },
  {
    path: '/taxonomy',
    title: '分类配置',
    icon: 'Collection',
    desc: '21 个分类 + 7 个榜单的字典维护（新增 / 编辑 / 启停 / 删除）',
  },
  {
    path: '/refresh',
    title: '增量配置',
    icon: 'Timer',
    desc: '7 个榜单 + 期刊杂志的一键全量刷新：cron 定时 + 立即执行。不落游标、不建任务',
  },
  {
    path: '/publish/config',
    title: '发布配置',
    icon: 'Notebook',
    desc: '候选清单筛选与批量删除、生成配置（cron）、初始化候选、生成并发布到语雀',
  },
  {
    path: '/publish/record',
    title: '发布记录',
    icon: 'Tickets',
    desc: '每一次成功发布到语雀的记录，按发布时间倒序',
  },
  {
    path: '/cursors',
    title: '采集游标',
    icon: 'Aim',
    desc: '每个采集目标的断点位置（maxIndex），支持重置与指定起点',
  },
  {
    path: '/requests',
    title: '请求审计',
    icon: 'Document',
    desc: '每一次对微信读书接口的 HTTP 请求（状态码 / 耗时 / 总条数 / 是否还有下一页）',
  },
]

/** 按路径查菜单项。面包屑用它取标题。会递归进 children */
export function findMenu(path: string): MenuItem | undefined {
  for (const item of MENUS) {
    if (item.path === path) {
      return item
    }
    if (item.children) {
      const hit = item.children.find((child) => child.path === path)
      if (hit) {
        return hit
      }
    }
  }
  return undefined
}

/**
 * 查某个路径的**父级**菜单项。面包屑要显示「父 / 子」两段时靠它拿父级。
 *
 * <p>⚠️ 2026-10-10 拆掉 AI 分组之后，{@link MENUS} 里已经没有二级菜单，
 * 所以这个函数<b>目前恒返回 undefined</b>（面包屑只显示一段）。
 * 保留它是因为 `AppBreadcrumb` 依赖这个入口，删掉就要连带改布局；
 * 以后加回二级菜单时也不用再补。
 *
 * @returns 没有父级（一级菜单、或路径压根不在菜单里）时返回 undefined
 */
export function findParentMenu(path: string): MenuItem | undefined {
  return MENUS.find((item) => item.children?.some((child) => child.path === path))
}
