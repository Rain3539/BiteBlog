<template>
  <div class="rank-page">
    <div class="rank-toolbar">
      <div>
        <h2>热榜</h2>
        <p>按互动热度展示正在被讨论的探店笔记</p>
      </div>
      <div class="toolbar-actions">
        <el-radio-group v-model="activeType" size="large" @change="switchType">
          <el-radio-button
            v-for="item in typeOptions"
            :key="item.value"
            :label="item.value"
          >
            {{ item.label }}
          </el-radio-button>
        </el-radio-group>
        <el-tooltip content="刷新" placement="bottom">
          <el-button :icon="Refresh" circle :loading="loading" @click="loadRank" />
        </el-tooltip>
        <el-button type="primary" plain :loading="rebuilding" @click="handleRebuild">
          重建榜单
        </el-button>
      </div>
    </div>

    <div class="rank-stats">
      <div class="stat-item">
        <span class="stat-label">当前榜单</span>
        <strong>{{ currentTypeLabel }}</strong>
      </div>
      <div class="stat-item">
        <span class="stat-label">收录笔记</span>
        <strong>{{ total }}</strong>
      </div>
      <div class="stat-item">
        <span class="stat-label">最高热度</span>
        <strong>{{ topScore }}</strong>
      </div>
    </div>

    <el-skeleton :loading="loading && !rankList.length" animated :rows="8" />

    <template v-if="!loading || rankList.length">
      <el-table
        v-if="rankList.length"
        :data="rankList"
        class="rank-table"
        row-key="postId"
        @row-click="goPost"
      >
        <el-table-column label="排名" width="86" align="center">
          <template #default="{ row }">
            <span class="rank-no" :class="{ top: row.rankNo <= 3 }">{{ row.rankNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="笔记" min-width="260">
          <template #default="{ row }">
            <div class="post-cell">
              <span class="post-title">{{ row.title }}</span>
              <span v-if="row.shopName" class="shop-name">{{ row.shopName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="互动" min-width="210">
          <template #default="{ row }">
            <div class="interaction-line">
              <span>{{ row.likeCount || 0 }} 赞</span>
              <span>{{ row.collectCount || 0 }} 藏</span>
              <span>{{ row.commentCount || 0 }} 评</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="热度" width="120" align="right">
          <template #default="{ row }">
            <strong class="hot-score">{{ formatScore(row.hotScore) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="96" align="center">
          <template #default="{ row }">
            <el-button type="primary" link @click.stop="goPost(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="暂无热榜数据">
        <el-button type="primary" plain :loading="rebuilding" @click="handleRebuild">重建榜单</el-button>
      </el-empty>
    </template>

    <div v-if="total > pageSize" class="rank-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        @current-change="loadRank"
        @size-change="handleSizeChange"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getRankList, rebuildRank } from '../api/rank'

const router = useRouter()

const typeOptions = [
  { label: '日榜', value: 'daily' },
  { label: '周榜', value: 'weekly' },
  { label: '总榜', value: 'all' }
]

const activeType = ref('daily')
const loading = ref(false)
const rebuilding = ref(false)
const rankList = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)

const currentTypeLabel = computed(() => {
  return typeOptions.find(item => item.value === activeType.value)?.label || '日榜'
})

const topScore = computed(() => {
  if (!rankList.value.length) return '0.0'
  return formatScore(rankList.value[0].hotScore)
})

async function loadRank() {
  loading.value = true
  try {
    const res = await getRankList({
      type: activeType.value,
      page: page.value,
      size: pageSize.value
    })
    rankList.value = res.data?.list || []
    total.value = Number(res.data?.total ?? 0)
  } catch {
    rankList.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function switchType() {
  page.value = 1
  loadRank()
}

function handleSizeChange() {
  page.value = 1
  loadRank()
}

async function handleRebuild() {
  try {
    await ElMessageBox.confirm(`确认重建${currentTypeLabel.value}缓存吗？`, '重建榜单', {
      type: 'warning',
      confirmButtonText: '重建',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }

  rebuilding.value = true
  try {
    await rebuildRank(activeType.value)
    ElMessage.success('榜单已重建')
    page.value = 1
    await loadRank()
  } catch {
    // request interceptor has shown the error.
  } finally {
    rebuilding.value = false
  }
}

function goPost(row) {
  const postId = typeof row === 'object' ? row.postId : row
  if (postId) {
    router.push(`/post/${postId}`)
  }
}

function formatScore(value) {
  const n = Number(value || 0)
  return n.toFixed(1)
}

function formatTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

onMounted(loadRank)
</script>

<style scoped>
.rank-page {
  padding: 24px 0 48px;
}

.rank-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}

.rank-toolbar h2 {
  margin: 0;
  font-size: 24px;
  color: #303133;
}

.rank-toolbar p {
  margin: 8px 0 0;
  color: #606266;
  font-size: 14px;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.rank-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}

.stat-item {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 14px 16px;
  background: #fff;
}

.stat-label {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.stat-item strong {
  font-size: 22px;
  color: #303133;
}

.rank-table {
  width: 100%;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  overflow: hidden;
}

.rank-table :deep(.el-table__row) {
  cursor: pointer;
}

.rank-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: #f4f4f5;
  color: #909399;
  font-weight: 700;
}

.rank-no.top {
  background: #fef0f0;
  color: #f56c6c;
}

.post-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.post-title {
  color: #303133;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-name {
  color: #909399;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.interaction-line {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  color: #606266;
  font-size: 13px;
}

.hot-score {
  color: #f56c6c;
  font-size: 15px;
}

.rank-pager {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

@media (max-width: 760px) {
  .rank-toolbar {
    flex-direction: column;
  }

  .toolbar-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .rank-stats {
    grid-template-columns: 1fr;
  }
}
</style>
