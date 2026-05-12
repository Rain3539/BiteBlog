<template>
  <el-container class="app-layout">
    <!-- 顶部导航 -->
    <el-header class="app-header">
      <div class="header-left">
        <h2 class="logo" @click="$router.push('/')">BiteBlog</h2>
      </div>
      <div class="header-nav">
        <router-link to="/" class="nav-link" :class="{ active: route.path === '/' }">首页</router-link>
        <router-link to="/discover" class="nav-link" :class="{ active: route.path.startsWith('/discover') }">发现</router-link>
        <router-link to="/nearby" class="nav-link" :class="{ active: route.path.startsWith('/nearby') }">附近</router-link>
      </div>
      <div class="header-right">
        <el-button type="primary" :icon="Plus" @click="$router.push('/publish')">发布</el-button>
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
import { ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, Search, Plus } from '@element-plus/icons-vue'
import { useUserStore } from '../../stores/user'
import SearchOverlay from '../SearchOverlay.vue'
import { logout, getToken } from '../../utils/auth'
import { getNotifyUnreadCount } from '../../api/notify'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const unreadCount = ref(0)
const showSearch = ref(false)

async function refreshNotifyBadge() {
  if (!getToken()) {
    unreadCount.value = 0
    return
  }
  try {
    const res = await getNotifyUnreadCount()
    unreadCount.value = Number(res.data?.unreadCount ?? 0)
  } catch {
    unreadCount.value = 0
  }
}

onMounted(() => {
  refreshNotifyBadge()
})

watch(
  () => route.fullPath,
  (newPath, oldPath) => {
    // 离开通知页时刷新（已读后角标归零）；其他路由切换也顺带刷新
    refreshNotifyBadge()
  }
)

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
  gap: 8px;
}

.nav-link {
  text-decoration: none;
  color: #606266;
  font-size: 14px;
  padding: 8px 16px;
  border-radius: 6px;
  transition: color 0.2s, background 0.2s;
}

.nav-link:hover {
  color: #409eff;
  background: #ecf5ff;
}

.nav-link.active {
  color: #409eff;
  font-weight: 600;
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
