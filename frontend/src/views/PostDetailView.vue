<template>
  <div class="post-detail-page" v-loading="loading">
    <!-- 图片区 -->
    <div v-if="post.imageUrls?.length" class="image-gallery">
      <el-carousel v-if="post.imageUrls.length > 1" height="400px" trigger="click">
        <el-carousel-item v-for="(url, i) in post.imageUrls" :key="i">
          <img :src="url" class="gallery-img" />
        </el-carousel-item>
      </el-carousel>
      <img v-else :src="post.imageUrls[0]" class="single-img" />
    </div>

    <!-- 标题与作者 -->
    <div class="post-header">
      <h2>{{ post.title }}</h2>
      <div class="author-row">
        <el-avatar :size="36">{{ (post.authorName || '用').charAt(0) }}</el-avatar>
        <span class="author-name">{{ post.authorName || '用户' + post.authorId }}</span>
        <span class="post-time">{{ formatTime(post.createdAt) }}</span>
        <el-button
          v-if="isAuthor"
          type="danger"
          size="small"
          plain
          @click="handleDelete"
          style="margin-left: auto"
        >
          删除
        </el-button>
      </div>
    </div>

    <!-- 评分 -->
    <div v-if="post.scoreColor || post.scoreSmell || post.scoreTaste" class="scores-card">
      <div class="score-row" v-if="post.scoreColor">
        <span>环境</span><el-rate :model-value="post.scoreColor" disabled show-score />
      </div>
      <div class="score-row" v-if="post.scoreSmell">
        <span>卫生</span><el-rate :model-value="post.scoreSmell" disabled show-score />
      </div>
      <div class="score-row" v-if="post.scoreTaste">
        <span>口味</span><el-rate :model-value="post.scoreTaste" disabled show-score />
      </div>
    </div>

    <!-- 正文 -->
    <div class="post-content">{{ post.content }}</div>

    <!-- 店铺信息 -->
    <div v-if="post.shopName || post.address" class="shop-card">
      <div class="shop-name" v-if="post.shopName">
        <el-icon><Shop /></el-icon> {{ post.shopName }}
      </div>
      <div class="shop-addr" v-if="post.address">
        <el-icon><Location /></el-icon> {{ post.address }}
        <a
          v-if="post.longitude && post.latitude"
          :href="`https://uri.amap.com/marker?position=${post.longitude},${post.latitude}&name=${encodeURIComponent(post.shopName || '探店位置')}`"
          target="_blank"
          class="map-link"
        >在地图上看</a>
      </div>
    </div>

    <!-- 互动栏 -->
    <div class="action-bar">
      <el-button
        :type="post.liked ? 'primary' : 'default'"
        :icon="post.liked ? StarFilled : Star"
        @click="toggleLike"
        circle
      />
      <span class="action-count">{{ post.likeCount || 0 }}</span>

      <el-button
        :type="post.favorited ? 'warning' : 'default'"
        :icon="post.favorited ? Collection : Collection"
        @click="toggleFavorite"
        circle
        style="margin-left: 16px"
      />
      <span class="action-count">{{ post.collectCount || 0 }}</span>

      <span class="action-count" style="margin-left: 16px">
        <el-icon><ChatDotRound /></el-icon> {{ post.commentCount || 0 }}
      </span>
    </div>

    <!-- 评论区域 -->
    <div class="comments-section">
      <h4>评论 ({{ post.commentCount || 0 }})</h4>

      <!-- 发表评论 -->
      <div class="comment-input">
        <el-input
          v-model="commentText"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="写下你的评论..."
        />
        <el-button type="primary" size="small" :loading="commenting" @click="handleComment" class="comment-submit">
          发表
        </el-button>
      </div>

      <!-- 评论列表 -->
      <div v-if="comments.length" class="comment-list">
        <div v-for="c in comments" :key="c.commentId" class="comment-item">
          <el-avatar :size="28" class="comment-avatar">{{ (c.username || '用').charAt(0) }}</el-avatar>
          <div class="comment-body">
            <div class="comment-header">
              <span class="comment-user">{{ c.username || '用户' + c.userId }}</span>
              <span class="comment-time">{{ formatTime(c.createdAt) }}</span>
            </div>
            <div class="comment-content">{{ c.content }}</div>
            <el-button text size="small" type="primary" @click="startReply(c)">回复</el-button>

            <!-- 回复列表 -->
            <div v-if="c.replies?.length" class="reply-list">
              <div v-for="r in c.replies" :key="r.commentId" class="reply-item">
                <el-avatar :size="22" class="comment-avatar">{{ (r.username || '用').charAt(0) }}</el-avatar>
                <div class="comment-body">
                  <div class="comment-header">
                    <span class="comment-user">{{ r.username || '用户' + r.userId }}</span>
                    <span class="comment-time">{{ formatTime(r.createdAt) }}</span>
                  </div>
                  <div class="comment-content">{{ r.content }}</div>
                </div>
              </div>
            </div>

            <!-- 回复输入框 -->
            <div v-if="replyTarget === c.commentId" class="reply-input">
              <el-input
                v-model="replyText"
                size="small"
                :placeholder="'回复 @' + (c.username || '用户' + c.userId) + '...'"
                @keyup.enter="submitReply(c.commentId)"
              />
              <el-button size="small" type="primary" @click="submitReply(c.commentId)">回复</el-button>
              <el-button size="small" @click="cancelReply">取消</el-button>
            </div>
          </div>
        </div>
      </div>

      <!-- 分页 -->
      <el-pagination
        v-if="commentTotal > commentPageSize"
        v-model:current-page="commentPage"
        :page-size="commentPageSize"
        :total="commentTotal"
        layout="prev, next"
        @current-change="loadComments"
        class="comment-pager"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Star, StarFilled, Collection, ChatDotRound, Shop, Location } from '@element-plus/icons-vue'
