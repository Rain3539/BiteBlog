<template>
  <div class="discover-page">
    <div class="discover-toolbar">
      <div>
        <h2>发现</h2>
        <p>根据兴趣、相似用户和近期热度推荐探店笔记</p>
      </div>
      <div class="toolbar-actions">
        <el-input
          v-model.trim="filters.tag"
          class="filter-input"
          clearable
          placeholder="标签/关键词"
          @keyup.enter="refreshList"
          @clear="refreshList"
        />
        <el-input
          v-model.trim="filters.city"
          class="filter-input"
          clearable
          placeholder="城市/地址"
          @keyup.enter="refreshList"
          @clear="refreshList"
        />
        <el-tooltip content="刷新推荐" placement="bottom">
          <el-button :icon="Refresh" circle :loading="loading" @click="refreshList" />
        </el-tooltip>
      </div>
    </div>

    <el-alert
      v-if="healthError"
      class="health-alert"
      type="warning"
      :closable="false"
      show-icon
      title="推荐服务健康检查暂不可用，列表请求会继续尝试。"
    />

    <el-skeleton :loading="loading && !recommendList.length" animated :rows="8" />

    <template v-if="!loading || recommendList.length">
      <div v-if="recommendList.length" class="recommend-grid">
        <article
          v-for="item in recommendList"
          :key="item.postId"
          class="recommend-card"
          @click="goPost(item)"
        >
          <div class="cover-box">
            <img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.title" />
            <div v-else class="cover-placeholder">
              <el-icon><Picture /></el-icon>
            </div>
          </div>
          <div class="card-body">
            <div class="card-title">{{ item.title || '未命名探店笔记' }}</div>
            <div class="shop-line">
              <el-icon><Shop /></el-icon>
              <span>{{ item.shopName || '暂无店铺信息' }}</span>
            </div>
            <div class="reason-line">
              <el-tag size="small" type="success" effect="plain">{{ item.reason || '为你推荐' }}</el-tag>
            </div>
            <div class="meta-line">
              <span>{{ item.likeCount || 0 }} 赞</span>
              <span>{{ item.collectCount || 0 }} 藏</span>
              <span>{{ item.commentCount || 0 }} 评</span>
            </div>
            <div class="time-line">{{ formatTime(item.createdAt) }}</div>
          </div>
        </article>
      </div>

      <el-empty v-else description="暂无推荐内容">
        <el-button type="primary" plain :loading="loading" @click="refreshList">重新获取</el-button>
      </el-empty>
    </template>

    <div class="load-more">
      <el-button
        v-if="hasMore"
        type="primary"
        plain
        :loading="loadingMore"
        @click="loadMore"
      >
        加载更多
      </el-button>
      <span v-else-if="recommendList.length" class="end-text">已经到底了</span>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Picture, Refresh, Shop } from '@element-plus/icons-vue'
import { getDiscoverList, getRecommendHealth, saveRecommendExposures } from '../api/recommend'

const router = useRouter()

const filters = reactive({
  tag: '',
  city: ''
})

const recommendList = ref([])
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const healthError = ref(false)
const exposedIds = new Set()

async function checkHealth() {
  try {
    await getRecommendHealth()
    healthError.value = false
  } catch {
    healthError.value = true
  }
}

async function refreshList() {
  cursor.value = null
  hasMore.value = false
  recommendList.value = []
  exposedIds.clear()
  await loadRecommend(false)
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  await loadRecommend(true)
}

async function loadRecommend(append) {
  const state = append ? loadingMore : loading
  state.value = true
  try {
    const params = {
      cursor: append ? cursor.value : 0,
      size: 20
    }
    if (filters.tag) params.tag = filters.tag
    if (filters.city) params.city = filters.city

    const res = await getDiscoverList(params)
    const data = res.data || {}
    const list = data.list || []
    recommendList.value = append ? [...recommendList.value, ...list] : list
    cursor.value = data.cursor ?? null
    hasMore.value = Boolean(data.hasMore)
    await reportExposure(list)
  } catch {
    if (!append) recommendList.value = []
  } finally {
    state.value = false
  }
}

async function reportExposure(list) {
  const postIds = list
    .map(item => item.postId)
    .filter(id => id && !exposedIds.has(id))

  if (!postIds.length) return
  postIds.forEach(id => exposedIds.add(id))

  try {
    await saveRecommendExposures(postIds)
  } catch {
    ElMessage.warning('曝光上报失败，推荐内容仍可浏览')
  }
}

function goPost(item) {
  if (item.postId) {
    router.push(`/post/${item.postId}`)
  }
}

function formatTime(value) {
  if (!value) return ''
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

onMounted(async () => {
  await checkHealth()
  await loadRecommend(false)
})
</script>

<style scoped>
.discover-page {
  padding: 24px 0 48px;
}

.discover-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}

.discover-toolbar h2 {
  margin: 0;
  font-size: 24px;
  color: #303133;
}

.discover-toolbar p {
  margin: 8px 0 0;
  color: #606266;
  font-size: 14px;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 12px;
}

.filter-input {
  width: 180px;
}

.health-alert {
  margin-bottom: 16px;
}

.recommend-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}

.recommend-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
  cursor: pointer;
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}

.recommend-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.08);
}

.cover-box {
  width: 100%;
  aspect-ratio: 4 / 3;
  background: #f5f7fa;
}

.cover-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
  font-size: 36px;
}

.card-body {
  padding: 14px;
}

.card-title {
  color: #303133;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.4;
  min-height: 44px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.shop-line {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 8px;
  color: #606266;
  font-size: 13px;
  min-width: 0;
}

.shop-line span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reason-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.meta-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  color: #909399;
  font-size: 13px;
}

.time-line {
  margin-top: 8px;
  color: #c0c4cc;
  font-size: 12px;
}

.load-more {
  display: flex;
  justify-content: center;
  margin-top: 24px;
  min-height: 32px;
}

.end-text {
  color: #909399;
  font-size: 13px;
}

@media (max-width: 760px) {
  .discover-toolbar {
    flex-direction: column;
  }

  .toolbar-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .filter-input {
    width: 100%;
  }

}
</style>
