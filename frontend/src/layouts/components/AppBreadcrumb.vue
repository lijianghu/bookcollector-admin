<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { findMenu, findParentMenu } from '@/config/menu'

/**
 * 面包屑。
 *
 * 两级或三级：一级菜单是「首页 / 当前页」，有二级菜单时是
 * 「首页 / 父级 / 当前页」。
 *
 * ⚠️ 路由表本身是**平的**（例如 `/publish/config` 是 BasicLayout 下的一个普通
 * 子路由，不是 `/publish` 的嵌套子路由）—— 所以「有没有父级」只能问菜单，
 * 不能靠 `route.matched` 推断。这也是当初把面包屑做成独立组件的原因。
 *
 * <p>2026-10-10 拆掉 AI 分组后，菜单里暂时没有二级项，所以只显示「首页 / 当前页」。
 */

const route = useRoute()
const current = computed(() => findMenu(route.path))
const parent = computed(() => findParentMenu(route.path))
</script>

<template>
  <el-breadcrumb separator="/">
    <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
    <!-- 有父级时插一段。父级只是分组、没有自己的页面，所以不给 to -->
    <el-breadcrumb-item v-if="parent">{{ parent.title }}</el-breadcrumb-item>
    <el-breadcrumb-item v-if="current && current.path !== '/dashboard'">
      {{ current.title }}
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>
