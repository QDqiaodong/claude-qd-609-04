<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">射</span>
        <span class="brand-text">
          <strong>弓箭俱乐部 · 射箭馆</strong>
          <em>靶位 · 计分 · 课程 · 器材</em>
        </span>
      </div>

      <nav class="tabs">
        <button
          v-for="item in navItems"
          :key="item.key"
          type="button"
          class="tab"
          :class="{ 'is-on': activeKey === item.key }"
          :title="item.hint"
          @click="go(item)"
        >
          <span class="tab-ico">{{ item.icon }}</span>
          <span class="tab-label">{{ item.label }}</span>
          <em v-if="badge(item) !== null" class="tab-badge">{{ badge(item) }}{{ item.unit }}</em>
        </button>

        <i class="tab-split" />

        <button
          type="button"
          class="tab tab-side"
          :class="{ 'is-on': activeKey === memberItem.key }"
          :title="memberItem.hint"
          @click="go(memberItem)"
        >
          <span class="tab-ico">{{ memberItem.icon }}</span>
          <span class="tab-label">{{ memberItem.label }}</span>
          <em v-if="badge(memberItem) !== null" class="tab-badge">{{ badge(memberItem) }}{{ memberItem.unit }}</em>
        </button>
      </nav>

      <div class="topbar-right">
        <span class="dot" :class="{ 'is-live': online }" />
        <span class="topbar-date">{{ todayText }}</span>
      </div>
    </header>

    <main class="stage">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { memberItem, navItems } from './router'
import { statsApi } from './api'

/** main.js 只装了 Element Plus，router 在这里装到根实例上（install 幂等，重复调用无副作用） */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

const counters = ref({})
const online = ref(false)

const today = new Date()
const todayText = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

const activeKey = computed(() => {
  const hit = navItems.find((item) => route.path.startsWith(item.path))
  return hit ? hit.key : memberItem.key
})

function badge(item) {
  const value = counters.value[item.badge]
  return value === undefined || value === null ? null : value
}

function go(item) {
  if (route.path !== item.path) nav.push({ path: item.path })
}

async function loadCounters() {
  try {
    const board = await statsApi.board()
    counters.value = board.counters || {}
    online.value = true
  } catch (e) {
    online.value = false
  }
}

onMounted(loadCounters)
</script>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

/* ---------- 顶部条 + 横排 Tab ---------- */
.topbar {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 10px 20px;
  background: #fff;
  border-bottom: 1px solid var(--line);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #00695c, #26a69a);
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(0, 105, 92, 0.3);
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand-text strong {
  font-size: 15px;
}

.brand-text em {
  font-style: normal;
  font-size: 11px;
  color: var(--ink-mute);
}

.tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: 12px;
  padding: 4px;
  background: #f4f7f7;
  border: 1px solid var(--line);
  border-radius: 12px;
}

.tab {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 14px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--ink-soft);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, box-shadow 0.15s;
}

.tab:hover {
  background: #e7efee;
  color: var(--el-color-primary);
}

.tab.is-on {
  background: var(--el-color-primary);
  color: #fff;
  box-shadow: 0 2px 8px rgba(0, 105, 92, 0.3);
}

.tab-ico {
  font-size: 15px;
  line-height: 1;
}

.tab-label {
  font-weight: 600;
}

.tab-badge {
  font-style: normal;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.07);
  color: inherit;
}

.tab.is-on .tab-badge {
  background: rgba(255, 255, 255, 0.25);
}

.tab-split {
  width: 1px;
  height: 20px;
  background: var(--line);
  margin: 0 4px;
}

.tab-side {
  color: #4a7c72;
}

.topbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-mute);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #cfd8d6;
}

.dot.is-live {
  background: #2eb872;
  box-shadow: 0 0 0 3px rgba(46, 184, 114, 0.18);
}

.stage {
  flex: 1;
  overflow: auto;
  background: var(--floor);
}

@media (max-width: 1080px) {
  .brand-text em,
  .topbar-right {
    display: none;
  }
}
</style>
