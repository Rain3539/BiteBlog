import request from './request'

/** 通知列表（传统分页） */
export function getNotifyList(params) {
  return request.get('/notify/list', { params })
}

export function getNotifyUnreadCount() {
  return request.get('/notify/unread-count')
}

export function markNotifyRead(id) {
  return request.post(`/notify/${id}/read`)
}

export function markAllNotifyRead() {
  return request.post('/notify/read-all')
}
