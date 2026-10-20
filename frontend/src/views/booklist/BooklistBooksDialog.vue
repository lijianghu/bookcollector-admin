<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { pageBooklistBooks } from '@/api/booklist'
import ProTable from '@/components/ProTable.vue'
import { dash, formatNumber, formatPublishDate, ratingToStars } from '@/utils/format'
import { booklistDisplayName, type Book, type Booklist } from '@/types'

/**
 * 书单详情弹窗：按书单 ID 分页展示这张书单里的书（2026-10-16 新增）。
 *
 * <h3>数据从哪来</h3>
 * `GET /api/booklists/{booklistId}/books` —— 后端匹配
 * `books.bookLists.bookListId`（书单引用数组）。也就是说这里展示的是
 * 「这本书被这张书单采到时写入的快照」：同一本书挂在多张书单下时，
 * 各弹窗看到的都是它自己那张书单的记录，互不影响。
 *
 * <h3>为什么没有排序 / 筛选工具栏</h3>
 * 用户要的是「点开书单看看里面有什么书」。排序后端定死（推荐值降序，
 * 与图书库列表默认一致），筛选有需要时去图书库页按书名/作者查 ——
 * 在这个弹窗里再复制一份筛选器，会把两处的口径越拉越开。
 *
 * <h3>「已采 / 声明」对不上是正常现象</h3>
 * 头部那行数字是后端书单表上的账：`bookCount`（书单声明的书籍数，
 * 来自同步书单时的接口响应）vs `bookCountCollected`（实际入库数）。
 * 书单被作者增删过书、或采集时命中了排除分类，两者就会不一致 ——
 * 这正是用户想在这里看到的信号，所以刻意不做「看起来齐」的处理。
 */

const props = defineProps<{
  modelValue: boolean
  /** 要查看的书单；null 时弹窗不渲染内容 */
  booklist: Booklist | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const rows = ref<Book[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

/** 弹窗标题里的书单展示名（没名称时回退 ID，与列表页口径一致） */
const title = computed(() => {
  if (!props.booklist) {
    return '书单详情'
  }
  return booklistDisplayName(props.booklist)
})

/** 打开即拉第一页；换一本书单也重新拉（reactive 依赖 booklistId） */
watch(
  () => [props.modelValue, props.booklist?.booklistId] as const,
  ([visible]) => {
    if (visible && props.booklist) {
      page.value = 1
      loadList()
    }
  },
  { immediate: true },
)

function loadList() {
  if (!props.booklist) {
    return
  }
  loading.value = true
  pageBooklistBooks(props.booklist.booklistId, page.value, size.value)
    .then((res) => {
      rows.value = res.list
      total.value = res.total
    })
    .catch(() => {
      // 拦截器已弹错误提示；这里把表清空，避免还显示着上一张书单的数据
      rows.value = []
      total.value = 0
    })
    .finally(() => {
      loading.value = false
    })
}

/**
 * 改每页条数时 ProTable 会**同步**先 emit `update:size` 再 emit `update:page(1)`
 * —— 事件顺序不保证，所以这里自己也算一次（同书单列表页的处理，见该页注释）。
 * 做法：顺手把两个 ref 都对齐到「最新意图」，让 loadList 用的页码永真。
 */
function handlePageChange(next: number) {
  page.value = next
  loadList()
}

function handleSizeChange(next: number) {
  size.value = next
  page.value = 1
  loadList()
}

function handleVisibleChange(visible: boolean) {
  emit('update:modelValue', visible)
}

/** 关闭时清掉数据：下次打开先看到空表 + loading，而不是上一张书单的残留 */
function handleClosed() {
  rows.value = []
  total.value = 0
  page.value = 1
}

/**
 * 「分类」列的兜底文案：优先顶层 {@link Book#category}（书单链路常带这个），
 * 没有时拼 {@link Book#categories} 的标题。
 *
 * <p>为什么在 script 里拼而不是模板里 `row.categories.map(...)`：
 * el-table slot 的 `row` 是 any，模板里的回调参数 `c` 会被推断成隐式 any，
 * noImplicitAny 下过不了类型检查（同书单列表页的 {@code categoryTooltip} 模式）。
 */
function categoryText(row: Book): string {
  if (row.category) {
    return row.category
  }
  const titles = (row.categories ?? []).map((c) => c.title).filter(Boolean)
  return titles.length > 0 ? titles.join('、') : '-'
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="860px"
    top="8vh"
    destroy-on-close
    @update:model-value="handleVisibleChange"
    @closed="handleClosed"
  >
    <template v-if="booklist">
      <div class="bl-meta">
        <span class="bl-meta-id text-mono">{{ booklist.booklistId }}</span>
        <span class="bl-meta-count">
          已采 <b>{{ dash(booklist.bookCountCollected) }}</b> / 声明
          <b>{{ dash(booklist.bookCount) }}</b> 本
        </span>
      </div>

      <ProTable
        :data="rows"
        :loading="loading"
        :total="total"
        :page="page"
        :size="size"
        :page-sizes="[20, 50, 100]"
        row-key="bookId"
        max-height="460px"
        empty-text="这张书单还没有采到书。采集任务跑完后这里就有内容了。"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <el-table-column label="书名" min-width="200">
          <template #default="{ row }">
            <div class="book-title">{{ dash(row.title) }}</div>
            <div class="book-id text-mono text-muted">{{ row.bookId }}</div>
          </template>
        </el-table-column>

        <el-table-column label="作者" min-width="110">
          <template #default="{ row }">{{ dash(row.author) }}</template>
        </el-table-column>

        <el-table-column label="推荐值" width="90" align="right">
          <template #default="{ row }">{{ ratingToStars(row.newRating) }}</template>
        </el-table-column>

        <el-table-column label="阅读人数" width="100" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.readingCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="分类" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'text-muted': categoryText(row) === '-' }">{{ categoryText(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="出版时间" width="110">
          <template #default="{ row }">{{ formatPublishDate(row.publishTime) }}</template>
        </el-table-column>
      </ProTable>
    </template>
  </el-dialog>
</template>

<style scoped>
.bl-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
}

.bl-meta-id {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.bl-meta-count {
  font-size: 13px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
}

.book-title {
  line-height: 1.4;
}

.book-id {
  font-size: 12px;
}

.num {
  font-variant-numeric: tabular-nums;
}
</style>
