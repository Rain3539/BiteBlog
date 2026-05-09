import request from './request'

export function getTimeline(params) {
  return request.get('/feed/timeline', { params })
}

export function getNearbyPosts(params) {
  return request.get('/feed/nearby', { params })
}
