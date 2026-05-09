import request from './request'

export function getNotifyList(params) {
  return request.get('/notify/list', { params })
}

export function readAllNotify() {
  return request.post('/notify/read-all')
}

export function readNotify(id) {
  return request.post(`/notify/${id}/read`)
}

export function getUnreadCount() {
  return request.get('/notify/unread-count')
}
