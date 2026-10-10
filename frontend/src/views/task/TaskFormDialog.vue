<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createTask, updateTask } from '@/api/task'
import { listTaxonomy } from '@/api/taxonomy'
import type { CollectTask, TargetType, TaxonomyItem } from '@/types'

/**
 * 新建 / 编辑采集任务（一个弹窗两种模式）。
 *
 * <h3>为什么合并成一个弹窗</h3>
 * 两种模式有 4 个字段完全一样（`name` / `maxPages` / `remark` / `excludeCategories`），
 * 拆成两个组件就得把那 4 个 form-item 抄两遍，以后改校验规则要改两处。
 * 差别只有一处：**新建要选目标，编辑不能改目标**。
 *
 * <h3>编辑为什么不能改目标</h3>
 * 后端 `TaskService.update()` 的签名里**根本没有** `targetType` / `targetId`。
 * 原因是它们是游标的归属键 —— `collect_cursors` 的主键就是 `(targetType, targetId)`。
 * 改了目标等于换了一本账，而任务名和历史运行记录还指着旧目标。
 * 所以编辑模式里把目标**只读展示**出来，并写明「想换目标请删了重建」。
 *
 * <h3>新建时为什么要把「已有任务的目标」从下拉里剔掉</h3>
 * `collect_tasks` 上有 `(targetType, targetId)` 唯一索引，重复新建必定
 * 返回 code=405「目标已经有一个任务了」。让用户在一个必然失败的选项上
 * 浪费时间是很差的设计 —— 所以直接不列出来，并解释为什么。
 * 万一有并发（用户 A 建完、用户 B 的下拉还是旧的），后端那道 405 仍然兜底。
 */

