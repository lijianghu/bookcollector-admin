<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ShelfBook } from '@/types'
import { bookVisual, fallbackCoverStyle } from '@/utils/shelfVisual'

/**
 * 藏书阁网格里的一本书（2026-10-10 网格改版新增）。
 *
 * <h3>2026-10-10 二次改版：从「平面卡片」改成真正的 3D 实体书</h3>
 * 上一版只有「封面 + 左侧一条渐变暗带（假书脊）+ box-shadow」——
 * 整墙看过去仍是一堆贴在木板上的图片。这次用 CSS 3D 把它做成一本书：
 *
 * <pre>
 * .card
 *   ├ .stage   透视容器（perspective / perspective-origin 略高于中心 → 看得到顶面）
 *   │   ├ ::before   环境遮蔽 + 接触阴影（平铺，不在 3D 上下文里）
 *   │   ├ ::after    木质层板的一段（左右各探进列间距，相邻卡片连成一条通板）
 *   │   └ .book  transform-style: preserve-3d，绕 Y 轴偏一个角 → 书脊侧露出来
 *   │        ├ .cover  前面板  translateZ(+thick/2)   ← 真实封面
 *   │        ├ .spine  左侧面  rotateY(-90deg) → 书脊（厚度）+ 竖排书名
 *   │        └ .top    上切面  rotateX(-90deg) → 顶部厚度（书口）
 *   ├ .meta    书名 / 作者
 *   └ ::after  抽屉面（左右各探出 6px，一整排连成一条柜体 —— 这就是「阁」）
 * </pre>
 *
 * <p>⚠️ 书脊那面用的是 **-90°** 而不是 +90°：`backface-visibility` 默认 visible，
 * 转到背面照样渲染但**内容是镜像的**。+90° 时镜头看到的正好是它的背面，
 * 书脊上的书名会左右反过来。理由与推导写在 `.spine` 的注释里。
 *
 * 三个面拼成一个「前面 + 左面 + 顶面」的半盒（背面/右面/底面看不见，不建），
 * 绕 Y 轴转一点点就能同时看到封面与书脊 —— 这就是「厚度感」的全部来源。
 *
 * <h3>为什么参数仍由 bookId 派生</h3>
 * 见 `utils/shelfVisual.ts`：宽窄厚薄都必须稳定，用 `Math.random()` 会让每次刷新
 * 整排书换样子。所以厚度继续取 `bookVisual().thickness`，只把偏角做了重映射（见下）。
 */
const props = defineProps<{
  book: ShelfBook
}>()

const emit = defineEmits<{
  (e: 'select', book: ShelfBook): void
}>()

const label = computed(() => props.book.title || '未命名')

/**
 * 推荐值角标文案：0~1000 → 百分比（`913` → `91.3%`）。
 *
 * <p>2026-10-12 统一口径 —— 与图书库列表 / 图书库详情抽屉 / 藏书阁抽屉一致。
 * 在这之前角标直接渲染原始值（显示 `913`），是**同一页里唯一没换算的地方**。
 *
 * <p>⚠️ 实测库里 `newRating` 全部 > 0（`shelf_books` 4401/4401），所以这里**不需要**
 * 像抽屉那样把 0 当作「无评分」处理；空值仍由模板的 `v-if` 挡掉。
 */
const ratingText = computed(() => {
  const r = props.book.newRating
  return r === null || r === undefined ? '' : `${(r / 10).toFixed(1)}%`
})

/** 封面加载失败时的生成式封面（颜色与 bookId 绑定，同一本书永远同一个配色） */
const generatedStyle = computed(() => fallbackCoverStyle(props.book.bookId))

/**
 * 封面是否已加载失败。库里的 `cover` 100% 非空，但「有 URL」不等于「加载得出来」——
 * 断网 / CDN 抖动 / 老书下架都会让 `<img>` 报错。那时换成生成式封面，不能留白块。
 */
const coverFailed = ref(false)

/** 由 bookId 派生的视觉参数（厚度 / 书脊配色），同一本书永远同一副样子 */
const visual = computed(() => bookVisual(props.book.bookId))

/**
 * 喂给 CSS 的 3D 变量。
 *
 * <p>⚠️ 偏角做了**重映射**：`shelfVisual.tilt` 是当年给「横向书架」定的 8~15°
 * （那时只要一点点倾斜就够）。网格里封面正对镜头，8° 时 12px 的厚度
 * 只投影出 `12 * sin8° ≈ 1.7px` —— 根本看不出书脊。
 *
 * <p>2026-10-10 二次抬高：书脊上要放书名，**静态**就得给侧面留出宽度，
 * 否则那行字会被压成一条糊线。所以从 `+7°`（15~22°）抬到 **`+12°`（20~27°）**：
 * 最薄的书侧面也有 `12 * sin20° ≈ 4px`，厚的能到 `20 * sin27° ≈ 9px`。
 * 仍由 bookId 派生，所以每本略有差别、刷新不跳。
 */
