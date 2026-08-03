import { defineStore } from 'pinia'

interface AuthState {
  token: string
  username: string
  theme: 'dark' | 'light'
}

const TOKEN_KEY = 'test_token'
const USERNAME_KEY = 'test_username'

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    username: localStorage.getItem(USERNAME_KEY) || '',
    theme: 'dark'
  }),
  getters: {
    isLoggedIn: (state) => state.token !== ''
  },
  actions: {
    setAuth(token: string, username: string) {
      this.token = token
      this.username = username
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USERNAME_KEY, username)
    },
    logout() {
      this.token = ''
      this.username = ''
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USERNAME_KEY)
    }
  }
})
