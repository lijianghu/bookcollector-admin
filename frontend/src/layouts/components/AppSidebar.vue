<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useAppStore } from '@/store/app'
import { MENUS } from '@/config/menu'

/**
 * 侧边栏。
 *
 * 菜单数据来自 `@/config/menu.ts`（唯一真相来源），这里只负责渲染。
 *
 * `el-menu` 开 `router` 模式 + `:index="item.path"` 之后，点击菜单项会
 * 直接 `router.push(index)` —— 不需要自己写 @select 回调。
 * `:default-active="route.path"` 让「刷新后高亮」也对得上。
 */

const route = useRoute()
const appStore = useAppStore()
</script>

<template>
  <div class="sidebar">
    <div class="logo" :class="{ collapsed: appStore.sidebarCollapsed }">
      <el-icon class="logo-icon"><Notebook /></el-icon>
      <span v-show="!appStore.sidebarCollapsed" class="logo-text">图书采集</span>
    </div>

    <el-scrollbar class="menu-scroll">
      <el-menu
        :default-active="route.path"
        :collapse="appStore.sidebarCollapsed"
        :collapse-transition="false"
        router
        class="menu"
      >
        <!--
          有 children 的一级菜单渲染成可折叠分组（如「AI」），其余仍是普通菜单项。
          判据只有「有没有 children」这一个 —— 见 menu.ts 的 MenuItem 注释。
          折叠态下 el-sub-menu 会退化成图标 + 悬浮弹出，不需要额外处理。
        -->
        <template v-for="item in MENUS" :key="item.path">
          <el-sub-menu v-if="item.children && item.children.length" :index="item.path">
            <template #title>
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.title }}</span>
            </template>
            <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path">
              <el-icon><component :is="child.icon" /></el-icon>
              <template #title>{{ child.title }}</template>
            </el-menu-item>
          </el-sub-menu>

          <el-menu-item v-else :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.title }}</template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-scrollbar>

    <div v-show="!appStore.sidebarCollapsed" class="sidebar-footer">
      <span class="version">v1.0.0 · 本地单机</span>
    </div>
  </div>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #ffffff;
  border-right: 1px solid #e4e7ed;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 56px;
  padding: 0 18px;
  border-bottom: 1px solid #e4e7ed;
  overflow: hidden;
  white-space: nowrap;
}
.logo.collapsed {
  justify-content: center;
  padding: 0;
}
.logo-icon {
  font-size: 20px;
  color: #409eff;
  flex-shrink: 0;
}
.logo-text {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.menu-scroll {
  flex: 1;
  min-height: 0;
}

/* el-menu 自带 border-right，会和 .sidebar 的边框叠成两条 */
.menu {
  border-right: none;
}

.sidebar-footer {
  padding: 10px 18px;
  border-top: 1px solid #e4e7ed;
}
.version {
  font-size: 12px;
  color: #a8abb2;
}
</style>
