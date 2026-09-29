import { reactive, watch } from 'vue'

/**
 * 淘汰赛计分台当前身份：值班经理 / 裁判 / 普通会员。
 * 角色只决定能不能点按钮，真正的鉴权在后端（越权返回 403「无权操作」）。
 * 刷新页面后从 localStorage 恢复，避免反复切换。
 */
const STORAGE_KEY = 'tournament-identity'

function load() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw)
  } catch (e) {
    // ignore
  }
  return { role: 'MANAGER', operator: '', tab: 'overview', tournamentId: null, matchId: null }
}

export const identity = reactive(load())

watch(
  identity,
  (value) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(value))
    } catch (e) {
      // ignore
    }
  },
  { deep: true }
)

export const isManager = () => identity.role === 'MANAGER'
export const isReferee = () => identity.role === 'REFEREE'
export const isMember = () => identity.role === 'MEMBER'

/** 请求体公共字段：所有写操作都带角色与操作人 */
export function actor(extra = {}) {
  return { role: identity.role, operator: identity.operator, ...extra }
}