import { getPostDetail, likePost, favoritePost, commentPost, getPostComments, deletePost } from '../api/post'
import { useUserStore } from '../stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const postId = computed(() => Number(route.params.id))
const loading = ref(true)

const post = reactive({
  postId: 0,
  authorId: 0,
  authorName: '',
  title: '',
  content: '',
  shopName: '',
  address: '',
  longitude: null,
  latitude: null,
  imageUrls: [],
  scoreColor: 0,
  scoreSmell: 0,
  scoreTaste: 0,
  likeCount: 0,
  collectCount: 0,
  commentCount: 0,
  liked: false,
  favorited: false,
  createdAt: ''
})

const isAuthor = computed(() => userStore.userInfo?.userId === post.authorId)

// ==================== 加载详情 ====================

async function loadDetail() {
  loading.value = true
  try {
    const res = await getPostDetail(postId.value)
    Object.assign(post, res.data)
  } catch {
    router.replace('/')
  } finally {
    loading.value = false
  }
}

// ==================== 点赞 / 收藏 ====================

async function toggleLike() {
  try {
    const res = await likePost(postId.value)
    post.liked = res.data.liked
    post.likeCount += res.data.liked ? 1 : -1
  } catch { /* 拦截器已处理 */ }
}

async function toggleFavorite() {
  try {
    const res = await favoritePost(postId.value)
    post.favorited = res.data.favorited
    post.collectCount += res.data.favorited ? 1 : -1
  } catch { /* 拦截器已处理 */ }
}

// ==================== 评论 ====================

const comments = ref([])
const commentTotal = ref(0)
const commentPage = ref(1)
const commentPageSize = 20
const commenting = ref(false)
const commentText = ref('')
const replyText = ref('')
const replyTarget = ref(null)

async function loadComments() {
  try {
    const res = await getPostComments(postId.value, { page: commentPage.value, size: commentPageSize })
    comments.value = res.data.list || []
    commentTotal.value = res.data.total || 0
  } catch { /* ignore */ }
}

async function handleComment() {
  if (!commentText.value.trim()) return
  commenting.value = true
  try {
    await commentPost(postId.value, { content: commentText.value, parentId: null })
    commentText.value = ''
    post.commentCount++
    commentPage.value = 1
    await loadComments()
  } catch { /* ignore */ }
  commenting.value = false
}

function startReply(comment) {
  replyTarget.value = comment.commentId
  replyText.value = ''
}

