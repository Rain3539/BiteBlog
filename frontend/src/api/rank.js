import request from './request'

export function getTop10(type = 'daily') {
  return request.get('/rank/top10', { params: { type } })
}

export function getRankList(params) {
  return request.get('/rank/list', { params })
}
