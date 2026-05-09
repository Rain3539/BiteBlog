import request from './request'

export function getDiscoverList(params) {
  return request.get('/recommend/discover', { params })
}
