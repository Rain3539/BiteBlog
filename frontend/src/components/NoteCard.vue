<template>
  <el-card class="note-card" shadow="hover" :body-style="{ padding: '0' }" @click="$router.push(`/post/${note.postId || note.noteId}`)">
    <div class="cover-wrap">
      <img v-if="coverUrl" :src="coverUrl" class="cover-img" />
      <div v-else class="cover-placeholder">
        <el-icon :size="32"><Food /></el-icon>
      </div>
      <span v-if="note.reason" class="reason-tag">{{ note.reason }}</span>
    </div>
    <div class="info">
      <h4 class="title">{{ note.title }}</h4>
      <div v-if="note.shopName" class="shop"><el-icon><Shop /></el-icon> {{ note.shopName }}</div>
      <div v-if="note.tags?.length" class="tags">
        <el-tag v-for="t in note.tags.slice(0, 3)" :key="t" size="small" type="info" effect="plain">{{ t }}</el-tag>
      </div>
      <div v-if="note.distance != null" class="distance"><el-icon><Location /></el-icon> {{ formatDistance(note.distance) }}</div>
      <div class="meta">
        <span><el-icon><Star /></el-icon> {{ note.likeCount || 0 }}</span>
        <span><el-icon><ChatDotRound /></el-icon> {{ note.commentCount || 0 }}</span>
        <span v-if="note.collectCount != null"><el-icon><Collection /></el-icon> {{ note.collectCount }}</span>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import { Food, Shop, Location, Star, ChatDotRound, Collection } from '@element-plus/icons-vue'

const props = defineProps({ note: { type: Object, required: true } })

const coverUrl = computed(() => props.note.coverUrl || (props.note.images?.length ? props.note.images[0] : null))

function formatDistance(d) {
  if (d == null) return ''
  return d < 1 ? `${Math.round(d * 1000)}m` : `${d.toFixed(1)}km`
}
</script>

<style scoped>
.note-card { cursor: pointer; border-radius: 12px; overflow: hidden; transition: transform 0.2s; }
.note-card:hover { transform: translateY(-4px); }
.cover-wrap { position: relative; width: 100%; height: 200px; overflow: hidden; background: #f5f7fa; }
.cover-img { width: 100%; height: 100%; object-fit: cover; }
.cover-placeholder { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; color: #c0c4cc; background: linear-gradient(135deg, #f5f7fa 0%, #e8eaed 100%); }
.reason-tag { position: absolute; bottom: 8px; left: 8px; background: rgba(0,0,0,0.6); color: #fff; font-size: 12px; padding: 2px 8px; border-radius: 4px; }
.info { padding: 12px; }
.title { margin: 0 0 6px; font-size: 14px; color: #303133; line-height: 1.4; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.shop { font-size: 12px; color: #67c23a; display: flex; align-items: center; gap: 4px; margin-bottom: 6px; }
.tags { display: flex; gap: 4px; flex-wrap: wrap; margin-bottom: 6px; }
.distance { font-size: 12px; color: #909399; display: flex; align-items: center; gap: 4px; margin-bottom: 6px; }
.meta { display: flex; gap: 12px; font-size: 12px; color: #909399; }
.meta span { display: flex; align-items: center; gap: 2px; }
</style>
