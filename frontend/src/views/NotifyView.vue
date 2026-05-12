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

    <el-skeleton :loading="loading && !list.length" animated :rows="4" />

    <el-empty v-if="!loading && !list.length" description="暂无通知" />

    <el-timeline v-else>
      <el-timeline-item
        v-for="row in list"
        :key="row.notificationId"
        :timestamp="formatTime(row.createdAt)"
        placement="top"
      >
        <el-card shadow="hover" :class="{ unread: row.readStatus === 0 }">
          <div class="row-line">
            <span class="sender">{{ row.senderUsername || '用户' + row.senderId }}</span>
            <el-tag size="small" type="info">{{ typeLabel(row.type) }}</el-tag>
            <span class="content">{{ row.content }}</span>
          </div>
          <div class="row-actions">
            <el-button
              v-if="row.bizId"
              type="primary"
              link
              size="small"
              @click="$router.push('/post/' + row.bizId)"
            >
              查看笔记
            </el-button>
            <el-button
              v-if="row.readStatus === 0"
              type="primary"
              link
              size="small"
              :loading="markingId === row.notificationId"
              @click="markOne(row.notificationId)"
            >
              标为已读
            </el-button>
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
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import { ElMessage } from 'element-plus'
import { getNotifyList, getNotifyUnreadCount, markNotifyRead, markAllNotifyRead } from '../api/notify'
import { getToken } from '../utils/auth'

const loading = ref(false)
const readAllLoading = ref(false)
const markingId = ref(null)
const list = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const unreadCount = ref(0)

const wsConnected = ref(false)
const wsHint = ref('')
let stompClient = null

/** 与 notify-service WebSocket 同源（不经 Vite /api 代理） */
const notifyWsOrigin = import.meta.env.VITE_NOTIFY_WS_ORIGIN || 'http://localhost:8087'

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function typeLabel(type) {
  const map = { like: '点赞', collect: '收藏', comment: '评论', follow: '关注' }
  return map[type] || type || '通知'
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  return d.toLocaleString()
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
    const res = await getNotifyList({ page: p, size: pageSize })
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

async function markOne(id) {
  markingId.value = id
  try {
    await markNotifyRead(id)
    ElMessage.success('已标记已读')
    await loadList(page.value)
  } catch {
    // 拦截器已提示
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
    // 拦截器已提示
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
    onDisconnect: () => {
      wsConnected.value = false
    },
    onStompError: (frame) => {
      console.warn('STOMP error', frame.headers['message'])
      wsConnected.value = false
    },
    onWebSocketError: () => {
      wsConnected.value = false
      wsHint.value =
        '无法连接通知 WebSocket（默认 ' +
        notifyWsOrigin +
        '）。请确认 notify-service 已启动，或在前端配置 VITE_NOTIFY_WS_ORIGIN。'
    }
  })
  stompClient.activate()
}

function disconnectWs() {
  if (stompClient) {
    try {
      stompClient.deactivate()
    } catch {
      /* noop */
    }
    stompClient = null
  }
  wsConnected.value = false
}

onMounted(async () => {
  await loadList(1)
  connectWs()
})

onUnmounted(() => {
  disconnectWs()
})
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
  margin-bottom: 16px;
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
.unread.el-card {
  border-left: 3px solid var(--el-color-primary);
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