const bookStyle = computed<Record<string, string>>(() => ({
  '--thick': `${visual.value.thickness}px`,
  '--tilt': `${visual.value.tilt + 12}deg`,
  '--spine': visual.value.spine,
  '--spine-dark': visual.value.spineDark,
  '--spine-light': visual.value.spineLight,
}))
</script>

<template>
  <article
    class="card"
    :title="`${label}${book.author ? ' · ' + book.author : ''}`"
    @click="emit('select', book)"
  >
    <!-- 3D 舞台：透视 + 落影 + 层板 -->
    <div class="stage">
      <div class="book" :style="bookStyle">
        <!-- 前面板：真实封面 -->
        <div class="cover">
          <img
            v-if="book.cover && !coverFailed"
            :src="book.cover"
            :alt="label"
            loading="lazy"
            decoding="async"
            draggable="false"
            @error="coverFailed = true"
          />
          <div v-else class="generated" :style="generatedStyle">
            <span class="generated-title">{{ label }}</span>
            <span v-if="book.author" class="generated-author">{{ book.author }}</span>
          </div>

          <!-- 封面左缘的「书脊转折」暗带：让封面不是一块硬邦邦的平板 -->
          <span class="hinge" aria-hidden="true" />
          <span class="sheen" aria-hidden="true" />

          <!-- 推荐值角标（0~1000 → 百分比，见 script 里的 ratingText） -->
          <span v-if="ratingText" class="rating">{{ ratingText }}</span>
        </div>

        <!-- 左侧面：书脊（厚度）+ 书脊上的书名 -->
        <span class="spine" aria-hidden="true">
          <span class="spine-title">{{ label }}</span>
        </span>
        <!-- 上切面：顶部厚度（书口） -->
        <span class="top" aria-hidden="true" />
      </div>
    </div>

    <div class="meta">
      <p class="title">{{ label }}</p>
      <p class="author">{{ book.author || '佚名' }}</p>
    </div>
  </article>
</template>

<style scoped>
.card {
  display: flex;
  flex-direction: column;
  /* 层板占掉一点，把书名往下推一点，别贴着木板 */
  gap: 15px;
  cursor: pointer;
  /* 抽屉面（::after）要挂在这一格的底边上 */
  position: relative;
}

/*
 * ================= 「阁」的那一层：抽屉面 =================
 *
 * 每一格的底部挂一段抽屉面板。相邻卡片的这一段左右各探出 6px（= 列间距 12px 的一半），
 * 首尾正好相接 —— 于是一整排连成一条**柜体**，上面 8 个抽屉、每个一个拉手。
 * 这是「藏书阁」的「阁」：不只是一排层板，底下还有一排能拉开的抽屉。
 *
 * 位置：`bottom: -15px` 落在网格的行间距里（行间距 30px），不会压到下一排的书。
 * ⚠️ 行间距与这里的 15px 是配套的，改一个必须改另一个（在 `views/shelf/index.vue` 的 `.grid`）。
 *
 * 拉手是用**背景图层**画的（居中一小段深色凹槽），不额外加 DOM ——
 * 这样「调整一下」就真的只是调整样式，不用动模板结构。
 */
