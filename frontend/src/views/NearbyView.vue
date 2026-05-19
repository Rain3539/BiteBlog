<template>
  <div class="nearby-page">
    <div class="nearby-toolbar">
      <div>
        <h2>附近探店</h2>
        <p>基于位置发现身边的美食笔记</p>
      </div>
    </div>

    <!-- 模式切换 -->
    <el-radio-group v-model="mode" size="large" class="mode-switch">
      <el-radio-button value="markers">附近笔记</el-radio-button>
      <el-radio-button value="poi">搜索地点</el-radio-button>
    </el-radio-group>

    <!-- 附近笔记 -->
    <template v-if="mode === 'markers'">
      <div class="markers-control">
        <div class="control-row">
          <div class="control-item">
            <span class="control-label">经度</span>
            <el-input-number v-model="lng" :precision="4" :step="0.01" controls-position="right" />
          </div>
          <div class="control-item">
            <span class="control-label">纬度</span>
            <el-input-number v-model="lat" :precision="4" :step="0.01" controls-position="right" />
          </div>
          <div class="control-item">
            <span class="control-label">半径</span>
            <el-select v-model="radius" style="width:120px">
              <el-option v-for="r in radiusOptions" :key="r.value" :label="r.label" :value="r.value" />
            </el-select>
          </div>
          <el-button type="primary" :loading="markerLoading" :icon="Search" @click="searchMarkers">
            查询
          </el-button>
          <el-button :icon="Aim" @click="locateMe">定位</el-button>
        </div>
        <p class="control-hint">默认武汉中山公园附近，点击"定位"使用浏览器位置</p>
      </div>

      <div v-loading="markerLoading" class="note-grid">
        <template v-if="markers.length">
          <NoteCard v-for="item in markers" :key="item.noteId" :note="item" />
        </template>
        <el-empty v-else-if="!markerLoading && searched" description="附近暂无探店笔记" />
        <el-empty v-else-if="!markerLoading && !searched" description="点击「查询」发现附近美食" />
      </div>
    </template>

    <!-- 搜索地点 -->
    <template v-else>
      <div class="poi-control">
        <div class="control-row">
          <el-input v-model="poiKeyword" placeholder="搜索地点，如：火锅" style="width:260px" clearable
            @keyup.enter="searchPoiList" />
          <el-input v-model="poiCity" placeholder="城市，如：武汉" style="width:160px" clearable
            @keyup.enter="searchPoiList" />
          <el-button type="primary" :loading="poiLoading" :icon="Search" @click="searchPoiList">
            搜索
          </el-button>
        </div>
      </div>

      <div v-loading="poiLoading" class="poi-list">
        <template v-if="poiItems.length">
          <el-card v-for="item in poiItems" :key="item.id" class="poi-card" shadow="hover">
            <div class="poi-card-body">
              <div class="poi-info">
                <h4>{{ item.name }}</h4>
                <p class="poi-address"><el-icon><Location /></el-icon> {{ item.address }}</p>
                <el-tag v-if="item.type" size="small" type="info" effect="plain">
                  {{ item.type.split(';').pop() || item.type }}
                </el-tag>
              </div>
              <div class="poi-coords">
                <span>{{ item.longitude?.toFixed(6) }}, {{ item.latitude?.toFixed(6) }}</span>
              </div>
            </div>
          </el-card>
        </template>
        <el-empty v-else-if="!poiLoading && poiSearched" description="未找到相关地点" />
        <el-empty v-else-if="!poiLoading && !poiSearched" description="输入关键词搜索周边地点" />
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Search, Aim, Location } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getNearbyMarkers } from '../api/location'
import { searchPoi } from '../api/location'
import NoteCard from '../components/NoteCard.vue'

// 模式
const mode = ref('markers')

// 附近笔记
const lng = ref(114.305)
const lat = ref(30.593)
const radius = ref(3)
const markerLoading = ref(false)
const markers = ref([])
const searched = ref(false)

const radiusOptions = [
  { label: '1km', value: 1 },
  { label: '3km', value: 3 },
  { label: '5km', value: 5 },
  { label: '10km', value: 10 },
  { label: '20km', value: 20 }
]

async function searchMarkers() {
  markerLoading.value = true
  searched.value = true
  try {
    const res = await getNearbyMarkers({
      longitude: lng.value,
      latitude: lat.value,
      radius: radius.value
    })
    markers.value = (res.data?.markers || []).map(m => ({
      ...m,
      postId: m.noteId,
      noteId: m.noteId
    }))
    if (!markers.value.length) {
      ElMessage.info('附近暂无探店笔记')
    }
  } catch {
    markers.value = []
  } finally {
    markerLoading.value = false
  }
}

function locateMe() {
  if (!navigator.geolocation) {
    ElMessage.warning('浏览器不支持定位功能')
    return
  }
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      lng.value = pos.coords.longitude
      lat.value = pos.coords.latitude
      ElMessage.success('定位成功')
      searchMarkers()
    },
    (err) => {
      const reasons = {
        1: '定位权限被拒绝，请在浏览器设置中允许访问位置信息',
        2: '无法获取位置信息，请检查系统定位服务是否已开启',
        3: '定位请求超时，请检查网络连接后重试'
      }
      ElMessage.warning(reasons[err.code] || '定位失败，请手动输入坐标')
    }
  )
}

// POI 搜索
const poiKeyword = ref('')
const poiCity = ref('')
const poiLoading = ref(false)
const poiItems = ref([])
const poiSearched = ref(false)

async function searchPoiList() {
  if (!poiKeyword.value.trim()) {
    ElMessage.warning('请输入搜索关键词')
    return
  }
  poiLoading.value = true
  poiSearched.value = true
  try {
    const res = await searchPoi({
      keyword: poiKeyword.value,
      city: poiCity.value || undefined
    })
    poiItems.value = res.data?.list || []
    if (!poiItems.value.length) {
      ElMessage.info('未找到相关地点')
    }
  } catch {
    poiItems.value = []
  } finally {
    poiLoading.value = false
  }
}
</script>

<style scoped>
.nearby-page { padding: 20px 0; }
h2 { margin: 0 0 4px; font-size: 20px; color: #303133; }
.nearby-toolbar p { margin: 0 0 16px; font-size: 13px; color: #909399; }

.mode-switch { margin-bottom: 20px; }

.markers-control, .poi-control {
  background: #f5f7fa; border-radius: 12px; padding: 16px; margin-bottom: 20px;
}
.control-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.control-item { display: flex; align-items: center; gap: 8px; }
.control-label { font-size: 13px; color: #606266; min-width: 32px; }
.control-hint { margin: 8px 0 0; font-size: 12px; color: #c0c4cc; }

.note-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
  min-height: 200px;
}

.poi-list { display: flex; flex-direction: column; gap: 12px; min-height: 200px; }

.poi-card { cursor: default; border-radius: 12px; }
.poi-card-body { display: flex; justify-content: space-between; align-items: flex-start; }
.poi-info h4 { margin: 0 0 6px; font-size: 15px; color: #303133; }
.poi-address { margin: 0 0 6px; font-size: 13px; color: #909399; display: flex; align-items: center; gap: 4px; }
.poi-coords { font-size: 12px; color: #c0c4cc; white-space: nowrap; }
</style>
