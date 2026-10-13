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
 * 7 个一级菜单，其中 3 个是可展开的分组。
 *
 * <h3>🔴 2026-10-12 的结构变更（用户指定）</h3>
 * <ol>
 *   <li><b>图书库</b>变成分组：`图书库` + `精选图书`（原「发布配置」页里的候选清单）；</li>
 *   <li><b>藏书阁</b>变成分组：`我的藏书` + `阅读清单`（新增的阅读计划）；</li>
 *   <li>新增<b>「配置」分组</b>：把 `分类配置` / `任务配置` / `增量配置` / `发布配置`
 *       收进去，**顺序就是用户点名的顺序**（分类 → 任务 → 增量 → 发布）。</li>
 * </ol>
 *
 * <h3>🔴 分组的 `path` 必须与它的子项不同</h3>
 * `AppSidebar` 用 `:index="item.path"` 做菜单项的唯一标识（`el-menu` 靠它高亮与展开）。
 * 若分组也写 `/books`，就和子项「图书库」撞了 index。所以分组用
 * `/books-group` / `/shelf-group` / `/config-group` 这类**纯标识路径** ——
 * 它们不对应任何真实路由，因为带 children 的分组本身**不可点击**。
 *
 * <p>⚠️ 改菜单标题时**必须同步改 `router/index.ts` 的 `meta.title`** ——
 * 后者驱动浏览器标签页，不同步会出现「侧边栏叫这个、标签页叫那个」。
 */
export const MENUS: MenuItem[] = [
  {
    path: '/dashboard',
    title: '仪表盘',
    icon: 'DataLine',
    desc: '4 个指标卡（图书总数 / 分类数 / 任务数 / 今日新增）+ 4 张图（分类分布 / 评分分布 / 出版年份 / 近 7 天采集量）',
  },
  {
    path: '/books-group',
    title: '图书库',
    icon: 'Reading',
    desc: '图书库与精选图书',
    children: [
      {
        path: '/books',
        title: '图书库',
        icon: 'Reading',
        desc: '筛选栏 + 12 列表格 + 分页 + 详情抽屉（46 字段分组）+ 批量删除；书名 / 作者支持模糊搜索',
      },
      {
        path: '/books/featured',
        title: '精选图书',
        icon: 'Medal',
        desc: 'AI 解读候选清单：状态 / 书名 / 作者 / 分类 / 推荐值 / 阅读人数 / 出版时间筛选，一键「重置精选」、勾选批量删除、重置为待解读',
      },
    ],
  },
  {
    path: '/shelf-group',
    title: '藏书阁',
    icon: 'Star',
    desc: '我的藏书与阅读清单',
    children: [
      {
        path: '/shelf',
        title: '我的藏书',
        icon: 'Collection',
        desc: '沉浸式浏览：按一级类目看藏书，8 列网格下滑加载；书名 / 作者模糊搜索',
      },
      {
        path: '/shelf/reading',
        title: '阅读清单',
        icon: 'Notebook',
        desc: '想读 / 在读 / 已读的阅读计划，按最近变更倒序；与「我的藏书」是两个独立维度',
      },
    ],
  },
  {
    path: '/config-group',
    title: '配置',
    icon: 'Setting',
    desc: '分类 / 任务 / 增量 / 发布 四项配置',
    children: [
      {
        path: '/taxonomy',
        title: '分类配置',
        icon: 'Menu',
        desc: '21 个分类 + 7 个榜单的字典维护（新增 / 编辑 / 启停 / 删除）',
      },
      {
        path: '/tasks',
        title: '任务配置',
        icon: 'List',
        desc: '任务列表 + 新建向导 + 详情（手动刷新进度）+ 启动 / 暂停 / 恢复 / 取消 / 重试',
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
        icon: 'Promotion',
        desc: '生成配置（cron）、候选池统计、生成并发布到语雀；候选清单与「重置精选」已移到「精选图书」',
      },
    ],
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

/**
 * 按路径查菜单项。面包屑用它取标题。会递归进一层 children。
 *
 * <p>⚠️ 一级菜单的 `path` 与子项**可能同名**（如分组「图书库」下也有「图书库」），
 * 但分组的 path 是 `/books-group` 这种带后缀的标识，所以不会误命中。
 */
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
 * <p>2026-10-12 起菜单里重新有了二级项（图书库 / 藏书阁 / 配置三个分组），
 * 所以面包屑会显示成「首页 / 图书库 / 精选图书」这样三段。
 *
 * @returns 没有父级（一级菜单、或路径压根不在菜单里）时返回 undefined
 */
export function findParentMenu(path: string): MenuItem | undefined {
  return MENUS.find((item) => item.children?.some((child) => child.path === path))
}
