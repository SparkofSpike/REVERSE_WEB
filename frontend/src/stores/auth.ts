import { defineStore } from 'pinia'

export type Role = 'USER' | 'ADMIN' | 'OP'

interface AuthState {
  token: string
  userId: number | null
  username: string
  role: Role
  nickname: string | null
  avatarUrl: string | null
  theme: 'dark' | 'light'
}

const TOKEN_KEY = 'test_token'
const USERNAME_KEY = 'test_username'
const ROLE_KEY = 'test_role'
const NICKNAME_KEY = 'test_nickname'
const AVATAR_KEY = 'test_avatar'

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    userId: null,
    username: localStorage.getItem(USERNAME_KEY) || '',
    role: (localStorage.getItem(ROLE_KEY) as Role) || 'USER',
    nickname: localStorage.getItem(NICKNAME_KEY),
    avatarUrl: localStorage.getItem(AVATAR_KEY),
    theme: 'dark'
  }),
  getters: {
    isLoggedIn: (state) => state.token !== '',
    /** OP always implies admin powers. */
    isAdmin: (state) => state.role !== 'USER',
    isOp: (state) => state.role === 'OP',
    /** Display name with username fallback. */
    displayName: (state) => state.nickname || state.username
  },
  actions: {
    setAuth(
      token: string,
      username: string,
      role: Role = 'USER',
      nickname: string | null = null,
      avatarUrl: string | null = null
    ) {
      this.token = token
      this.username = username
      this.role = role
      this.nickname = nickname
      this.avatarUrl = avatarUrl
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USERNAME_KEY, username)
      localStorage.setItem(ROLE_KEY, role)
      if (nickname) {
        localStorage.setItem(NICKNAME_KEY, nickname)
      } else {
        localStorage.removeItem(NICKNAME_KEY)
      }
      if (avatarUrl) {
        localStorage.setItem(AVATAR_KEY, avatarUrl)
      } else {
        localStorage.removeItem(AVATAR_KEY)
      }
    },
    setUserInfo(
      userId: number | null,
      role: Role,
      nickname: string | null,
      avatarUrl: string | null = null
    ) {
      this.userId = userId
      this.role = role
      this.nickname = nickname
      this.avatarUrl = avatarUrl
      localStorage.setItem(ROLE_KEY, role)
      if (nickname) {
        localStorage.setItem(NICKNAME_KEY, nickname)
      } else {
        localStorage.removeItem(NICKNAME_KEY)
      }
      if (avatarUrl) {
        localStorage.setItem(AVATAR_KEY, avatarUrl)
      } else {
        localStorage.removeItem(AVATAR_KEY)
      }
    },
    logout() {
      this.token = ''
      this.userId = null
      this.username = ''
      this.role = 'USER'
      this.nickname = null
      this.avatarUrl = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USERNAME_KEY)
      localStorage.removeItem(ROLE_KEY)
      localStorage.removeItem(NICKNAME_KEY)
      localStorage.removeItem(AVATAR_KEY)
    }
  }
})