const props = defineProps<{
  modelValue: boolean
  mode: 'create' | 'edit'
  /** 编辑模式下的任务 */
  task: CollectTask | null
  /** 已有任务列表。用来剔除「已经有任务的目标」 */
  existingTasks: CollectTask[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved', task: CollectTask): void
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)

const form = reactive<{
  name: string
  targetType: TargetType
  targetId: string
  maxPages?: number
  excludeCategories: string[]
  remark: string
}>({
  name: '',
  targetType: 'CATEGORY',
  targetId: '',
  maxPages: undefined,
  excludeCategories: [],
  remark: '',
})

const rules: FormRules = {
  targetType: [{ required: true, message: '请选择目标类型', trigger: 'change' }],
  targetId: [{ required: true, message: '请选择采集目标', trigger: 'change' }],
}

/** 字典。两种模式都要加载 —— 编辑模式也要能改「排除分类」 */
const categories = ref<TaxonomyItem[]>([])
const rankings = ref<TaxonomyItem[]>([])

/**
 * 分类字典的**全量**副本（含已停用项），只喂给「排除分类」。
 *
 * <h3>为什么不复用 categories</h3>
 * `categories` 拉的是 `enabledOnly=true`（停用的不该再建任务）。
 * 但「这个分类停用了」和「这个分类的书我不想采」是两件事 ——
 * 停用的分类照样应该能被排除，否则用户没法给一个已停用的分类兜底。
 * 再拉一次全量只有 188 条，代价可以忽略，换来的是不碰「可选目标」那条已有逻辑。
 */
const allCategories = ref<TaxonomyItem[]>([])

const dictOfCurrentType = computed(() =>
  form.targetType === 'RANKING' ? rankings.value : categories.value,
)

/** 已经被别的任务占用的目标 key，形如 `CATEGORY:300000` */
const usedTargetKeys = computed<Set<string>>(() => {
  const set = new Set<string>()
  props.existingTasks.forEach((t) => {
    if (t.targetType && t.targetId) {
      set.add(`${t.targetType}:${t.targetId}`)
    }
  })
  return set
})

/** 可选目标 = 启用的字典项 − 已有任务的目标 */
const availableTargets = computed(() => {
  return dictOfCurrentType.value
    .filter((t) => !usedTargetKeys.value.has(`${form.targetType}:${t.code}`))
    .map((t) => ({ value: t.code, label: `${t.name}（${t.code}）` }))
})

/** 当前类型的字典里有没有二级分类。没有就保持原来的平铺渲染 */
const hasSubCategories = computed(() => dictOfCurrentType.value.some((t) => t.parentCode))

/**
 * 可选目标按层级分组：先「一级分类」，再按上级逐个列出它的子分类。
 *
 * <h3>为什么必须分组，不能靠 sort 排</h3>
 * 二级分类是后加的，而 seed 是「只补缺失、不覆盖已有」—— 已有库上它们的 `sort`
 * 注定排在全部一级之后（22+），插不回各自上级的后面。所以层级只能靠 `parentCode` 表达。
 *
 * <h3>组顺序为什么用完整字典定，而不是用 avail</h3>
 * 某个一级分类一旦已经有任务，它自己会被剔出 avail；若拿 avail 定序，
 * 它的子分类组会莫名其妙跑到最后面。
 */
const availableGroups = computed(() => {
  const toOption = (t: TaxonomyItem) => ({ value: t.code, label: `${t.name}（${t.code}）` })
  const avail = dictOfCurrentType.value.filter(
    (t) => !usedTargetKeys.value.has(`${form.targetType}:${t.code}`),
  )

  const groups: { label: string; options: { value: string; label: string }[] }[] = []

  const tops = avail.filter((t) => !t.parentCode)
  if (tops.length) {
    groups.push({ label: '一级分类', options: tops.map(toOption) })
  }
  for (const parent of dictOfCurrentType.value) {
    if (parent.parentCode) {
      continue
    }
    const kids = avail.filter((t) => t.parentCode === parent.code)
    if (kids.length) {
      groups.push({ label: `${parent.name} 的子分类`, options: kids.map(toOption) })
    }
  }
  return groups
})

/** 有多少个目标因为「已经有任务」被剔掉了 —— 用来解释下拉为什么短 */
const hiddenCount = computed(
  () => dictOfCurrentType.value.length - availableTargets.value.length,
)

/**
 * 「排除分类」的候选项 —— 用**完整字典**分组，不做任何剔除。
 *
 * <h3>⚠️ 为什么不能复用 availableGroups</h3>
 * 那个剔掉了「已经有任务的目标」，因为一个目标只能建一个任务。
 * 但「某个分类已经建了任务」跟「能不能排除它」是两件毫不相干的事 ——
 * 恰恰是「采个人成长的任务」最需要能排除「个人成长下的女性成长」。
 * 复用会让用户眼睁睁看着自己最想排除的那一项从列表里消失。
 *
 * <h3>为什么只列分类，不列榜单</h3>
 * 排除是按书的 `categories` 标签匹配的，榜单不是分类标签，
 * 列进来会让人以为「排除飙升榜」能过滤掉榜单里的书 —— 那是不成立的。
 */
const excludeGroups = computed(() => {
  const toOption = (t: TaxonomyItem) => ({ value: t.code, label: `${t.name}（${t.code}）` })
  const all = allCategories.value

  const groups: { label: string; options: { value: string; label: string }[] }[] = []

  const tops = all.filter((t) => !t.parentCode)
  if (tops.length) {
    groups.push({ label: '一级分类（排除整块）', options: tops.map(toOption) })
  }
  for (const parent of tops) {
    const kids = all.filter((t) => t.parentCode === parent.code)
    if (kids.length) {
      groups.push({ label: `${parent.name} 的子分类`, options: kids.map(toOption) })
    }
  }
  return groups
})

/**
 * 已选但不在字典里的 code 个数。
 *
 * ⚠️ 字典覆盖不全（实测库里出现过的分类比字典多 3 个一级 / 53 个二级），
 * 所以允许手输 code。手输的值 `el-select` 会自己显示成标签，
 * 这里只是算个数量，好在提示里说清楚「其中 N 个字典里没有」。
 */
const customExcludeCount = computed(() => {
  const known = new Set<string>()
  allCategories.value.forEach((t) => known.add(t.code))
  return form.excludeCategories.filter((code) => !known.has(code)).length
})

async function loadDict(): Promise<void> {
  try {
    // enabledOnly=true：停用的字典项不该再新建任务
    // 第三项是全量分类，只给「排除分类」用（见 allCategories 的说明）
    const [cats, ranks, allCats] = await Promise.all([
      listTaxonomy('CATEGORY', true),
      listTaxonomy('RANKING', true),
      listTaxonomy('CATEGORY'),
    ])
    categories.value = cats
    rankings.value = ranks
    allCategories.value = allCats
  } catch {
    categories.value = []
    rankings.value = []
    allCategories.value = []
  }
}

function resetForm(): void {
  form.name = ''
  form.targetType = 'CATEGORY'
  form.targetId = ''
  form.maxPages = undefined
  form.excludeCategories = []
  form.remark = ''
}

function fillFrom(task: CollectTask | null): void {
  if (!task) {
    resetForm()
    return
  }
  form.name = task.name ?? ''
  form.targetType = task.targetType
  form.targetId = task.targetId
  form.maxPages = task.maxPages ?? undefined
  // ⚠️ 必须兜成数组：后端没配过排除时这个字段可能是 null / undefined，
  // 直接绑到 el-select multiple 上会让它渲染成非受控状态
  form.excludeCategories = task.excludeCategories ? [...task.excludeCategories] : []
  form.remark = task.remark ?? ''
}

/**
 * 切换目标类型时清空已选目标。
 *
 * 不清的话会出现：先选「分类 → 300000」，再切成「榜单」，
 * 目标还留着 "300000"，提交后后端拿 `ranking:300000` 建任务 ——
 * 建出来的任务永远采不到东西，而且不报错。
 */
function handleTargetTypeChange(): void {
  form.targetId = ''
}

watch(
  () => [props.modelValue, props.mode, props.task?.id] as const,
  ([visible]) => {
    if (!visible) {
      return
    }
    fillFrom(props.mode === 'edit' ? props.task : null)
    formRef.value?.clearValidate()
    // 两种模式都加载：编辑模式也要能改「排除分类」，而候选项来自字典
    void loadDict()
  },
  { immediate: true },
)

/** 编辑模式下的目标展示文案（只读） */
const targetText = computed(() => {
  if (!props.task) {
    return '-'
  }
  const typeName = props.task.targetType === 'RANKING' ? '榜单' : '分类'
  return `${typeName} · ${props.task.targetName ?? props.task.targetId}（${props.task.targetId}）`
})

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    if (props.mode === 'edit') {
      if (!props.task?.id) {
        return
      }
      // ⚠️ 只提交 name / maxPages / remark / excludeCategories —— 后端也不接受别的字段
      const updated = await updateTask(props.task.id, {
        name: form.name,
        maxPages: form.maxPages,
        remark: form.remark,
        // ⚠️ 必须显式传（哪怕空数组）—— 空数组 = 清空排除，undefined = 不改。
        // 「空就不传」的写法会让用户永远删不掉已配的排除项。
        excludeCategories: form.excludeCategories,
      })
      ElMessage.success('任务已保存')
      emit('saved', updated)
    } else {
      const target = dictOfCurrentType.value.find((t) => t.code === form.targetId)
      const created = await createTask({
        name: form.name,
        targetType: form.targetType,
        targetId: form.targetId,
        // 目标名快照：任务建完之后，即使字典项被删/改名，任务里也还知道自己在采什么
        targetName: target?.name,
        maxPages: form.maxPages,
        excludeCategories: form.excludeCategories,
        remark: form.remark,
      })
      ElMessage.success('任务已创建')
      emit('saved', created)
    }
    emit('update:modelValue', false)
  } catch {
    // 拦截器已经弹过后端的 message（405「目标已经有一个任务了」也走这里）
  } finally {
    saving.value = false
  }
}

