<template>
  <div class="profile-page" v-loading="loading">
    <div v-if="profile" class="user-card">
      <el-avatar :size="64">{{ (profile.username || '用').charAt(0) }}</el-avatar>
      <div class="user-info">
        <h2>{{ profile.username || '用户' + profile.userId }}</h2>
        <p v-if="profile.bio" class="bio">{{ profile.bio }}</p>
        <div class="stats">
          <div class="stat clickable" @click="showFollowList('following')">
            <span class="stat-num">{{ profile.followingCount || 0 }}</span>
            <span class="stat-label">关注</span>
          </div>
          <div class="stat clickable" @click="showFollowList('followers')">
            <span class="stat-num">{{ profile.followerCount || 0 }}</span>
            <span class="stat-label">粉丝</span>
          </div>
          <div class="stat">
            <span class="stat-num">{{ profile.likeCount || 0 }}</span>
            <span class="stat-label">获赞</span>
          </div>
        </div>
      </div>
      <el-button
        v-if="!isOwner"
        :type="following ? 'default' : 'primary'"
        :loading="followLoading"
        @click="toggleFollow"
        class="follow-btn"
      >
        {{ following ? '已关注' : '+ 关注' }}
      </el-button>
    </div>

    <el-tabs v-if="isOwner" v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="我创建的笔记" name="created" />
      <el-tab-pane label="我点赞的笔记" name="liked" />
      <el-tab-pane label="我收藏的笔记" name="favorited" />
    </el-tabs>
    <h3 v-else class="section-title">ta发布的笔记</h3>

    <div class="note-grid">
      <template v-if="currentList.length">
        <NoteCard v-for="item in currentList" :key="item.postId || item.noteId" :note="item" />
      </template>
      <el-empty v-else-if="!loading && currentSearched" description="暂无笔记" />
      <el-empty v-else-if="!loading && !currentSearched" description="点击标签加载笔记" />
    </div>

    <el-pagination
      v-if="currentTotal > pageSize"
      v-model:current-page="currentPage"
      :page-size="pageSize"
      :total="currentTotal"
      layout="prev, pager, next"
      @current-change="loadCurrentList"
      class="pager"
    />

    <!-- 关注/粉丝列表弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="400px" destroy-on-close>
      <div v-loading="dialogLoading">
        <template v-if="dialogList.length">
          <div v-for="u in dialogList" :key="u.userId" class="user-row" @click="goToUser(u.userId)">
            <el-avatar :size="36">{{ (u.username || '用').charAt(0) }}</el-avatar>
            <div class="user-row-info">
              <span class="user-row-name">{{ u.username || '用户' + u.userId }}</span>
              <span v-if="u.bio" class="user-row-bio">{{ u.bio }}</span>
            </div>
          </div>
        </template>
        <el-empty v-else description="暂无数据" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getUserProfile, followUser, checkFollowing, getFollowingList, getFollowersList } from '../api/user'
import { getUserPosts, getUserLikedPosts, getUserFavoritedPosts } from '../api/post'
import { useUserStore } from '../stores/user'
import NoteCard from '../components/NoteCard.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const userId = computed(() => Number(route.params.id) || userStore.userInfo?.userId)
const isOwner = computed(() => userId.value === userStore.userInfo?.userId)
const loading = ref(true)
const profile = ref(null)
const activeTab = ref('created')

// 笔记
const createdPosts = ref([])
const createdTotal = ref(0)
const createdPage = ref(1)
const createdSearched = ref(false)

const likedPosts = ref([])
const likedTotal = ref(0)
const likedPage = ref(1)
const likedSearched = ref(false)

const favoritedPosts = ref([])
const favoritedTotal = ref(0)
const favoritedPage = ref(1)
const favoritedSearched = ref(false)

const pageSize = 20

const currentList = computed(() => {
  if (activeTab.value === 'liked') return likedPosts.value
  if (activeTab.value === 'favorited') return favoritedPosts.value
  return createdPosts.value
})
const currentTotal = computed(() => {
  if (activeTab.value === 'liked') return likedTotal.value
  if (activeTab.value === 'favorited') return favoritedTotal.value
  return createdTotal.value
})
const currentSearched = computed(() => {
  if (activeTab.value === 'liked') return likedSearched.value
  if (activeTab.value === 'favorited') return favoritedSearched.value
  return createdSearched.value
})
const currentPage = computed({
  get: () => {
    if (activeTab.value === 'liked') return likedPage.value
    if (activeTab.value === 'favorited') return favoritedPage.value
    return createdPage.value
  },
  set: (v) => {
    if (activeTab.value === 'liked') likedPage.value = v
    else if (activeTab.value === 'favorited') favoritedPage.value = v
    else createdPage.value = v
  }
})

// ==================== 关注 ====================

