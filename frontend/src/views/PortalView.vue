<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

// Portal entry: signed-in users go straight to the battle system, guests are
// routed through the login page first.
function enter() {
  router.push({ name: auth.isLoggedIn ? 'home' : 'login' })
}
</script>

<template>
  <div class="portal">
    <div class="portal-center">
      <div class="portal-kicker">TABLE-TOP COMBAT TERMINAL</div>
      <h1 class="portal-title">
        TEST<span class="portal-title-cn">战斗辅助</span>
      </h1>
      <p class="portal-sub">赛博桌游战斗裁决终端</p>
      <button class="enter-btn" type="button" @click="enter">
        进入 TEST 战斗系统
      </button>
    </div>
  </div>
</template>

<style scoped>
.portal {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  overflow: hidden;
  position: relative;
  background:
    /* neon corner glows */
    radial-gradient(ellipse at 18% 12%, rgba(76, 194, 255, 0.14), transparent 46%),
    radial-gradient(ellipse at 82% 88%, rgba(255, 93, 108, 0.1), transparent 46%),
    /* subtle cyber grid */
    linear-gradient(rgba(76, 194, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(76, 194, 255, 0.045) 1px, transparent 1px),
    var(--bg);
  background-size:
    auto,
    auto,
    52px 52px,
    52px 52px,
    auto;
}

.portal-center {
  text-align: center;
  max-width: 640px;
  animation: fade-up 0.5s ease both;
}

.portal-kicker {
  font-size: 12px;
  letter-spacing: 6px;
  color: var(--text-dim);
  text-transform: uppercase;
  margin-bottom: 18px;
}

.portal-title {
  font-size: 72px;
  font-weight: 800;
  letter-spacing: 10px;
  line-height: 1;
  color: var(--accent);
  text-shadow:
    0 0 18px rgba(76, 194, 255, 0.45),
    0 0 60px rgba(76, 194, 255, 0.18);
}

.portal-title-cn {
  font-size: 30px;
  font-weight: 600;
  letter-spacing: 4px;
  color: var(--text);
  text-shadow: none;
  margin-left: 14px;
}

.portal-sub {
  margin-top: 16px;
  font-size: 15px;
  letter-spacing: 2px;
  color: var(--text-dim);
}

.enter-btn {
  margin-top: 44px;
  padding: 14px 48px;
  font-size: 16px;
  letter-spacing: 3px;
  color: var(--accent);
  background: rgba(76, 194, 255, 0.06);
  border: 1px solid var(--accent);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.18s ease;
  box-shadow:
    0 0 20px rgba(76, 194, 255, 0.18),
    inset 0 0 14px rgba(76, 194, 255, 0.06);
}

.enter-btn:hover {
  color: #fff;
  background: var(--accent-dim);
  box-shadow:
    0 0 34px rgba(76, 194, 255, 0.45),
    inset 0 0 18px rgba(255, 255, 255, 0.12);
}

.enter-btn:active {
  transform: translateY(1px);
}

@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* ---------- mobile ---------- */
@media (max-width: 768px) {
  .portal-title {
    font-size: 44px;
    letter-spacing: 6px;
  }
  .portal-title-cn {
    font-size: 22px;
    letter-spacing: 2px;
    margin-left: 8px;
  }
  .portal-kicker {
    letter-spacing: 4px;
    font-size: 10px;
  }
  .enter-btn {
    padding: 13px 30px;
    font-size: 14px;
  }
}
</style>
