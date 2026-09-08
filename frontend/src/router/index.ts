import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, guestOnly: true, title: '登录' }
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { public: true, guestOnly: true, title: '注册' }
    },
    // Public portal: the website root. TEST battle system is one module of it;
    // click-through lands on /test (name 'home'). Keep meta.title empty so the
    // document title stays the bare site name.
    {
      path: '/',
      name: 'portal',
      component: () => import('@/views/PortalView.vue'),
      meta: { public: true }
    },
    // TEST battle system (requires login). Route names stay stable so every
    // router.push({ name }) across the app keeps working under the /test prefix.
    {
      path: '/test',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: '作战室' }
    },
    {
      path: '/test/battle/:battleId?',
      name: 'battle',
      component: () => import('@/views/BattleView.vue'),
      meta: { title: '战斗' }
    },
    {
      path: '/test/pvp',
      name: 'pvp',
      component: () => import('@/views/PvpLobbyView.vue'),
      meta: { title: '房间大厅' }
    },
    {
      path: '/test/builds',
      name: 'builds',
      component: () => import('@/views/BuildsView.vue'),
      meta: { title: '构筑管理' }
    },
    {
      path: '/test/records',
      name: 'records',
      component: () => import('@/views/RecordsView.vue'),
      meta: { title: '战报' }
    },
    {
      path: '/test/records/:id',
      name: 'record-detail',
      component: () => import('@/views/RecordDetailView.vue'),
      meta: { title: '战报详情' }
    },
    {
      path: '/test/profile',
      name: 'profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { title: '编辑资料' }
    },
    {
      path: '/test/design',
      name: 'design',
      component: () => import('@/views/DesignView.vue'),
      meta: { title: '设计管理', requiresAdmin: true }
    },
    {
      path: '/test/admin/users',
      name: 'admin-users',
      component: () => import('@/views/AdminUsersView.vue'),
      meta: { title: '权限管理', requiresOp: true }
    },
    // King's Chess — module under design. Skeleton board only for now; the
    // route is login-gated like the TEST module.
    {
      path: '/chess',
      name: 'chess',
      component: () => import('@/views/ChessView.vue'),
      meta: { title: '国王棋' }
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  // Guest-only pages (login/register) bounce signed-in users to the battle system.
  if (to.meta.guestOnly && auth.isLoggedIn) {
    return { name: 'home' }
  }
  // Everything under /test/* (and other protected routes) requires a session;
  // the public portal at '/' stays reachable either way.
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login' }
  }
  // Enforce role-based route access.
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
  document.title = title ? `${title} - TEST 战斗辅助` : 'Reverse_Web'
})

export default router
