import request from './request'

export function publishPost(data) {
  return request.post('/post/publish', data)
}

export function getPostDetail(id) {
  return request.get(`/post/${id}`)
}

export function likePost(id) {
  return request.post(`/post/${id}/like`)
}

export function favoritePost(id) {
  return request.post(`/post/${id}/favorite`)
}

export function commentPost(id, data) {
  return request.post(`/post/${id}/comment`, data)
}

export function getPostComments(id, params) {
  return request.get(`/post/${id}/comments`, { params })
}

export function deletePost(id) {
  return request.delete(`/post/${id}`)
}
