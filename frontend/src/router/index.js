import { createRouter, createWebHistory } from 'vue-router'

/**
 * 导航形态：顶部横排 Tab（箭道 / 计分 / 课程 / 器材），会员作为右侧的独立管理页。
 * 图标用 emoji，不引图标库。
 */
export const navItems = [
  { key: 'lanes', path: '/lanes', icon: '🎯', label: '箭道', hint: '靶场平面图，点道开台', badge: 'laneOpen', unit: '开放' },
  { key: 'score', path: '/score', icon: '🏹', label: '计分', hint: '环数键盘，一支一支记', badge: 'roundOngoing', unit: '进行中' },
  { key: 'tournament', path: '/tournament', icon: '🏆', label: '淘汰赛', hint: '团体淘汰赛计分台：赛事总览 · 对阵树 · 单场记分', badge: 'tournamentOngoing', unit: '进行中' },
  { key: 'safety', path: '/safety', icon: '🛑', label: '联锁', hint: '安全停射联锁台：停射事件 · 双人复核 · 复射放行', badge: 'safetyActive', unit: '起' },
  { key: 'cert', path: '/cert', icon: '🎖️', label: '认证', hint: '弓种能力认证：证据窗口 · 版本化规则 · 教练复核 · 有效期', badge: 'certPending', unit: '待复核' },
  { key: 'courses', path: '/courses', icon: '📚', label: '课程', hint: '卡片列表，满员禁用报名', badge: 'courseTotal', unit: '门' },
  { key: 'equipment', path: '/equipment', icon: '🧰', label: '器材', hint: '表格 + 租借归还', badge: 'equipRented', unit: '租出' }
]

export const memberItem = { key: 'members', path: '/members', icon: '🪪', label: '会员', hint: '开卡 / 充值 / 等级', badge: 'memberTotal', unit: '人' }

const routes = [
  { path: '/', redirect: '/lanes' },
  { path: '/lanes', name: 'lanes', component: () => import('../views/LaneView.vue') },
  { path: '/score', name: 'score', component: () => import('../views/ScoreView.vue') },
  { path: '/tournament', name: 'tournament', component: () => import('../views/TournamentView.vue') },
  { path: '/safety', name: 'safety', component: () => import('../views/SafetyView.vue') },
  { path: '/cert', name: 'cert', component: () => import('../views/CertView.vue') },
  { path: '/courses', name: 'courses', component: () => import('../views/CourseView.vue') },
  { path: '/equipment', name: 'equipment', component: () => import('../views/EquipmentView.vue') },
  { path: '/members', name: 'members', component: () => import('../views/MemberView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/lanes' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
