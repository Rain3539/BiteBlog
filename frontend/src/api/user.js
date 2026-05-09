import request from './request'

export function login(data) {
  return request.post('/user/login', data)
}

export function register(data) {
  return request.post('/user/register', data)
}

export function getUserProfile(id) {
  return request.get(`/user/${id}`)
}

export function followUser(id) {
  return request.post(`/user/follow/${id}`)
}

export function getFollowingList(id, params) {
  return request.get(`/user/${id}/following`, { params })
}

export function getFollowersList(id, params) {
  return request.get(`/user/${id}/followers`, { params })
}
