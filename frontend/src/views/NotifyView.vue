<template>
  <div class="notify-page">
    <div class="notify-header">
      <h2>通知中心</h2>
      <div class="header-actions">
        <el-tag v-if="wsConnected" type="success" size="small">实时已连接</el-tag>
        <el-tag v-else type="info" size="small">实时未连接</el-tag>
        <span class="unread">未读 {{ unreadCount }}</span>
        <el-button type="primary" link :loading="loading" @click="loadList(1)">刷新</el-button>
        <el-button type="primary" plain :disabled="unreadCount === 0" :loading="readAllLoading" @click="handleReadAll">
          全部已读
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="wsHint"
      :title="wsHint"
      type="info"
      show-icon
      closable
      class="ws-hint"
      @close="wsHint = ''"
    />

    <!-- 分类 Tab：全部 / 未读 / 点赞 / 收藏 / 评论 -->
    <el-tabs v-model="activeTab" class="notify-tabs" @tab-change="onTabChange">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="未读" name="unread" />
      <el-tab-pane label="点赞" name="like" />
      <el-tab-pane label="收藏" name="collect" />
      <el-tab-pane label="评论" name="comment" />
    </el-tabs>

    <el-skeleton :loading="loading && !list.length" animated :rows="4" />

    <el-empty v-if="!loading && !list.length" description="暂无通知" />

    <el-timeline v-else>
      <el-timeline-item
        v-for="row in list"
        :key="row.notificationId"
        :timestamp="formatTime(row.createdAt)"
        placement="top"
      >
        <!--
          整行卡片可点击：点击后先标记已读，再跳转到对应笔记。
          若 bizId 为空则不跳转；点击操作按钮时通过 @click.stop 阻止冒泡。
        -->
        <el-card
          shadow="hover"
          :class="{ unread: row.readStatus === 0, clickable: !!row.bizId }"
          @click="handleCardClick(row)"
        >
          <div class="row-line">
            <span class="sender">{{ row.senderUsername || '用户' + row.senderId }}</span>
            <el-tag size="small" type="info">{{ typeLabel(row.type) }}</el-tag>
            <span class="content">{{ row.content }}</span>
          </div>
          <div class="row-actions">
            <el-button
              v-if="row.readStatus === 0"
              type="primary"
              link
              size="small"
              :loading="markingId === row.notificationId"
              @click.stop="markOne(row.notificationId)"
            >
              标为已读
            </el-button>
            <span v-if="!row.bizId" class="no-link-hint">（无关联笔记）</span>
          </div>
        </el-card>
      </el-timeline-item>
    </el-timeline>

    <div v-if="total > list.length" class="pager">
      <el-button :disabled="page <= 1" @click="loadList(page - 1)">上一页</el-button>
      <span class="page-info">第 {{ page }} 页 / 共 {{ totalPages }} 页</span>
      <el-button :disabled="page >= totalPages" @click="loadList(page + 1)">下一页</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import { ElMessage } from 'element-plus'
import { getNotifyList, getNotifyUnreadCount, markNotifyRead, markAllNotifyRead } from '../api/notify'
import { getToken } from '../utils/auth'

const router = useRouter()

const loading = ref(false)
const readAllLoading = ref(false)
const markingId = ref(null)
const list = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const unreadCount = ref(0)

/** 当前激活的 Tab：all / unread / like / collect / comment */
const activeTab = ref('all')

const wsConnected = ref(false)
const wsHint = ref('')
let stompClient = null

const notifyWsOrigin = import.meta.env.VITE_NOTIFY_WS_ORIGIN || 'http://localhost:8087'

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function typeLabel(type) {
  const map = { like: '点赞', collect: '收藏', comment: '评论', follow: '关注' }
  return map[type] || type || '通知'
}

function formatTime(t) {
  if (!t) return ''
  return new Date(t).toLocaleString()
}

/** 根据当前 Tab 构造过滤参数 */
function buildFilterParams(p) {
  const params = { page: p, size: pageSize }
  if (activeTab.value === 'unread') {
    params.readStatus = 0
  } else if (activeTab.value !== 'all') {
    params.type = activeTab.value
  }
  return params
}

async function refreshUnread() {
  try {
    const res = await getNotifyUnreadCount()
    unreadCount.value = Number(res.data?.unreadCount ?? 0)
  } catch {
    unreadCount.value = 0
  }
}

