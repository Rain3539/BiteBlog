import request from './request'

export function getDiscoverList(params) {
  return request.get('/recommend/discover', { params })
}

export function saveRecommendExposures(postIds) {
  return request.post('/recommend/exposures', { postIds })
}

export function getRecommendHealth() {
  return request.get('/recommend/health')
}
