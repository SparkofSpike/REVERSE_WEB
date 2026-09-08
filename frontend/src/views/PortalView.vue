<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const message = useMessage()
const auth = useAuthStore()

// TEST battle system entry: signed-in users go straight to the battle system,
// guests are routed through the login page first.
function enterTest() {
  router.push({ name: auth.isLoggedIn ? 'home' : 'login' })
}

// Placeholder module (King's Chess) — not implemented yet, nothing to route to.
function enterKing() {
  message.info('国王棋 · 开发中，敬请期待')
}
</script>

<template>
  <div class="portal">
    <div class="portal-center">
      <div class="portal-kicker">REVERSE PROJECT</div>
      <h1 class="portal-title">Reverse_Web</h1>
      <p class="portal-sub">Reverse 企划官方网站</p>

      <div class="portal-actions">
        <button class="entry-card" type="button" @click="enterTest">
          <span class="entry-name">TEST 战斗系统</span>
          <span class="entry-desc">赛博桌游战斗裁决终端</span>
          <span class="entry-cta">进入 →</span>
        </button>
        <button class="entry-card coming" type="button" @click="enterKing">
          <span class="entry-name">国王棋</span>
          <span class="entry-desc">敬请期待</span>
          <span class="entry-cta">即将上线</span>
        </button>
      </div>
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
  max-width: 720px;
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
  font-size: 76px;
  font-weight: 800;
  letter-spacing: 4px;
  line-height: 1;
  color: var(--accent);
  text-shadow:
    0 0 18px rgba(76, 194, 255, 0.45),
    0 0 60px rgba(76, 194, 255, 0.18);
}

.portal-sub {
  margin-top: 16px;
  font-size: 15px;
  letter-spacing: 3px;
  color: var(--text-dim);
}

.portal-actions {
  margin-top: 48px;
  display: flex;
  gap: 16px;
  justify-content: center;
  flex-wrap: wrap;
}

.entry-card {
  width: 260px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px 20px;
  background: rgba(76, 194, 255, 0.05);
  border: 1px solid var(--accent);
  border-radius: 10px;
  color: var(--text);
  cursor: pointer;
  transition: all 0.18s ease;
  box-shadow:
    0 0 20px rgba(76, 194, 255, 0.12),
    inset 0 0 14px rgba(76, 194, 255, 0.04);
}

.entry-card:hover {
  background: rgba(76, 194, 255, 0.12);
  box-shadow:
    0 0 34px rgba(76, 194, 255, 0.35),
    inset 0 0 18px rgba(76, 194, 255, 0.08);
  transform: translateY(-2px);
}

.entry-name {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 3px;
  color: var(--accent);
}

.entry-desc {
  font-size: 12px;
  letter-spacing: 1px;
  color: var(--text-dim);
}

.entry-cta {
  margin-top: 6px;
  font-size: 13px;
  letter-spacing: 2px;
  color: var(--text);
  opacity: 0.85;
}

/* placeholder module: dimmed, no interactive affordance */
.entry-card.coming {
  background: rgba(139, 150, 171, 0.04);
  border-color: var(--border);
  box-shadow: none;
}

.entry-card.coming:hover {
  background: rgba(139, 150, 171, 0.08);
  box-shadow: none;
  transform: none;
}

.entry-card.coming .entry-name {
  color: var(--text-dim);
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
    font-size: 46px;
    letter-spacing: 2px;
  }
  .portal-kicker {
    letter-spacing: 4px;
    font-size: 10px;
  }
  .portal-sub {
    letter-spacing: 2px;
    font-size: 13px;
  }
  .portal-actions {
    flex-direction: column;
    align-items: center;
    margin-top: 36px;
  }
  .entry-card {
    width: min(300px, 100%);
  }
}
</style>
