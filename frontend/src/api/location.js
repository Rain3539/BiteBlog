import request from './request'

export function searchPoi(params) {
  return request.get('/location/poi/search', { params })
}

export function getNearbyMarkers(params) {
  return request.get('/location/nearby/markers', { params })
}
