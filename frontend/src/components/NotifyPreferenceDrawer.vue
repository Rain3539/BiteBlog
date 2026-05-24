<template>
  <el-drawer
    v-model="visible"
    title="通知设置"
    direction="rtl"
    size="360px"
    :destroy-on-close="false"
    @open="loadPreferences"
  >
    <div v-loading="loading" class="pref-drawer">
      <section class="pref-section">
        <h4 class="pref-title">接收的通知类型</h4>
        <p class="pref-desc">关闭后，对应类型的通知将不再写入列表</p>
        <div class="pref-checks">
          <div v-for="item in typeOptions" :key="item.type" class="type-row">
            <span>{{ item.label }}</span>
            <el-switch
              :model-value="item.receive"
              :disabled="saving"
              @change="(v) => onReceiveToggle(item.type, v)"
            />
          </div>
        </div>
      </section>

      <el-divider />

      <section class="pref-section">
        <h4 class="pref-title">屏蔽用户</h4>
        <p class="pref-desc">被屏蔽用户的互动将不再产生通知</p>
        <div class="sender-add">
          <el-input
            v-model="newSenderId"
            placeholder="输入用户 ID"
            clearable
            :disabled="saving"
            @keyup.enter="addMutedSender"
          />
          <el-button type="primary" :loading="addingSender" :disabled="saving" @click="addMutedSender">
            添加
          </el-button>
        </div>
        <el-empty v-if="!mutedSenders.length" description="暂无屏蔽用户" :image-size="56" />
        <ul v-else class="sender-list">
          <li v-for="item in mutedSenders" :key="item.id" class="sender-item">
            <span class="sender-name">{{ item.label }}</span>
            <el-button
              type="danger"
              link
              size="small"
              :disabled="saving"
              @click="removePreference(item.id)"
            >
              移除
            </el-button>
          </li>
        </ul>
      </section>

      <el-divider />

      <section class="pref-section">
        <div class="dnd-header">
          <div>
            <h4 class="pref-title">勿扰时段</h4>
            <p class="pref-desc">时段内通知仍保存，但不实时推送、不增加未读角标</p>
          </div>
          <el-switch
            v-model="dndEnabled"
            :disabled="saving"
            @change="onDndToggle"
          />
        </div>
        <div v-if="dndEnabled" class="dnd-times">
          <el-time-select
            v-model="dndStart"
            start="00:00"
            step="00:30"
            end="23:30"
            placeholder="开始"
            :disabled="saving"
            @change="saveDnd"
          />
          <span class="dnd-sep">至</span>
          <el-time-select
            v-model="dndEnd"
            start="00:00"
            step="00:30"
            end="23:30"
            placeholder="结束"
            :disabled="saving"
            @change="saveDnd"
          />
        </div>
      </section>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getNotifyPreferences,
  muteNotifyType,
  muteNotifySender,
  setNotifyDnd,
  clearNotifyDnd,
  deleteNotifyPreference
} from '../api/notify'
import { getUserProfile } from '../api/user'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const loading = ref(false)
const saving = ref(false)
const addingSender = ref(false)

const prefs = ref([])

const typeOptions = computed(() => {
  const muted = (type) => !!prefs.value.find((p) => p.prefType === 'mute_type' && p.prefValue === type)
  return [
    { type: 'like', label: '点赞通知', receive: !muted('like') },
    { type: 'collect', label: '收藏通知', receive: !muted('collect') },
    { type: 'comment', label: '评论通知', receive: !muted('comment') },
    { type: 'follow_post', label: '关注者发帖通知', receive: !muted('follow_post') }
  ]
})

const mutedSenders = ref([])
const newSenderId = ref('')

const dndEnabled = ref(false)
const dndStart = ref('22:00')
const dndEnd = ref('08:00')

function findMuteType(type) {
  return prefs.value.find((p) => p.prefType === 'mute_type' && p.prefValue === type)
}

function syncFromPrefs() {
  const dnd = prefs.value.find((p) => p.prefType === 'dnd_time')
  if (dnd?.prefValue?.includes('-')) {
    dndEnabled.value = true
    const [s, e] = dnd.prefValue.split('-')
    dndStart.value = s?.trim() || '22:00'
    dndEnd.value = e?.trim() || '08:00'
  } else {
    dndEnabled.value = false
  }
}

