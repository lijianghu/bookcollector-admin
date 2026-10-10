<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
        之后改图书库不会改写这里。新增记录由「发布配置」的「生成并发布」自动写入。
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
            <span v-if="row.sensitiveHitCount" class="num">{{ row.sensitiveHitCount }} 词</span>
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
</style>
