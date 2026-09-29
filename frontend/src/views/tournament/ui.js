/** 淘汰赛计分台共用的展示小工具 */

export const fmtTime = (value) => (value ? String(value).replace('T', ' ').slice(5, 19) : '')

export function tourStatusType(status) {
  if (status === 'ONGOING') return 'warning'
  if (status === 'FINISHED') return 'success'
  return 'info'
}

export function matchStatusType(status) {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'AWAIT_CONFIRM') return 'warning'
  if (status === 'ONGOING') return 'primary'
  return 'info'
}

export function roleTagType(role) {
  if (role === 'MANAGER') return 'warning'
  if (role === 'REFEREE') return 'primary'
  if (role === 'SYSTEM') return 'success'
  return 'info'
}

/** 环数芯片配色（与日常计分台一致） */
export function chipClass(ring) {
  if (ring === 'X') return 'is-x'
  if (ring === 'M') return 'is-miss'
  if (ring === '10') return 'is-ten'
  return ''
}