function cancelReply() {
  replyTarget.value = null
  replyText.value = ''
}

async function submitReply(parentId) {
  if (!replyText.value.trim()) return
  try {
    await commentPost(postId.value, { content: replyText.value, parentId })
    replyText.value = ''
    replyTarget.value = null
    post.commentCount++
    await loadComments()
  } catch { /* ignore */ }
}

// ==================== 删除 ====================

async function handleDelete() {
  try {
    await ElMessageBox.confirm('确定删除这篇笔记吗？删除后不可恢复。', '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await deletePost(postId.value)
    ElMessage.success('删除成功')
    router.replace('/')
  } catch { /* 取消或失败 */ }
}

// ==================== 工具 ====================

function formatTime(t) {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  })
}

watch(postId, (newId) => {
  if (!newId) return
  commentPage.value = 1
  commentText.value = ''
  replyTarget.value = null
  loadDetail()
  loadComments()
}, { immediate: true })
</script>

<style scoped>
.post-detail-page {
  max-width: 700px;
  margin: 0 auto;
  padding: 24px 0;
}

/* 图片 */
.image-gallery {
  border-radius: 12px;
  overflow: hidden;
  margin-bottom: 24px;
}

.gallery-img, .single-img {
  width: 100%;
  max-height: 400px;
  object-fit: cover;
  border-radius: 12px;
}

/* 标题与作者 */
.post-header {
  margin-bottom: 20px;
}

.post-header h2 {
  margin: 0 0 12px;
  font-size: 22px;
  color: #303133;
}

.author-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.author-name {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.post-time {
  font-size: 13px;
  color: #909399;
}

/* 评分卡片 */
.scores-card {
  background: #fafafa;
  border-radius: 8px;
  padding: 16px 20px;
  margin-bottom: 20px;
}

.score-row {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  color: #606266;
}

.score-row + .score-row {
  margin-top: 8px;
}

/* 正文 */
.post-content {
  font-size: 15px;
  line-height: 1.8;
  color: #303133;
  white-space: pre-wrap;
  margin-bottom: 20px;
}

/* 店铺 */
.shop-card {
  background: #f0f9eb;
  border-radius: 8px;
  padding: 16px 20px;
  margin-bottom: 20px;
}

.shop-name {
  font-size: 15px;
  font-weight: 600;
  color: #67c23a;
  display: flex;
  align-items: center;
  gap: 6px;
}

.shop-addr {
  font-size: 13px;
  color: #909399;
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
}

.map-link {
  font-size: 12px;
  color: #409eff;
  text-decoration: none;
  margin-left: 8px;
  white-space: nowrap;
}

.map-link:hover {
  text-decoration: underline;
}

/* 互动栏 */
.action-bar {
  display: flex;
  align-items: center;
  padding: 16px 0;
  border-top: 1px solid #ebeef5;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 24px;
}

.action-count {
  font-size: 14px;
  color: #606266;
  margin-left: 6px;
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 评论 */
.comments-section h4 {
  margin: 0 0 16px;
  font-size: 16px;
  color: #303133;
}

.comment-input {
  margin-bottom: 24px;
}

.comment-submit {
  margin-top: 8px;
  float: right;
}

.comment-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.comment-item {
  display: flex;
  gap: 10px;
}

.comment-avatar {
  flex-shrink: 0;
  margin-top: 2px;
}

.comment-body {
  flex: 1;
  min-width: 0;
}

.comment-header {
  margin-bottom: 4px;
}

.comment-user {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}

.comment-time {
  font-size: 12px;
  color: #c0c4cc;
  margin-left: 8px;
}

.comment-content {
  font-size: 14px;
  color: #303133;
  line-height: 1.6;
  word-break: break-word;
}

.reply-list {
  margin-top: 10px;
  padding-left: 12px;
  border-left: 2px solid #ebeef5;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.reply-item {
  display: flex;
  gap: 10px;
}

.reply-input {
  margin-top: 8px;
  display: flex;
  gap: 8px;
  align-items: center;
}

.comment-pager {
  margin-top: 16px;
  justify-content: center;
}
</style>
