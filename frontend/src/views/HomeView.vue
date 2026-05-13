<template>
  <div class="home-page">
    <h3>关注</h3>

    <div v-loading="loading && !notes.length" class="note-grid">
      <template v-if="notes.length">
        <NoteCard v-for="item in notes" :key="item.postId" :note="item" />
      </template>
      <el-empty v-else-if="!loading" description="暂无内容，去关注一些探店达人吧" />
    </div>

    <div v-if="hasMore && notes.length" class="load-more">
      <el-button :loading="loading" @click="loadMore">加载更多</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getTimeline } from '../api/feed'
import NoteCard from '../components/NoteCard.vue'

const notes = ref([])
const cursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)

async function fetchData(reset) {
  if (loading.value) return
  loading.value = true
  try {
    const params = { size: 20 }
    if (!reset && cursor.value) params.cursor = cursor.value
    const res = await getTimeline(params)
    const data = res.data
    if (reset) {
      notes.value = data.list || []
    } else {
      notes.value.push(...(data.list || []))
    }
    cursor.value = data.cursor
    hasMore.value = data.hasMore
  } catch {
    /* interceptor handled */
  } finally {
    loading.value = false
  }
}

function loadMore() {
  fetchData(false)
}

onMounted(() => fetchData(true))
</script>

<style scoped>
.home-page { padding: 20px 0; }
h3 { margin: 0 0 20px; font-size: 20px; color: #303133; }
.note-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; min-height: 200px; }
.load-more { text-align: center; margin-top: 24px; }
</style>
