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
      <div v-if="searched" class="search-results" v-loading="searching">
        <h4>搜索结果（{{ searchTotal }} 条）</h4>
        <div v-if="searchResults.length" class="rank-list">
          <div
            v-for="item in searchResults"
            :key="item.postId"
            class="rank-item"
            @click="goPost(item.postId)"
          >
            <img v-if="item.cover" :src="item.cover" class="search-cover" />
            <span v-else class="rank-num search-num">#</span>
            <div class="rank-info">
              <div class="rank-title">{{ item.title }}</div>
              <div class="rank-meta">
                <span v-if="item.shopName">{{ item.shopName }}</span>
                <span>{{ item.likeCount || 0 }} 赞</span>
              </div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无搜索结果" :image-size="60" />
      </div>

      <!-- 搜索引导（Rank Service 就绪后可替换为 Top10） -->
      <div v-else class="search-hint">
        <el-icon size="48" color="#c0c4cc"><Search /></el-icon>
        <p>输入关键词搜索探店笔记</p>
        <p class="hint-sub">支持按标题、正文、店铺名搜索</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { searchPosts } from '../api/post'

const emit = defineEmits(['close'])

const router = useRouter()
const keyword = ref('')
const searched = ref(false)
const searching = ref(false)
const searchResults = ref([])
const searchTotal = ref(0)

async function handleSearch() {
  const kw = keyword.value.trim()
  if (!kw) return
  searching.value = true
  searched.value = true
  try {
    const res = await searchPosts({ keyword: kw, page: 1, size: 20 })
    searchResults.value = res.data?.list || []
    searchTotal.value = res.data?.total || 0
  } catch {
    searchResults.value = []
    searchTotal.value = 0
  } finally {
    searching.value = false
  }
}

function goPost(id) {
  emit('close')
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

.search-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 0 16px;
  color: #909399;
}

.search-hint p {
  margin: 8px 0 0;
  font-size: 14px;
}

.hint-sub {
  font-size: 12px !important;
  color: #c0c4cc;
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

.search-cover {
  width: 24px;
  height: 24px;
  border-radius: 4px;
  object-fit: cover;
  flex-shrink: 0;
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
