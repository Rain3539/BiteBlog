<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2 style="text-align:center;margin-bottom:24px">BiteBlog 登录</h2>
      <el-form :model="form" label-width="0">
        <el-form-item>
          <el-input v-model="form.phone" placeholder="手机号" prefix-icon="Phone" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" prefix-icon="Lock" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" style="width:100%" @click="handleLogin" :loading="loading">登录</el-button>
        </el-form-item>
        <div style="text-align:center">
          <el-link @click="isRegister = !isRegister">{{ isRegister ? '已有账号？去登录' : '没有账号？去注册' }}</el-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, register } from '../api/user'
import { setToken } from '../utils/auth'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const isRegister = ref(false)
const form = reactive({ phone: '', password: '', username: '' })

async function handleLogin() {
  if (!form.phone || !form.password) {
    ElMessage.warning('请填写完整信息')
    return
  }
  loading.value = true
  try {
    const api = isRegister.value ? register : login
    const res = await api(form)
    setToken(res.data.token)
    userStore.setUserInfo(res.data)
    ElMessage.success(isRegister.value ? '注册成功' : '登录成功')
    router.push('/')
  } catch (e) {
    // handled by interceptor
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container { display:flex; justify-content:center; align-items:center; height:100vh; background:#f5f7fa; }
.login-card { width:400px; }
</style>
