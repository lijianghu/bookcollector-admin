<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { pagePublishRecords } from '@/api/publish'
import ProTable from '@/components/ProTable.vue'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import type { PublishRecord } from '@/types'

/**
 * 发布记录（2026-10-10 新增）。
 *
 * <h3>它是什么</h3>
 * 每一次<b>成功发布到语雀</b>留下的流水，按发布时间倒序。
 * 后端在发布流程的最后一步顺带写入（见 `PublishServiceImpl.writePublishRecord`），
 * 这个页面只读。
 *
 * <h3>🔴 为什么与「候选清单」不是一回事</h3>
 * 候选清单里一本书只有一行，状态会被后续操作覆盖 —— 重新生成一次，
 * 上一次的语雀链接就没了。而这里要的是流水账：同一本书发几次就有几条。
 * 所以书名 / 作者 / 分类 / 推荐值都是**发布那一刻**的快照，
 * 图书库里的记录后来被改被删，都不会改写这里。
 *
 * <h3>⚠️ 历史数据要靠脚本补</h3>
 * 这个集合是本次新加的，所以「以前发过的那些」默认不在里面。
 * 用户自己跑 `tools/backfill_publish_records.py` 从 `book_interpretations`
 * 里把已发布的记录补录进来（默认 dry-run，加 `--apply` 才写库）。
 *
 * <h3>列宽</h3>
 * 1440 屏上表格可用宽约 1146px，且 `el-table` 的横向滚动条**默认隐藏** ——
 * 列宽总和超了就悄悄把右边的列裁到视口外。所以这里的固定宽列总和
 * （148+80+70+84+84 = 466）加上 min-width 列（200+130+150 = 480）留了余量。
 */