async function loadList(p) {
  page.value = p
  loading.value = true
  try {
    const res = await getNotifyList(buildFilterParams(p))
    list.value = res.data?.list || []
    total.value = Number(res.data?.total ?? 0)
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
  await refreshUnread()
}

function onTabChange() {
  loadList(1)
}

/**
 * 点击整行卡片：若有 bizId，先标记已读，然后跳转到笔记详情。
 * PostDetailView 负责处理笔记不存在（404）的情况并展示提示。
 */
async function handleCardClick(row) {
  if (!row.bizId) return
  // 标记已读（不阻塞跳转，失败静默）
  if (row.readStatus === 0) {
    markNotifyRead(row.notificationId)
      .then(() => {
        row.readStatus = 1
        if (unreadCount.value > 0) unreadCount.value -= 1
      })
      .catch(() => {})
  }
  router.push('/post/' + row.bizId)
}

async function markOne(id) {
  markingId.value = id
  try {
    await markNotifyRead(id)
    ElMessage.success('已标记已读')
    await loadList(page.value)
  } catch {
    // axios 拦截器已提示
  } finally {
    markingId.value = null
  }
}

async function handleReadAll() {
  readAllLoading.value = true
  try {
    await markAllNotifyRead()
    ElMessage.success('已全部标为已读')
    await loadList(page.value)
  } catch {
    // axios 拦截器已提示
  } finally {
    readAllLoading.value = false
  }
}

function connectWs() {
  const token = getToken()
  if (!token) {
    wsHint.value = '未登录，无法建立实时通知连接。'
    return
  }
  const url = `${notifyWsOrigin}/ws-notify?token=${encodeURIComponent(token)}`

  stompClient = new Client({
    webSocketFactory: () => new SockJS(url),
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      wsConnected.value = true
      stompClient.subscribe('/user/queue/notify', (message) => {
        try {
          const payload = JSON.parse(message.body)
          if (payload?.notificationId) {
            // 仅在"全部"或匹配当前 Tab 时插入实时条目，避免 Tab 过滤失效
            const matchTab =
              activeTab.value === 'all' ||
              (activeTab.value === 'unread' && payload.readStatus === 0) ||
              activeTab.value === payload.type
            if (matchTab) {
              list.value.unshift({
                notificationId: payload.notificationId,
                senderId: payload.senderId,
                senderUsername: payload.senderUsername,
                type: payload.type,
                bizId: payload.bizId,
                content: payload.content,
                readStatus: payload.readStatus ?? 0,
                createdAt: payload.createdAt
              })
              total.value += 1
            }
            if (payload.readStatus === 0) {
              unreadCount.value += 1
            }
          }
        } catch {
          refreshUnread()
          loadList(1)
        }
      })
    },
    onDisconnect: () => { wsConnected.value = false },
    onStompError: (frame) => {
      console.warn('STOMP error', frame.headers['message'])
      wsConnected.value = false
    },
    onWebSocketError: () => {
      wsConnected.value = false
      wsHint.value =
        `无法连接通知 WebSocket（默认 ${notifyWsOrigin}）。` +
        '请确认 notify-service 已启动，或在前端配置 VITE_NOTIFY_WS_ORIGIN。'
    }
  })
  stompClient.activate()
}

function disconnectWs() {
  if (stompClient) {
    try { stompClient.deactivate() } catch { /* noop */ }
    stompClient = null
  }
  wsConnected.value = false
}

onMounted(async () => {
  await loadList(1)
  connectWs()
})

onUnmounted(() => { disconnectWs() })
</script>

<style scoped>
.notify-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 16px 0 48px;
}
.notify-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.notify-header h2 {
  margin: 0;
  font-size: 1.25rem;
}
.header-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.notify-tabs {
  margin-bottom: 12px;
}
.unread {
  font-size: 14px;
  color: var(--el-color-danger);
}
.ws-hint {
  margin-bottom: 16px;
}
.row-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.sender {
  font-weight: 600;
}
.content {
  color: var(--el-text-color-regular);
}
.row-actions {
  margin-top: 8px;
}
/* 未读通知左侧蓝色竖线 */
.unread.el-card {
  border-left: 3px solid var(--el-color-primary);
}
/* 有关联笔记时整行显示手型，提示可点击 */
.clickable {
  cursor: pointer;
  transition: box-shadow 0.15s;
}
.clickable:hover {
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.12);
}
.no-link-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 24px;
}
.page-info {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
</style>