function handleClose(): void {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="mode === 'create' ? '新建采集任务' : '编辑任务'"
    width="560px"
    top="8vh"
    destroy-on-close
    @update:model-value="handleClose"
  >
    <el-alert v-if="mode === 'create'" type="info" :closable="false" class="tip">
      <template #title>
        一个采集目标只能有一个任务（数据库上有唯一索引）。已经建过任务的目标不会出现在
        下面的下拉框里 —— 想改参数就编辑那个任务，不要建第二个。
      </template>
    </el-alert>
    <el-alert v-else type="warning" :closable="false" class="tip">
      <template #title>
        目标不可修改。它决定采集游标归谁（游标按「目标」存），改了等于换一本账，
        而历史运行记录还指着旧目标。想换目标请删掉这个任务重建。
      </template>
    </el-alert>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px" class="form">
      <el-form-item label="任务名">
        <el-input
          v-model="form.name"
          clearable
          placeholder="留空自动生成，如「文学-采集」"
        />
      </el-form-item>

      <!-- 新建：选目标 -->
      <template v-if="mode === 'create'">
        <el-form-item label="目标类型" prop="targetType">
          <el-radio-group v-model="form.targetType" @change="handleTargetTypeChange">
            <el-radio-button value="CATEGORY">分类</el-radio-button>
            <el-radio-button value="RANKING">榜单</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="采集目标" prop="targetId">
          <el-select v-model="form.targetId" clearable filterable placeholder="选择要采集的分类或榜单">
            <!-- 有二级分类时按层级分组；没有（如榜单）就平铺，与改造前完全一致 -->
            <template v-if="hasSubCategories">
              <el-option-group v-for="g in availableGroups" :key="g.label" :label="g.label">
                <el-option
                  v-for="opt in g.options"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-option-group>
            </template>
            <template v-else>
              <el-option
                v-for="opt in availableTargets"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </template>
          </el-select>
        </el-form-item>
        <div class="field-hint">
          可选 {{ availableTargets.length }} 个
          <template v-if="hiddenCount > 0">
            （另有 {{ hiddenCount }} 个已建过任务，不在此列出）
          </template>
        </div>
      </template>

      <!-- 编辑：目标只读 -->
      <el-form-item v-else label="采集目标">
        <span class="readonly-target">{{ targetText }}</span>
      </el-form-item>

      <el-form-item label="最大页数">
        <el-input-number
          v-model="form.maxPages"
          :min="0"
          :controls="false"
          placeholder="0 或不填 = 不限，一直翻到底"
        />
      </el-form-item>
      <div class="field-hint">
        留空或填 0 表示不限制，会一直翻到接口说没有下一页。想先试跑就填个 1~2。
      </div>

      <el-form-item label="排除分类">
        <el-select
          v-model="form.excludeCategories"
          multiple
          filterable
          allow-create
          default-first-option
          collapse-tags
          collapse-tags-tooltip
          :max-collapse-tags="3"
          placeholder="不选 = 不排除任何分类"
          class="exclude-select"
        >
          <el-option-group v-for="g in excludeGroups" :key="g.label" :label="g.label">
            <el-option
              v-for="opt in g.options"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-option-group>
        </el-select>
      </el-form-item>
      <div class="field-hint">
        命中的图书<b>不会写进图书库</b>。一本书通常同时挂 2~4 个分类标签，
        只要其中<b>任意一个</b>命中这里的 code，整本就被跳过 ——
        所以排除「女性成长 1000007」之后，一本同时属于「文学」和「女性成长」的书，
        在采「文学」时也会被跳过。
        <template v-if="customExcludeCount > 0">
          其中 {{ customExcludeCount }} 个不在字典里，将按 code 直接匹配。
        </template>
        字典里没有的分类可以直接输 code 回车。
      </div>

      <el-form-item label="备注">
        <el-input
          v-model="form.remark"
          type="textarea"
          :rows="3"
          resize="vertical"
          placeholder="可选"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">
        {{ mode === 'create' ? '创建' : '保存' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  margin-bottom: 16px;
}

.form :deep(.el-input-number) {
  width: 100%;
}

/* el-select 在 el-form-item 里默认就能撑满，这里显式写一遍是防止以后
   有人给 .form 加了 flex 布局把它挤成内容宽度 */
.exclude-select {
  width: 100%;
}

.field-hint {
  margin: -8px 0 16px 88px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.readonly-target {
  font-size: 13px;
  color: #606266;
}
</style>
