<script setup lang="ts">
import AppNav from '@/components/AppNav.vue'

// Static skeleton for the Ranzhong Dui board: four 4-cell Fields around the
// central Court, plus a Rack zone. Pure layout — adjudication is backend-only
// and not wired up yet; the module is under design.
const northCells = [0, 1, 2, 3]
const eastCells = [0, 1, 2, 3]
const southCells = [0, 1, 2, 3]
const westCells = [0, 1, 2, 3]

function cellIndex(n: number) {
  return n + 1
}
</script>

<template>
  <div class="page">
    <AppNav />
    <main class="container">
      <header class="header">
        <div>
          <h1>国王棋 · 岚中对</h1>
          <p class="dim">轮转式棋盘 — 四列四格棋场环绕中央棋局</p>
        </div>
        <span class="phase-badge">开发中</span>
      </header>

      <div class="board">
        <!-- North field -->
        <div class="row row-north">
          <span class="arrow">↖</span>
          <div class="field field-h">
            <div v-for="n in northCells" :key="n" class="cell">{{ cellIndex(n) }}</div>
          </div>
          <span class="arrow">↘</span>
        </div>

        <!-- Middle row: West field | Court | East field -->
        <div class="row row-mid">
          <div class="field field-v">
            <div v-for="n in westCells" :key="n" class="cell">{{ cellIndex(n) }}</div>
          </div>
          <div class="court">
            <span class="court-label">棋局</span>
            <span class="court-sub dim">公棋池</span>
          </div>
          <div class="field field-v">
            <div v-for="n in eastCells" :key="n" class="cell">{{ cellIndex(n) }}</div>
          </div>
        </div>

        <!-- South field -->
        <div class="row row-south">
          <span class="arrow">↙</span>
          <div class="field field-h">
            <div v-for="n in southCells" :key="n" class="cell">{{ cellIndex(n) }}</div>
          </div>
          <span class="arrow">↘</span>
        </div>
      </div>

      <div class="rack">
        <span class="rack-label">格栏（个人区域 · 经典对局不用）</span>
      </div>

      <p class="footnote dim">
        此处为棋盘骨架预览。对局裁决（d20 比速、同格吃子、计分、胜负）均在服务端实现，
        前端仅展示与转发；本模块仍处设计阶段。
      </p>
    </main>
  </div>
</template>

<style scoped>
.page {
  min-height: 100%;
  background: var(--bg);
}

.container {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header h1 {
  font-size: 22px;
  letter-spacing: 2px;
}

.phase-badge {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--warn);
  border: 1px solid var(--warn);
  border-radius: 4px;
  padding: 3px 10px;
}

.board {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background:
    linear-gradient(rgba(76, 194, 255, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(76, 194, 255, 0.03) 1px, transparent 1px),
    var(--bg-panel);
  background-size: 28px 28px, 28px 28px, auto;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.field {
  display: flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-panel-2);
}

.field-h {
  flex-direction: row;
}

.field-v {
  flex-direction: column;
}

.cell {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-dim);
  border: 1px solid var(--border);
  border-radius: 4px;
  background: rgba(11, 14, 20, 0.6);
}

.arrow {
  font-size: 22px;
  color: var(--accent);
  padding: 0 8px;
}

.court {
  width: 180px;
  height: 180px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--accent);
  border-radius: 8px;
  background: rgba(76, 194, 255, 0.08);
}

.court-label {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 4px;
  color: var(--accent);
}

.court-sub {
  font-size: 12px;
  letter-spacing: 2px;
}

.rack {
  border: 1px dashed var(--border);
  border-radius: 8px;
  padding: 14px;
  text-align: center;
}

.rack-label {
  font-size: 13px;
  color: var(--text-dim);
  letter-spacing: 1px;
}

.footnote {
  font-size: 12px;
  line-height: 1.7;
}

/* ---------- mobile ---------- */
@media (max-width: 768px) {
  .container {
    padding: 16px;
  }
  .cell {
    width: 44px;
    height: 44px;
    font-size: 14px;
  }
  .court {
    width: 120px;
    height: 120px;
  }
  .arrow {
    font-size: 16px;
    padding: 0 4px;
  }
}
</style>
