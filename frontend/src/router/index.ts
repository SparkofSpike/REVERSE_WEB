import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' }
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { public: true, title: '注册' }
    },
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: '作战室' }
    },
    {
      path: '/battle/:battleId?',
      name: 'battle',
      component: () => import('@/views/BattleView.vue'),
      meta: { title: '战斗' }
    },
    {
      path: '/pvp',
      name: 'pvp',
      component: () => import('@/views/PvpLobbyView.vue'),
      meta: { title: '房间大厅' }
    },
    {
      path: '/builds',
      name: 'builds',
      component: () => import('@/views/BuildsView.vue'),
      meta: { title: '构筑管理' }
    },
    {
      path: '/records',
      name: 'records',
      component: () => import('@/views/RecordsView.vue'),
      meta: { title: '战报' }
    },
    {
      path: '/records/:id',
      name: 'record-detail',
      component: () => import('@/views/RecordDetailView.vue'),
      meta: { title: '战报详情' }
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { title: '编辑资料' }
    },
    {
      path: '/design',
      name: 'design',
      component: () => import('@/views/DesignView.vue'),
      meta: { title: '设计管理', requiresAdmin: true }
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/views/AdminUsersView.vue'),
      meta: { title: '权限管理', requiresOp: true }
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login' }
  }
  if (to.meta.public && auth.isLoggedIn) {
    return { name: 'home' }
  }
  // role-guarded routes: the nav menu hides them, the guard is the backstop
  if (to.meta.requiresOp && !auth.isOp) {
    return { name: 'home' }
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'home' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - TEST 战斗辅助` : 'TEST 战斗辅助'
})

export default router
