import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUser, setUser, getToken } from '../utils/auth'

export const useUserStore = defineStore('user', () => {
  const userInfo = ref(getUser())
  const isLoggedIn = ref(!!getToken())

  function setUserInfo(info) {
    userInfo.value = info
    isLoggedIn.value = true
    setUser(info)
  }

  function clearUser() {
    userInfo.value = null
    isLoggedIn.value = false
  }

  return { userInfo, isLoggedIn, setUserInfo, clearUser }
})
