<template>
  <div class="profile-page" v-loading="loading">
    <div v-if="profile" class="user-card">
      <el-avatar :size="64">{{ (profile.username || '用').charAt(0) }}</el-avatar>
      <div class="user-info">
        <h2>{{ profile.username || '用户' + profile.userId }}</h2>
        <p v-if="profile.bio" class="bio">{{ profile.bio }}</p>
        <div class="stats">
          <div class="stat"><span class="stat-num">{{ profile.followingCount || 0 }}</span><span class="stat-label">关注</span></div>
          <div class="stat"><span class="stat-num">{{ profile.followerCount || 0 }}</span><span class="stat-label">粉丝</span></div>
          <div class="stat"><span class="stat-num">{{ profile.likeCount || 0 }}</span><span class="stat-label">获赞</span></div>
        </div>
      </div>
    </div>

    <h3 class="section-title">笔记 ({{ postTotal }})</h3>
    <div class="note-grid">
      <template v-if="posts.length">
        <NoteCard v-for="item in posts" :key="item.postId" :note="item" />
      </template>
      <el-empty v-else-if="!loading" description="暂无笔记" />
    </div>

    <el-pagination
      v-if="postTotal > pageSize"
      v-model:current-page="page"
      :page-size="pageSize"
      :total="postTotal"
      layout="prev, pager, next"
      @current-change="loadPosts"
      class="pager"
    />
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getUserProfile } from '../api/user'
import { getUserPosts } from '../api/post'
import { useUserStore } from '../stores/user'
import NoteCard from '../components/NoteCard.vue'

const route = useRoute()
const userStore = useUserStore()

const userId = computed(() => Number(route.params.id) || userStore.userInfo?.userId)
const loading = ref(true)
const profile = ref(null)
const posts = ref([])
const postTotal = ref(0)
const page = ref(1)
const pageSize = 20

async function loadProfile() {
  if (!userId.value) return
  loading.value = true
  try {
    const res = await getUserProfile(userId.value)
    profile.value = res.data
  } catch { /* ignore */ }
  finally { loading.value = false }
}

async function loadPosts() {
  if (!userId.value) return
  try {
    const res = await getUserPosts(userId.value, { page: page.value, size: pageSize })
    posts.value = res.data.list || []
    postTotal.value = res.data.total || 0
  } catch { /* ignore */ }
}

watch(userId, (id) => {
  if (!id) return
  page.value = 1
  loadProfile()
  loadPosts()
}, { immediate: true })
</script>

<style scoped>
.profile-page { max-width: 800px; margin: 0 auto; padding: 24px 0; }
.user-card { display: flex; gap: 20px; padding: 24px; background: #fff; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.06); margin-bottom: 24px; }
.user-info { flex: 1; }
.user-info h2 { margin: 0 0 4px; font-size: 20px; color: #303133; }
.bio { font-size: 14px; color: #909399; margin: 0 0 12px; }
.stats { display: flex; gap: 24px; }
.stat { display: flex; flex-direction: column; align-items: center; }
.stat-num { font-size: 18px; font-weight: 600; color: #303133; }
.stat-label { font-size: 12px; color: #909399; }
.section-title { font-size: 16px; color: #303133; margin: 0 0 16px; }
.note-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; min-height: 200px; }
.pager { margin-top: 24px; justify-content: center; }
</style>