.card::after {
  content: '';
  position: absolute;
  left: -6px;
  right: -6px;
  bottom: -15px;
  height: 15px;
  border-radius: 0 0 3px 3px;
  background:
    linear-gradient(180deg, rgba(0, 0, 0, 0.66) 0%, rgba(0, 0, 0, 0.3) 100%) no-repeat 50% 7px /
      38px 4px,
    linear-gradient(180deg, #7a5632 0%, #5c3f22 26%, #432c16 68%, #2a1a0c 100%);
  box-shadow:
    inset 0 1px 0 rgba(255, 220, 168, 0.5),
    inset 0 -1px 0 rgba(0, 0, 0, 0.6),
    0 7px 12px -5px rgba(0, 0, 0, 0.9);
}

/* ================= 3D 舞台 ================= */
.stage {
  position: relative;
  /* 每本书自己的灭点 —— 网格里不能共用一个，否则同一行两端的书会歪得不像话 */
  perspective: 1100px;
  /* 灭点略高于中心：等价于「从斜上方俯视」，上切面才露得出来 */
  perspective-origin: 50% 32%;
}

/*
 * 落影（两层）：
 *   1. 接触阴影 —— 紧贴书脚、短而深，这是「书压在木板上」的关键一笔
 *   2. 环境遮蔽 —— 整块柔光，把书从深木背景里「托」出来
 * 平铺在 3D 上下文之外（.stage 没有 preserve-3d），所以它不会跟着书一起转。
 */
.stage::before {
  content: '';
  position: absolute;
  inset: 6% 3% -7px 3%;
  z-index: 0;
  filter: blur(6px);
  background:
    radial-gradient(58% 9% at 45% 97%, rgba(0, 0, 0, 0.78) 0%, rgba(0, 0, 0, 0) 80%),
    radial-gradient(76% 72% at 43% 58%, rgba(0, 0, 0, 0.5) 0%, rgba(0, 0, 0, 0) 72%);
}

/*
 * 木质层板的一段。左右各探进列间距（12px / 2 = 6px，这里给 7px），
 * 于是同一行的卡片首尾相接 —— 视觉上连成一条通板，而不是一格一块小板。
 * 顶部一道受光高光、底部一道落影，做出「板有厚度」的观感。
 */
.stage::after {
  content: '';
  position: absolute;
  left: -7px;
  right: -7px;
  bottom: -7px;
  height: 7px;
  border-radius: 1px 1px 2px 2px;
  z-index: 0;
  background: linear-gradient(180deg, #6d4c2d 0%, #4b3218 52%, #291a0c 100%);
  box-shadow:
    inset 0 1px 0 rgba(255, 214, 158, 0.34),
    inset 0 -1px 0 rgba(0, 0, 0, 0.55),
    0 5px 9px -4px rgba(0, 0, 0, 0.85);
}

/* ================= 书本体 ================= */
.book {
  position: relative;
  z-index: 1;
  transform-style: preserve-3d;
  /* 静态就带一点偏角：封面 + 书脊同时可见 */
  transform: rotateX(1.5deg) rotateY(var(--tilt));
  transition: transform 0.32s cubic-bezier(0.22, 0.72, 0.3, 1);
}

/*
 * 悬停：向前抽出 + 明显转过来。
 * 偏角再 +16°（→ 36~43°），书脊的横向投影从 `t·sin20° ≈ 0.34t` 拉到
 * `t·sin40° ≈ 0.64t` —— 书脊那行书名只有到这一步才勉强读得出来。
 * 同时封面横向收窄（cos 变小），所以看起来是「书被抽出来转了一下」，
 * 而不是单纯放大。
 */
.card:hover .book {
  transform: translateZ(34px) rotateX(1.5deg) rotateY(calc(var(--tilt) + 16deg));
}

/* ---------- 前面板（封面） ---------- */
.cover {
  position: relative;
  width: 100%;
  /* 阅读平台封面基本是 3:4，固定比例让网格对齐 */
  aspect-ratio: 3 / 4;
  border-radius: 2px 5px 5px 2px;
  overflow: hidden;
  background: #221a13;
  /* 往前推半个厚度 —— 书脊面 / 上切面都从这条边接出去 */
  transform: translateZ(calc(var(--thick) / 2));
  box-shadow:
    inset 1px 0 0 rgba(255, 236, 205, 0.1),
    inset -1px 0 0 rgba(0, 0, 0, 0.4),
    inset 0 1px 0 rgba(255, 236, 205, 0.14),
    inset 0 -1px 0 rgba(0, 0, 0, 0.45);
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 封面左缘转折暗带（原来那条假书脊，现在只做「封面弯进书脊」的过渡） */
.hinge {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 10px;
  background: linear-gradient(
    90deg,
    rgba(0, 0, 0, 0.46) 0%,
    rgba(0, 0, 0, 0.16) 58%,
    rgba(255, 255, 255, 0.05) 100%
  );
  pointer-events: none;
}

/* 玻璃高光：平时几乎看不见，悬停亮起 */
.sheen {
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.2s ease;
  background: linear-gradient(112deg, rgba(255, 255, 255, 0.24) 0%, rgba(255, 255, 255, 0) 46%);
}

.card:hover .sheen {
  opacity: 1;
}

/* ---------- 左侧面：书脊（厚度）+ 书脊上的书名 ---------- */
.spine {
  position: absolute;
  left: 0;
  top: 0;
  width: var(--thick);
  height: 100%;
  overflow: hidden;
  pointer-events: none;
  transform-origin: left center;
  /*
   * 立起来：绕左缘转 **-90°**（不是 +90°），再整体后移半个厚度。
   *
   * 🔴 为什么必须是 -90°：`backface-visibility` 默认 visible —— 转到背面照样渲染，
   *    但**内容是镜像的**。用 +90° 时这面墙的法线朝 +x（指向书的内侧），
   *    镜头看到的是它的**背面** → 书脊上的书名会左右反过来。
   *    改 -90° 后法线朝 -x（朝外），正对镜头，字才是正的。
   *    两种写法的占地完全一样（局部 x 都映射到 z ∈ [-t/2, +t/2]），只是贴图朝向相反。
   */
  transform: translateZ(calc(var(--thick) * -0.5)) rotateY(-90deg);
  /*
   * 横向着色 = 书脊的圆背：两端压暗、中段受光。
   * ⚠️ 局部 x 的 0% 落在**屏幕左侧**、100% 靠近封面，所以这是一条
   *    「左暗 → 亮 → 右侧微暗（与封面交界处的折痕）」的柱面。
   * 纵向着色 = 顶部受光、底部压暗。
   */
  background:
    linear-gradient(
      90deg,
      rgba(0, 0, 0, 0.52) 0%,
      rgba(0, 0, 0, 0.06) 30%,
      rgba(255, 255, 255, 0.1) 58%,
      rgba(0, 0, 0, 0.3) 100%
    ),
    linear-gradient(180deg, var(--spine-light) 0%, var(--spine) 48%, var(--spine-dark) 100%);
  border-radius: 1px 0 0 1px;
}

/*
 * 书脊上的书名。**竖排** —— 侧面只有 12~20px 宽，横排一个字都塞不下。
 * `writing-mode: vertical-rl` 让汉字逐字正立往下排（拉丁字母会转 90°）。
 * 太长就按真实书脊的做法截断加省略号，不缩字号（缩了就彻底看不见）。
 */
.spine-title {
  position: absolute;
  left: 0;
  right: 0;
  top: 9px;
  bottom: 9px;
  writing-mode: vertical-rl;
  text-align: center;
  font-size: 10px;
  line-height: 1;
  letter-spacing: 0.5px;
  color: rgba(255, 246, 232, 0.92);
  text-shadow: 0 1px 1px rgba(0, 0, 0, 0.72);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 上切面：顶部厚度（书口） ---------- */
.top {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: var(--thick);
  pointer-events: none;
  transform-origin: center top;
  /* 先绕上缘翻下去（+y 转到 -z），再整体前移半个厚度 */
  transform: translateZ(calc(var(--thick) / 2)) rotateX(-90deg);
  /* 奶白纸色：一眼就读得出「这是书的厚度」，比木色或封面色都清楚 */
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.12) 0%, rgba(0, 0, 0, 0.26) 100%),
    linear-gradient(90deg, #c8b18c 0%, #e8d9bb 44%, #b49c76 100%);
  border-radius: 1px 1px 0 0;
}

/* ---------- 生成式封面 ---------- */
.generated {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  border: 1px solid;
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 7px;
}

.generated-title {
  font-size: 14px;
  line-height: 1.35;
  font-weight: 600;
  color: rgba(255, 248, 238, 0.95);
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.6);
  display: -webkit-box;
  -webkit-line-clamp: 5;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.generated-author {
  font-size: 11px;
  color: rgba(255, 240, 220, 0.62);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 推荐值角标 */
.rating {
  position: absolute;
  right: 6px;
  top: 6px;
  padding: 1px 6px;
  border-radius: 9px;
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #2a1a0c;
  background: linear-gradient(180deg, #e8c07e 0%, #cb9c58 100%);
  box-shadow: 0 2px 6px -2px rgba(0, 0, 0, 0.7);
}

/* ---------- 书名 / 作者 ---------- */
.meta {
  padding: 0 2px;
}

.title {
  margin: 0;
  font-size: 13px;
  line-height: 1.4;
  color: #ecdcc6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.author {
  margin: 3px 0 0;
  font-size: 11.5px;
  color: rgba(233, 220, 201, 0.48);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 尊重系统的「减少动态效果」：去掉抽出与透视变化，静态仍保留厚度与阴影 */
@media (prefers-reduced-motion: reduce) {
  .book {
    transition: none;
  }
  .card:hover .book {
    transform: rotateX(1.5deg) rotateY(var(--tilt));
  }
  .sheen {
    transition: none;
  }
}
</style>