const following = ref(false)
const followLoading = ref(false)

async function toggleFollow() {
  followLoading.value = true
  try {
    const res = await followUser(userId.value)
    following.value = res.data.followed
  } catch { /* ignore */ }
  followLoading.value = false
}

// ==================== 关注/粉丝弹窗 ====================

const dialogVisible = ref(false)
const dialogTitle = ref('')
const dialogLoading = ref(false)
const dialogList = ref([])
let dialogType = ''

async function showFollowList(type) {
  dialogType = type
  dialogTitle.value = type === 'following' ? '关注列表' : '粉丝列表'
  dialogVisible.value = true
  dialogLoading.value = true
  dialogList.value = []
  try {
    const fn = type === 'following' ? getFollowingList : getFollowersList
    const res = await fn(userId.value, { page: 1, size: 200 })
    dialogList.value = res.data.list || []
  } catch { /* ignore */ }
  dialogLoading.value = false
}

function goToUser(id) {
  dialogVisible.value = false
  router.push(`/profile/${id}`)
}

// ==================== 加载数据 ====================

async function loadProfile() {
  if (!userId.value) return
  loading.value = true
  try {
    const res = await getUserProfile(userId.value)
    profile.value = res.data
    if (!isOwner.value) {
      checkFollowing(userId.value).then(r => { following.value = r.data.following }).catch(() => {})
    }
  } catch { /* ignore */ }
  finally { loading.value = false }
}

async function loadCreated() {
  if (!userId.value) return
  createdSearched.value = true
  try {
    const res = await getUserPosts(userId.value, { page: createdPage.value, size: pageSize })
    createdPosts.value = res.data.list || []
    createdTotal.value = res.data.total || 0
  } catch { createdPosts.value = [] }
}

async function loadLiked() {
  if (!userId.value) return
  likedSearched.value = true
  try {
    const res = await getUserLikedPosts(userId.value, { page: likedPage.value, size: pageSize })
    likedPosts.value = res.data.list || []
    likedTotal.value = res.data.total || 0
  } catch { likedPosts.value = [] }
}

async function loadFavorited() {
  if (!userId.value) return
  favoritedSearched.value = true
  try {
    const res = await getUserFavoritedPosts(userId.value, { page: favoritedPage.value, size: pageSize })
    favoritedPosts.value = res.data.list || []
    favoritedTotal.value = res.data.total || 0
  } catch { favoritedPosts.value = [] }
}

function loadCurrentList() {
  if (activeTab.value === 'liked') loadLiked()
  else if (activeTab.value === 'favorited') loadFavorited()
  else loadCreated()
}

function onTabChange(tab) {
  activeTab.value = tab
  if (tab === 'created' && !createdSearched.value) loadCreated()
  else if (tab === 'liked' && !likedSearched.value) loadLiked()
  else if (tab === 'favorited' && !favoritedSearched.value) loadFavorited()
}

watch(userId, (id) => {
  if (!id) return
  createdPage.value = 1
  likedPage.value = 1
  favoritedPage.value = 1
  createdPosts.value = []
  likedPosts.value = []
  favoritedPosts.value = []
  createdSearched.value = false
  likedSearched.value = false
  favoritedSearched.value = false
  following.value = false
  activeTab.value = 'created'
  loadProfile()
  loadCreated()
}, { immediate: true })
</script>

<style scoped>
.profile-page { max-width: 800px; margin: 0 auto; padding: 24px 0; }
.user-card { display: flex; gap: 20px; padding: 24px; background: #fff; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.06); margin-bottom: 24px; align-items: center; }
.user-info { flex: 1; }
.user-info h2 { margin: 0 0 4px; font-size: 20px; color: #303133; }
.bio { font-size: 14px; color: #909399; margin: 0 0 12px; }
.stats { display: flex; gap: 24px; }
.stat { display: flex; flex-direction: column; align-items: center; }
.stat.clickable { cursor: pointer; border-radius: 8px; padding: 4px 8px; transition: background 0.2s; }
.stat.clickable:hover { background: #f5f7fa; }
.stat-num { font-size: 18px; font-weight: 600; color: #303133; }
.stat-label { font-size: 12px; color: #909399; }
.follow-btn { flex-shrink: 0; }
.section-title { font-size: 16px; color: #303133; margin: 0 0 16px; }
.note-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; min-height: 200px; }
.pager { margin-top: 24px; justify-content: center; }

.user-row { display: flex; align-items: center; gap: 12px; padding: 10px 8px; border-radius: 8px; cursor: pointer; transition: background 0.2s; }
.user-row:hover { background: #f5f7fa; }
.user-row-info { flex: 1; min-width: 0; }
.user-row-name { font-size: 14px; font-weight: 500; color: #303133; }
.user-row-bio { font-size: 12px; color: #909399; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: block; }
</style>
