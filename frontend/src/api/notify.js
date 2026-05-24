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

/** 通知偏好 */
export function getNotifyPreferences() {
  return request.get('/notify/preference')
}

export function muteNotifyType(type) {
  return request.post('/notify/preference/mute/type', { type })
}

export function muteNotifySender(senderId) {
  return request.post('/notify/preference/mute/sender', { senderId })
}

export function setNotifyDnd(timeRange) {
  return request.post('/notify/preference/dnd', { timeRange })
}

export function clearNotifyDnd() {
  return request.delete('/notify/preference/dnd')
}

export function deleteNotifyPreference(id) {
  return request.delete(`/notify/preference/${id}`)
}
