<template>
  <el-container class="app-layout">
    <!-- 顶部导航 -->
    <el-header class="app-header">
      <div class="header-left">
        <h2 class="logo" @click="$router.push('/')">BiteBlog</h2>
      </div>
      <div class="header-nav">
        <el-menu mode="horizontal" :default-active="activeMenu" router>
          <el-menu-item index="/">首页</el-menu-item>
          <el-menu-item index="/discover">发现</el-menu-item>
          <el-menu-item index="/nearby">附近</el-menu-item>
          <el-menu-item index="/publish">发布</el-menu-item>
        </el-menu>
      </div>
      <div class="header-right">
        <el-button :icon="Search" circle @click="showSearch = true" />
        <el-badge :value="unreadCount" :hidden="unreadCount === 0">
          <el-button :icon="Bell" circle @click="$router.push('/notify')" />
        </el-badge>
        <el-dropdown @command="handleCommand">
          <el-avatar :src="userStore.userInfo?.avatar" size="small">
            {{ userStore.userInfo?.username?.charAt(0) || 'U' }}
          </el-avatar>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人主页</el-dropdown-item>
              <el-dropdown-item command="admin">管理后台</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <!-- 内容区 -->
    <el-main class="app-main">
      <router-view />
    </el-main>

    <!-- 搜索浮层 -->
    <SearchOverlay v-if="showSearch" @close="showSearch = false" />
  </el-container>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, Search } from '@element-plus/icons-vue'
import { useUserStore } from '../../stores/user'
import SearchOverlay from '../SearchOverlay.vue'
import { logout } from '../../utils/auth'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const unreadCount = ref(0)
const showSearch = ref(false)

const activeMenu = computed(() => {
  if (route.path.startsWith('/discover')) return '/discover'
  if (route.path.startsWith('/nearby')) return '/nearby'
  if (route.path.startsWith('/publish')) return '/publish'
  return '/'
})

function handleCommand(cmd) {
  if (cmd === 'profile') {
    router.push(`/profile/${userStore.userInfo?.userId}`)
  } else if (cmd === 'admin') {
    router.push('/admin/dashboard')
  } else if (cmd === 'logout') {
    logout()
  }
}
</script>

<style scoped>
.app-layout {
  height: 100vh;
}
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 20px;
}
.logo {
  margin: 0;
  cursor: pointer;
  color: #409eff;
}
.header-nav {
  flex: 1;
  display: flex;
  justify-content: center;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.app-main {
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
}
</style>
