import request from './request'

// ==================== 图片上传 ====================

export function uploadImage(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/post/upload-image', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// ==================== 笔记 CRUD ====================

export function publishPost(data) {
  return request.post('/post/publish', data)
}

export function getPostDetail(id) {
  return request.get(`/post/${id}`)
}

export function deletePost(id) {
  return request.delete(`/post/${id}`)
}

// ==================== 互动 ====================

export function likePost(id) {
  return request.post(`/post/${id}/like`)
}

export function favoritePost(id) {
  return request.post(`/post/${id}/favorite`)
}

// ==================== 评论 ====================

export function commentPost(id, data) {
  return request.post(`/post/${id}/comment`, data)
}

export function getPostComments(id, params) {
  return request.get(`/post/${id}/comments`, { params })
}

// ==================== 搜索 ====================

export function searchPosts(params) {
  return request.get('/post/search', { params })
}

export function getUserPosts(userId, params) {
  return request.get(`/post/user/${userId}`, { params })
}