const loading = ref(false)
const rows = ref<PublishRecord[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

/**
 * 「合规替换」明细弹窗（2026-10-13 加）。
 *
 * <h3>为什么要加</h3>
 * 那一列原来只显示「N 词」，看不到**替换了什么** —— 想知道就得去语雀 / 本地 md 里翻，
 * 或者直连 Mongo 看 `book_interpretations.sensitiveHits`。
 *
 * <p>数据来自 `PublishRecord.sensitiveHits`（命中的**原词**列表）。
 * ⚠️ 2026-10-13 **之前**的记录没有这个字段（那时只存了个数）→ `null`，
 * 弹窗里如实说明「只存了个数」，不是坏了。
 */
const hitsVisible = ref(false)
const hitsRow = ref<PublishRecord | null>(null)

function openHits(row: PublishRecord): void {
  hitsRow.value = row
  hitsVisible.value = true
}

/** 弹窗里的词；历史记录（没有该字段）给空数组 */
const hitsWords = computed<string[]>(() => hitsRow.value?.sensitiveHits ?? [])

/** 弹窗标题：合规替换 ·《书名》 */
const hitsTitle = computed<string>(() => {
  const name = hitsRow.value?.title ?? hitsRow.value?.bookId
  return name ? `合规替换 ·《${name}》` : '合规替换'
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const data = await pagePublishRecords({ page: page.value, size: size.value })
    rows.value = data.list
    total.value = data.total

    // 页码越界纠正（同图书库）：数据变少后停在一个空页上，用户会以为「记录没了」
    const lastPage = Math.max(1, data.totalPages || 1)
    if (!correcting && data.list.length === 0 && page.value > lastPage) {
      correcting = true
      page.value = lastPage
      await load()
      correcting = false
    }
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 防止「页码越界纠正」反复触发 */
let correcting = false

function handlePageChange(next: number): void {
  page.value = next
  void load()
}

function handleSizeChange(next: number): void {
  size.value = next
  page.value = 1
  void load()
}

/** 推荐值 913 → 91.3% */
function ratingText(v?: number | null): string {
  return v === null || v === undefined ? '-' : `${(v / 10).toFixed(1)}%`
}

/** MANUAL / SCHEDULED → 人话 */
function triggerText(v?: string | null): string {
  if (v === 'MANUAL') {
    return '手动'
  }
  if (v === 'SCHEDULED') {
    return '定时'
  }
  return dash(v)
}

/** token 消耗：没数据时给 '-'，两个都空才合并显示 */
function tokenText(row: PublishRecord): string {
  const prompt = row.promptTokens
  const completion = row.completionTokens
  if ((prompt === null || prompt === undefined) && (completion === null || completion === undefined)) {
    return '-'
  }
  return `${formatNumber(prompt)} / ${formatNumber(completion)}`
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" class="intro">
      <template #title>
        每次成功发布到语雀留下一条记录，按发布时间倒序。书名 / 作者 / 分类是<b>发布那一刻</b>的快照，
        之后改图书库不会改写这里。新增记录由「发布配置」的「生成并发布」或「定时发布」写入。
      </template>
    </el-alert>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">发布记录</span>
          <span class="hint">共 {{ formatNumber(total) }} 条</span>
        </div>
      </template>

      <ProTable
        :data="rows"
        :loading="loading"
        :total="total"
        :page="page"
        :size="size"
        :page-sizes="[20, 50, 100]"
        row-key="id"
        empty-text="还没有发布记录。跑一次「生成并发布」就会有了；历史数据用 tools/backfill_publish_records.py 补录。"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #toolbar>
          <el-button :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <span class="toolbar-info">
            共 {{ formatNumber(total) }} 条 · 本页 {{ rows.length }} 条
          </span>
        </template>

        <el-table-column label="发布时间" width="148">
          <template #default="{ row }">
            <span class="num">{{ formatDateTime(row.publishedAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="书名" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="book-title">{{ row.title ?? dash(row.bookId) }}</div>
            <div class="book-author">{{ dash(row.author) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="分类" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ dash(row.category) }}</template>
        </el-table-column>

        <el-table-column label="推荐值" width="80" align="right">
          <template #default="{ row }">
            <span class="num">{{ ratingText(row.newRating) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="语雀" width="70" align="center">
          <template #default="{ row }">
            <el-link
              v-if="row.yuqueUrl"
              type="primary"
              :href="row.yuqueUrl"
              target="_blank"
              :underline="false"
            >
              打开
            </el-link>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="模型 / tokens" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ dash(row.model) }}</div>
            <div class="book-author num">{{ tokenText(row) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="合规替换" width="84" align="center">
          <template #default="{ row }">
            <!-- 点开看**替换了哪些词**（2026-10-13）；没有命中的行不可点 -->
            <el-link
              v-if="row.sensitiveHitCount"
              type="primary"
              :underline="false"
              @click="openHits(row)"
            >
              {{ row.sensitiveHitCount }} 词
            </el-link>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="触发方式" width="84" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.trigger === 'SCHEDULED' ? 'warning' : 'info'">
              {{ triggerText(row.trigger) }}
            </el-tag>
          </template>
        </el-table-column>
      </ProTable>
    </el-card>

    <!--
      合规替换明细：点那一列弹出。数据是本次命中的敏感词**原词**列表
      （顺序 = 替换时的扫描顺序；同一个词出现多次会列多次）。
      ⚠️ 2026-10-13 之前的记录没有列表、只有个数 —— 走下面的空态说明。
    -->
    <el-dialog v-model="hitsVisible" :title="hitsTitle" width="520px">
      <template v-if="hitsWords.length">
        <div class="hits-hint">共 {{ hitsWords.length }} 处命中，按替换时的扫描顺序：</div>
        <div class="hits-list">
          <el-tag
            v-for="(w, i) in hitsWords"
            :key="`${w}-${i}`"
            size="small"
            type="warning"
            effect="plain"
          >
            {{ w }}
          </el-tag>
        </div>
      </template>
      <el-empty
        v-else
        :image-size="72"
        :description="hitsRow?.sensitiveHitCount
          ? '这条记录只存了替换词个数，没存具体词（2026-10-13 起才记录）'
          : '没有命中敏感词'"
      />
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.intro :deep(.el-alert__title) {
  line-height: 1.7;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-title {
  font-weight: 600;
}

.hint {
  font-size: 12px;
  color: #909399;
}

.toolbar-info {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.book-title {
  font-weight: 600;
}

.book-author {
  font-size: 12px;
  color: #909399;
}

.num {
  font-variant-numeric: tabular-nums;
}

/* 「合规替换」弹窗 */
.hits-hint {
  margin-bottom: 10px;
  font-size: 12px;
  color: #909399;
}

.hits-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 320px;
  overflow: auto;
}
</style>
