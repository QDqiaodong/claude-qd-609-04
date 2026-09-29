import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  }
)

/** 模块一：箭道 */
export const laneApi = {
  list: (params) => http.get('/lanes', { params }),
  create: (data) => http.post('/lanes', data),
  open: (id, data) => http.post(`/lanes/${id}/open`, data),
  release: (id) => http.post(`/lanes/${id}/release`),
  setStatus: (id, status) => http.post(`/lanes/${id}/status`, { status }),
  options: () => http.get('/lanes/options')
}

/** 模块二：会员 */
export const memberApi = {
  list: (params) => http.get('/members', { params }),
  options: () => http.get('/members/options'),
  create: (data) => http.post('/members', data),
  recharge: (id, amount) => http.post(`/members/${id}/recharge`, { amount })
}

/** 模块九：重复会员档案合并（预演 → 确认 → 轨迹 → 旧卡入口跳转） */
export const mergeApi = {
  preview: (data) => http.post('/member-merges/preview', data),
  previewDetail: (id) => http.get(`/member-merges/preview/${id}`),
  confirm: (data) => http.post('/member-merges/confirm', data),
  trails: (memberId) => http.get(`/member-merges/trails/${memberId}`),
  lookup: (cardNo) => http.get('/member-merges/lookup', { params: { cardNo } })
}

/** 模块三：计分回合（有序集合：每支箭按 shot_index 顺序 append） */
export const roundApi = {
  list: (params) => http.get('/rounds', { params }),
  keypad: () => http.get('/rounds/keypad'),
  start: (data) => http.post('/rounds', data),
  detail: (id) => http.get(`/rounds/${id}`),
  shoot: (id, ring) => http.post(`/rounds/${id}/shots`, { ring }),
  undo: (id) => http.post(`/rounds/${id}/undo`),
  submit: (id) => http.post(`/rounds/${id}/submit`),
  /** 会员各可比组别的个人最好 */
  memberBests: (memberId) => http.get(`/members/${memberId}/personal-bests`),
  /** 历史修复：预演（不落库）/ 执行（只改个人最好标记，可重复执行） */
  previewRepair: () => http.get('/personal-bests/repair'),
  repair: () => http.post('/personal-bests/repair')
}

/** 模块四：课程预约 */
export const courseApi = {
  list: (params) => http.get('/courses', { params }),
  create: (data) => http.post('/courses', data),
  enroll: (id, memberId) => http.post(`/courses/${id}/enroll`, { memberId }),
  cancel: (id, memberId) => http.post(`/courses/${id}/cancel`, { memberId }),
  options: () => http.get('/courses/options')
}

/** 模块五：器材租赁 */
export const equipApi = {
  list: (params) => http.get('/equipment', { params }),
  create: (data) => http.post('/equipment', data),
  rent: (id, memberId) => http.post(`/equipment/${id}/rent`, { memberId }),
  giveBack: (id) => http.post(`/equipment/${id}/return`),
  repair: (id) => http.post(`/equipment/${id}/repair`),
  options: () => http.get('/equipment/options')
}

/** 模块六：安全停射联锁台（建立事件 → 双人分项复核 → 复射放行） */
export const safetyApi = {
  options: () => http.get('/safety/options'),
  list: (params) => http.get('/safety/events', { params }),
  detail: (id) => http.get(`/safety/events/${id}`),
  create: (data) => http.post('/safety/events', data),
  startReview: (id, operator) => http.post(`/safety/events/${id}/start-review`, { operator }),
  review: (id, data) => http.post(`/safety/events/${id}/review`, data),
  release: (id, operator) => http.post(`/safety/events/${id}/release`, { operator })
}

/** 模块七：馆内团体淘汰赛计分台（独立的一套计分台） */
export const tournamentApi = {  options: () => http.get('/tournament/options'),
  list: () => http.get('/tournament/list'),
  detail: (id) => http.get(`/tournament/${id}`),
  create: (data) => http.post('/tournament/create', data),
  addTeam: (id, data) => http.post(`/tournament/${id}/teams`, data),
  updateTeam: (id, teamId, data) => http.post(`/tournament/${id}/teams/${teamId}`, data),
  removeTeam: (id, teamId, data) => http.post(`/tournament/${id}/teams/${teamId}/remove`, data),
  start: (id, data) => http.post(`/tournament/${id}/start`, data),
  match: (matchId) => http.get(`/tournament/matches/${matchId}`),
  recordArrow: (matchId, data) => http.post(`/tournament/matches/${matchId}/arrows`, data),
  confirmEnd: (matchId, data) => http.post(`/tournament/matches/${matchId}/confirm-end`, data),
  retractEnd: (matchId, data) => http.post(`/tournament/matches/${matchId}/retract-end`, data),
  shootoffArrow: (matchId, data) => http.post(`/tournament/matches/${matchId}/shootoff/arrows`, data),
  lockShootoff: (matchId, data) => http.post(`/tournament/matches/${matchId}/shootoff/lock`, data),
  confirmWinner: (matchId, data) => http.post(`/tournament/matches/${matchId}/confirm-winner`, data)
}

/** 模块八：弓种能力认证（版本化规则 · 证据窗口评定 · 教练复核 · 有效期管理） */
export const certApi = {
  options: () => http.get('/cert/options'),
  rules: () => http.get('/cert/rules'),
  createRule: (data) => http.post('/cert/rules', data),
  newVersion: (id, data) => http.post(`/cert/rules/${id}/new-version`, data),
  retireRule: (id, operator) => http.post(`/cert/rules/${id}/retire`, { operator }),
  list: (params) => http.get('/cert/applications', { params }),
  detail: (id) => http.get(`/cert/applications/${id}`),
  create: (data) => http.post('/cert/applications', data),
  decide: (id, data) => http.post(`/cert/applications/${id}/decide`, data),
  resubmit: (id, data) => http.post(`/cert/applications/${id}/resubmit`, data),
  withdraw: (id, data) => http.post(`/cert/applications/${id}/withdraw`, data),
  memberCerts: (memberId) => http.get(`/cert/members/${memberId}`)
}

export const statsApi = {
  board: () => http.get('/stats/board')
}

/** 时间小工具：后端 DATETIME 出来是 "yyyy-MM-dd HH:mm:ss" */
export const shortTime = (value) => (value ? String(value).slice(5, 16) : '')

export const shortDate = (value) => (value ? String(value).slice(0, 10) : '')

export const money = (value) => (value == null ? '-' : Number(value).toFixed(2))

export default http