async function resolveSenderLabels(senderPrefs) {
  const items = await Promise.all(
    senderPrefs.map(async (p) => {
      const id = Number(p.prefValue)
      let label = `用户 ${p.prefValue}`
      if (id) {
        try {
          const res = await getUserProfile(id)
          if (res.data?.username) {
            label = `${res.data.username}（ID ${id}）`
          }
        } catch {
          /* 保留默认标签 */
        }
      }
      return { id: p.id, senderId: p.prefValue, label }
    })
  )
  mutedSenders.value = items
}

async function loadPreferences() {
  loading.value = true
  try {
    const res = await getNotifyPreferences()
    prefs.value = res.data || []
    syncFromPrefs()
    const senderPrefs = prefs.value.filter((p) => p.prefType === 'mute_sender')
    await resolveSenderLabels(senderPrefs)
  } catch {
    prefs.value = []
    mutedSenders.value = []
  } finally {
    loading.value = false
  }
}

async function onReceiveToggle(type, receive) {
  saving.value = true
  try {
    if (receive) {
      const p = findMuteType(type)
      if (p?.id) {
        await deleteNotifyPreference(p.id)
        ElMessage.success(`已恢复${typeLabel(type)}通知`)
      }
    } else {
      await muteNotifyType(type)
      ElMessage.success(`已关闭${typeLabel(type)}通知`)
    }
    await loadPreferences()
    emit('changed')
  } catch {
    /* prefs 未变，computed 自动回显 */
  } finally {
    saving.value = false
  }
}

function typeLabel(type) {
  return { like: '点赞', collect: '收藏', comment: '评论', follow_post: '关注者发帖' }[type] || type
}

async function addMutedSender() {
  const id = Number(String(newSenderId.value).trim())
  if (!id || id <= 0) {
    ElMessage.warning('请输入有效的用户 ID')
    return
  }
  if (mutedSenders.value.some((s) => Number(s.senderId) === id)) {
    ElMessage.info('该用户已在屏蔽列表中')
    return
  }
  addingSender.value = true
  try {
    await muteNotifySender(id)
    newSenderId.value = ''
    ElMessage.success('已屏蔽该用户')
    await loadPreferences()
    emit('changed')
  } catch {
    /* axios 拦截器已提示 */
  } finally {
    addingSender.value = false
  }
}

/** 供通知列表「屏蔽此人」快捷调用 */
async function muteSenderById(senderId) {
  if (!senderId) return
  saving.value = true
  try {
    await muteNotifySender(senderId)
    ElMessage.success('已屏蔽该用户')
    if (visible.value) {
      await loadPreferences()
    }
    emit('changed')
  } catch {
    /* noop */
  } finally {
    saving.value = false
  }
}

async function removePreference(id) {
  saving.value = true
  try {
    await deleteNotifyPreference(id)
    ElMessage.success('已移除')
    await loadPreferences()
    emit('changed')
  } catch {
    /* noop */
  } finally {
    saving.value = false
  }
}

async function onDndToggle(enabled) {
  saving.value = true
  try {
    if (enabled) {
      await saveDnd()
      ElMessage.success('勿扰时段已开启')
    } else {
      await clearNotifyDnd()
      ElMessage.success('勿扰时段已关闭')
    }
    await loadPreferences()
    emit('changed')
  } catch {
    dndEnabled.value = !enabled
  } finally {
    saving.value = false
  }
}

async function saveDnd() {
  if (!dndStart.value || !dndEnd.value) return
  saving.value = true
  try {
    await setNotifyDnd(`${dndStart.value}-${dndEnd.value}`)
    await loadPreferences()
    emit('changed')
  } catch {
    /* noop */
  } finally {
    saving.value = false
  }
}

defineExpose({ muteSenderById, loadPreferences })
</script>

<style scoped>
.pref-drawer {
  padding: 0 4px 24px;
}
.pref-section {
  margin-bottom: 4px;
}
.pref-title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
}
.pref-desc {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.pref-checks {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.type-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
}
.sender-add {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.sender-add .el-input {
  flex: 1;
}
.sender-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.sender-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.sender-item:last-child {
  border-bottom: none;
}
.sender-name {
  font-size: 14px;
  color: var(--el-text-color-regular);
}
.dnd-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.dnd-times {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.dnd-sep {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
