/**
 * 书架视觉参数的**唯一来源**。
 *
 * <h3>为什么所有差异都由 `bookId` 派生</h3>
 * 书架要「排列整齐但不完全机械化」—— 每本书的宽窄、厚薄、高低、倾斜、书脊颜色
 * 都得有差别。但差别如果来自 `Math.random()`，每次刷新整排书都会换位置换颜色，
 * 看着像数据错乱（需求里也明确要求「每次刷新不会无意义地大幅改变已有书籍顺序」）。
 *
 * 所以这里用 **`bookId` 的哈希**派生：同一本书永远长同一个样子，
 * 数据顺序一变也只是重排，不会「换一本书」。
 *
 * <h3>为什么不用 crypto / 不用库</h3>
 * 只需要「稳定 + 分布够散」，不需要密码学强度。FNV-1a 十行、零依赖，
 * 而且对短字符串（bookId 是 "34615967" 这种）分布很好。
 */

/** FNV-1a 32 位哈希。返回无符号整数 */
export function hashString(input: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < input.length; i += 1) {
    h ^= input.charCodeAt(i)
    // 用 Math.imul 做 32 位乘法，避免 Number 精度把低位吃掉
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

/** 把一个种子映射到 [0, 1) */
function ratio(seed: string): number {
  return (hashString(seed) % 10000) / 10000
}

/** 把一个种子映射到 [min, max] 的整数 */
function between(seed: string, min: number, max: number): number {
  return Math.round(min + ratio(seed) * (max - min))
}

/** 一本书的视觉参数。单位都是 px / deg */
export interface BookVisual {
  /** 封面宽（不含书脊） */
  width: number
  /** 书高 */
  height: number
  /** 书脊（厚度）宽 */
  thickness: number
  /** 与相邻书的间距 */
  gap: number
  /** 绕 Y 轴的倾角。8~15° 之间最像「摆在架子上」 */
  tilt: number
  /** 色相 0~359 */
  hue: number
  /** 书脊主色 */
  spine: string
  /** 书脊暗部（用于左侧压边） */
  spineDark: string
  /** 书脊亮部（用于右侧高光） */
  spineLight: string
}

/**
 * 由 `bookId` 派生视觉参数。
 *
 * <p>每个属性用**不同的种子后缀**（`:w` / `:t` …），否则同一个哈希的不同位段会相关，
 * 出现「厚的书必然也宽」这种一眼假的规律。
 */
export function bookVisual(bookId?: string | null): BookVisual {
  const id = bookId && bookId.length > 0 ? bookId : 'unknown'

  const hue = between(`${id}:hue`, 0, 359)
  const sat = between(`${id}:sat`, 28, 46)
  const light = between(`${id}:lig`, 26, 40)

  return {
    width: between(`${id}:w`, 88, 106),
    height: between(`${id}:h`, 146, 176),
    thickness: between(`${id}:t`, 12, 20),
    gap: between(`${id}:g`, 10, 20),
    tilt: between(`${id}:k`, 8, 15),
    hue,
    spine: `hsl(${hue}, ${sat}%, ${light}%)`,
    spineDark: `hsl(${hue}, ${sat}%, ${Math.max(12, light - 14)}%)`,
    spineLight: `hsl(${hue}, ${Math.min(72, sat + 14)}%, ${light + 16}%)`,
  }
}

/**
 * 封面加载失败时的**生成式封面**样式（背景渐变 + 描边）。
 *
 * <p>实测微信读书的封面 CDN 无防盗链、库里的 `cover` 也 100% 非空，
 * 但「非空」不等于「一定能加载」—— 断网、CDN 抖动、老书下架都会让 `<img>` 报错。
 * 那时不能留一块空白，得有一张看得出「这是哪本书」的假封面。
 */
export function fallbackCoverStyle(bookId?: string | null): Record<string, string> {
  const v = bookVisual(bookId)
  return {
    background:
      `linear-gradient(158deg, hsl(${v.hue}, 34%, 40%) 0%, ` +
      `hsl(${v.hue}, 38%, 24%) 58%, hsl(${v.hue}, 44%, 16%) 100%)`,
    borderColor: `hsl(${v.hue}, 32%, 54%)`,
  }
}
