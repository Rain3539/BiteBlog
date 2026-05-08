import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('../components/layout/AppLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'Home', component: () => import('../views/HomeView.vue') },
      { path: 'discover', name: 'Discover', component: () => import('../views/DiscoverView.vue') },
      { path: 'publish', name: 'Publish', component: () => import('../views/PublishView.vue') },
      { path: 'post/:id', name: 'PostDetail', component: () => import('../views/PostDetailView.vue') },
      { path: 'nearby', name: 'Nearby', component: () => import('../views/NearbyView.vue') },
      { path: 'profile/:id?', name: 'Profile', component: () => import('../views/ProfileView.vue') },
      { path: 'notify', name: 'Notify', component: () => import('../views/NotifyView.vue') },
      { path: 'admin/dashboard', name: 'AdminDashboard', component: () => import('../views/admin/DashboardView.vue') }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('biteblog_token')
  if (to.meta.requiresAuth !== false && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
