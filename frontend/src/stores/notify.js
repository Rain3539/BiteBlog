import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getNotifyUnreadCount } from '../api/notify'
import { getToken } from '../utils/auth'

/** 全局通知未读数：顶栏角标与通知页共用，避免「全部已读」后角标不同步 */
export const useNotifyStore = defineStore('notify', () => {
  const unreadCount = ref(0)

  async function refreshUnread() {
    if (!getToken()) {
      unreadCount.value = 0
      return 0
    }
    try {
      const res = await getNotifyUnreadCount()
      unreadCount.value = Number(res.data?.unreadCount ?? 0)
    } catch {
      unreadCount.value = 0
    }
    return unreadCount.value
  }

  function setUnreadCount(n) {
    unreadCount.value = Math.max(0, Number(n) || 0)
  }

  function incrementUnread(delta = 1) {
    unreadCount.value = Math.max(0, unreadCount.value + delta)
  }

  function decrementUnread(delta = 1) {
    unreadCount.value = Math.max(0, unreadCount.value - delta)
  }

  function clearUnread() {
    unreadCount.value = 0
  }

  return {
    unreadCount,
    refreshUnread,
    setUnreadCount,
    incrementUnread,
    decrementUnread,
    clearUnread
  }
})
