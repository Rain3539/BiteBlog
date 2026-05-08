<template>
  <div class="search-overlay" @click.self="$emit('close')">
    <div class="search-panel">
      <!-- 搜索框 -->
      <div class="search-header">
        <el-input
          v-model="keyword"
          placeholder="搜索探店笔记..."
          size="large"
          clearable
          @keyup.enter="handleSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button text @click="$emit('close')" class="close-btn">关闭</el-button>
      </div>

      <!-- 搜索结果 -->
      <div v-if="searched" class="search-results">
        <h4>搜索结果</h4>
        <div v-if="searchResults.length" class="rank-list">
          <div
            v-for="item in searchResults"
            :key="item.id"
            class="rank-item"
            @click="goPost(item.id)"
          >
            <span class="rank-num search-num">#</span>
            <div class="rank-info">
              <div class="rank-title">{{ item.title }}</div>
              <div class="rank-meta">{{ item.authorName }} · {{ item.likeCount }} 赞</div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无搜索结果" :image-size="60" />
      </div>

      <!-- Top 10 热门笔记 -->
      <div v-else class="top10-section">
        <h4>热门笔记 Top 10</h4>
        <div v-if="topList.length" class="rank-list">
          <div
            v-for="(item, index) in topList"
            :key="item.id"
            class="rank-item"
            @click="goPost(item.id)"
          >
            <span class="rank-num" :class="{ 'top3': index < 3 }">{{ index + 1 }}</span>
            <div class="rank-info">
              <div class="rank-title">{{ item.title }}</div>
              <div class="rank-meta">
                <span>{{ item.authorName || '匿名' }}</span>
                <span class="rank-score">{{ item.score || item.likeCount || 0 }} 热度</span>
              </div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无排行数据" :image-size="60" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { getTop10 } from '../api/rank'

defineEmits(['close'])

const router = useRouter()
const keyword = ref('')
const searched = ref(false)
const searchResults = ref([])
const topList = ref([])

onMounted(async () => {
  try {
    const res = await getTop10()
    topList.value = res.data?.list || res.data || []
  } catch {
    topList.value = []
  }
})

function handleSearch() {
  if (!keyword.value.trim()) return
  // 搜索功能待 Post Service 实现 ES 搜索接口
  searched.value = true
  searchResults.value = []
}

function goPost(id) {
  router.push(`/post/${id}`)
}
</script>

<style scoped>
.search-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 2000;
  display: flex;
  justify-content: center;
  padding-top: 80px;
}

.search-panel {
  width: 600px;
  max-height: 70vh;
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  overflow-y: auto;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}

.search-header {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 20px;
}

.close-btn {
  white-space: nowrap;
}

h4 {
  margin: 0 0 12px;
  font-size: 15px;
  color: #333;
}

.rank-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rank-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}

.rank-item:hover {
  background: #f5f7fa;
}

.rank-num {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: #f0f0f0;
  color: #999;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.rank-num.top3 {
  background: #fff0f0;
  color: #f56c6c;
}

.search-num {
  background: #ecf5ff;
  color: #409eff;
}

.rank-info {
  flex: 1;
  min-width: 0;
}

.rank-title {
  font-size: 14px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.rank-meta {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
  display: flex;
  gap: 12px;
}

.rank-score {
  color: #f56c6c;
}
</style>
