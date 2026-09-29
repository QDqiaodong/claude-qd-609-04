<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">安全停射联锁台 <em>停射事件 · 双人分项复核 · 复射放行</em></h2>
      <div class="page-tools">
        <el-radio-group v-model="role" size="small">
          <el-radio-button v-for="r in options.roles || []" :key="r.code" :value="r.code">{{ r.name }}</el-radio-button>
        </el-radio-group>
        <el-input v-model="operator" :placeholder="`当前${roleName}姓名`" style="width: 160px" />
      </div>
    </div>

    <div class="rule-tip">
      值班经理建立停射事件后，受影响箭道立即安全锁定（不可开台 / 开打）、进行中的回合暂停（保留已记箭支）、
      相关器材冻结（不可租借 / 归还 / 维修）。教练确认「射线与人员安全」、器材管理员确认「器材检查」，
      两项都通过后才允许值班经理执行复射放行；任一不通过则保留原因、继续锁定。当前角色：
      <b>{{ roleName }}</b>，页面只展示该角色允许执行的动作。
    </div>

    <!-- 建立停射事件：仅值班经理 -->
    <div v-if="isManager" class="panel">
      <div class="panel-title">
        建立停射事件
        <small>选择受影响箭道与器材，写明原因、严重级别与现场说明；提交后立即联锁</small>
      </div>
      <div class="create-grid">
        <div class="create-form">
          <el-form label-width="84px" label-position="left">
            <el-form-item label="停射原因">
              <el-select v-model="createForm.reason" placeholder="请选择" style="width: 100%">
                <el-option v-for="r in options.reasons || []" :key="r.code" :label="r.name" :value="r.code" />
              </el-select>
            </el-form-item>
            <el-form-item label="严重级别">
              <el-radio-group v-model="createForm.severity">
                <el-radio-button v-for="s in options.severities || []" :key="s.code" :value="s.code">
                  {{ s.name }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="现场说明">
              <el-input
                v-model="createForm.description"
                type="textarea"
                :rows="3"
                maxlength="500"
                show-word-limit
                placeholder="例：A02 道靶架松动，已疏散该侧射手；或发现人员越过起射线…"
              />
            </el-form-item>
          </el-form>
          <div class="create-actions">
            <span class="muted">已选箭道 {{ createForm.laneIds.length }} 条 · 器材 {{ createForm.equipmentIds.length }} 件</span>
            <el-button type="danger" :loading="creating" @click="submitCreate">建立事件并立即联锁</el-button>
          </div>
        </div>
        <div class="create-pick">
          <div class="pick-title">受影响箭道 <small class="muted">安全锁定中的不可再选</small></div>
          <el-checkbox-group v-model="createForm.laneIds">
            <el-checkbox
              v-for="l in lanes"
              :key="l.id"
              :value="l.id"
              :disabled="l.status === 'LOCKED'"
              class="pick-item"
            >
              {{ l.laneNo }} · {{ l.distance }}m
              <em :class="'st-' + l.status.toLowerCase()">{{ l.statusName }}</em>
            </el-checkbox>
          </el-checkbox-group>
          <div class="pick-title">受影响器材</div>
          <el-checkbox-group v-model="createForm.equipmentIds">
            <el-checkbox
              v-for="e in equips"
              :key="e.id"
              :value="e.id"
              :disabled="e.status === 'LOCKED'"
              class="pick-item"
            >
              {{ e.equipCode }} · {{ e.typeName }}
              <em :class="'st-' + e.status.toLowerCase()">{{ e.statusName }}</em>
            </el-checkbox>
          </el-checkbox-group>
        </div>
      </div>
    </div>

    <div class="board">
      <!-- 事件列表 -->
      <div class="panel list-panel">
        <div class="panel-title">
          停射事件
          <small>未放行 <b class="c-danger">{{ activeCount }}</b> 起 · 共 {{ events.length }} 起</small>
          <el-checkbox v-model="onlyActive" label="只看未放行" class="only-active" />
        </div>
        <el-table
          :data="shownEvents"
          row-key="id"
          highlight-current-row
          :current-row-key="selectedId"
          height="520"
          @current-change="onPick"
        >
          <el-table-column prop="eventNo" label="事件号" width="150">
            <template #default="{ row }"><span class="mono">{{ row.eventNo }}</span></template>
          </el-table-column>
          <el-table-column label="原因" min-width="120">
            <template #default="{ row }">{{ row.reasonName }}</template>
          </el-table-column>
          <el-table-column label="级别" width="70">
            <template #default="{ row }">
              <el-tag size="small" :type="severityType(row.severity)" effect="dark">{{ row.severityName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="联锁" width="110">
            <template #default="{ row }">
              <span class="muted">道{{ row.laneCount }} · 器{{ row.equipmentCount }} · 回{{ row.roundCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="建立" width="130">
            <template #default="{ row }">
              <span class="muted">{{ row.createdBy }} · {{ shortTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 事件详情 -->
      <div class="panel detail-panel">
        <template v-if="detail">
          <div class="panel-title">
            事件 {{ detail.eventNo }}
            <el-tag size="small" :type="severityType(detail.severity)" effect="dark">{{ detail.severityName }}</el-tag>
            <el-tag size="small" :type="statusType(detail.status)" effect="dark">{{ detail.statusName }}</el-tag>
            <small>{{ detail.reasonName }} · 第 {{ detail.reviewRound }} 轮复核</small>
          </div>

          <el-steps :active="stepActive" align-center finish-status="success" class="steps">
            <el-step title="待处置" description="事件建立，资源联锁" />
            <el-step title="分项复核" description="教练 + 器材管理员" />
            <el-step title="待放行" description="两项复核均通过" />
            <el-step title="已放行" description="资源恢复原状态" />
          </el-steps>

          <div class="desc-box">
            <b>现场说明</b>
            <p>{{ detail.description }}</p>
            <span class="muted">
              值班经理 {{ detail.createdBy }} 建立于 {{ fmtTime(detail.createdAt) }}
              <template v-if="detail.releasedBy"> · {{ detail.releasedBy }} 放行于 {{ fmtTime(detail.releasedAt) }}</template>
            </span>
          </div>

          <!-- 受影响资源 -->
          <div class="res-grid">
            <div class="res-box">
              <div class="res-title">受影响箭道（{{ detail.lanes.length }}）</div>
              <div v-for="l in detail.lanes" :key="l.laneId" class="res-row">
                <b class="mono">{{ l.laneNo }}</b>
                <span class="muted">{{ l.prevStatusName }}</span>
                <span class="arrow">→</span>
                <el-tag size="small" :type="l.currentStatus === 'LOCKED' ? 'danger' : 'success'" effect="plain">
                  {{ l.currentStatusName }}
                </el-tag>
              </div>
              <div v-if="!detail.lanes.length" class="muted">无</div>
            </div>
            <div class="res-box">
              <div class="res-title">暂停回合（{{ detail.rounds.length }}）</div>
              <div v-for="r in detail.rounds" :key="r.roundId" class="res-row">
                <b class="mono">{{ r.roundNo }}</b>
                <span class="muted">{{ r.memberName }} · {{ r.laneNo }} · {{ r.shotCount }}/{{ r.arrowCount }} 支</span>
                <el-tag size="small" :type="r.currentStatus === 'PAUSED' ? 'danger' : 'success'" effect="plain">
                  {{ r.currentStatusName }}
                </el-tag>
              </div>
              <div v-if="!detail.rounds.length" class="muted">无</div>
            </div>
            <div class="res-box">
              <div class="res-title">受影响器材（{{ detail.equipment.length }}）</div>
              <div v-for="e in detail.equipment" :key="e.equipmentId" class="res-row">
                <b class="mono">{{ e.equipCode }}</b>
                <span class="muted">{{ e.typeName }} · {{ e.prevStatusName }}</span>
                <span class="arrow">→</span>
                <el-tag size="small" :type="e.currentStatus === 'LOCKED' ? 'danger' : 'success'" effect="plain">
                  {{ e.currentStatusName }}
                </el-tag>
              </div>
              <div v-if="!detail.equipment.length" class="muted">无</div>
            </div>
          </div>

          <!-- 本轮复核 -->
          <div class="res-title" style="margin-top: 12px">本轮复核（第 {{ detail.reviewRound }} 轮）</div>
          <div class="review-grid">
            <div v-for="item in currentReviews" :key="item.item" class="review-card" :class="'rv-' + (item.conclusion || 'wait').toLowerCase()">
              <div class="review-head">
                <b>{{ item.itemName }}</b>
                <el-tag size="small" :type="conclusionType(item.conclusion)" effect="dark">
                  {{ item.conclusionName }}
                </el-tag>
              </div>
              <div class="review-body">
                <template v-if="item.conclusion">
                  <span>{{ item.reviewer }} · {{ fmtTime(item.reviewedAt) }}</span>
                  <p v-if="item.note" class="review-note" :class="{ 'is-fail': item.conclusion === 'FAIL' }">
                    {{ item.note }}
                  </p>
                </template>
                <span v-else class="muted">等待{{ item.item === 'RANGE' ? '教练' : '器材管理员' }}复核</span>
              </div>
            </div>
          </div>

          <!-- 角色动作区：只展示当前角色允许执行的动作 -->
          <div v-if="hasAnyAction" class="action-bar">
            <template v-if="canStartReview">
              <el-button type="primary" :loading="acting" @click="doStartReview">发起分项复核</el-button>
              <span class="muted">发起后由教练与器材管理员分别确认</span>
            </template>
            <template v-if="canReview">
              <el-radio-group v-model="reviewForm.conclusion">
                <el-radio-button value="PASS">通过</el-radio-button>
                <el-radio-button value="FAIL">不通过</el-radio-button>
              </el-radio-group>
              <el-input
                v-model="reviewForm.note"
                :placeholder="reviewForm.conclusion === 'FAIL' ? '必填：不通过原因' : '复核意见（选填）'"
                style="width: 260px"
                maxlength="500"
              />
              <el-button
                :type="reviewForm.conclusion === 'FAIL' ? 'danger' : 'success'"
                :loading="acting"
                @click="doReview"
              >
                提交「{{ myReviewItem.itemName }}」复核
              </el-button>
            </template>
            <template v-if="canRelease">
              <el-button type="success" :loading="acting" @click="doRelease">复射放行</el-button>
              <span class="muted">两项复核均已通过，放行后箭道 / 回合 / 器材恢复锁定前状态</span>
            </template>
          </div>
          <div v-else-if="detail.status !== 'RELEASED'" class="action-bar is-hint">
            <span class="muted">{{ waitHint }}</span>
          </div>

          <!-- 历史复核结论（复核不通过重新发起的轮次） -->
          <template v-if="pastReviews.length">
            <div class="res-title" style="margin-top: 12px">历史复核结论</div>
            <div v-for="item in pastReviews" :key="item.reviewRound + item.item" class="past-review">
              <el-tag size="small" effect="plain">第 {{ item.reviewRound }} 轮</el-tag>
              <span>{{ item.itemName }}</span>
              <el-tag size="small" :type="conclusionType(item.conclusion)" effect="dark">{{ item.conclusionName }}</el-tag>
              <span class="muted">{{ item.reviewer }} · {{ fmtTime(item.reviewedAt) }}</span>
              <span v-if="item.note" class="review-note is-fail">{{ item.note }}</span>
            </div>
          </template>

          <!-- 状态轨迹 -->
          <div class="res-title" style="margin-top: 12px">状态轨迹</div>
          <el-timeline class="trail">
            <el-timeline-item
              v-for="(log, index) in detail.logs"
              :key="index"
              :timestamp="fmtTime(log.createdAt)"
              :type="log.action === 'RELEASE' ? 'success' : log.action === 'REVIEW_FAIL' || log.action === 'BACK_PENDING' ? 'danger' : 'primary'"
            >
              <b>{{ log.actionName }}</b>
              <span class="muted"> · {{ log.operator }}（{{ log.roleName }}）</span>
              <div v-if="log.detail" class="muted">{{ log.detail }}</div>
            </el-timeline-item>
          </el-timeline>
        </template>
        <el-empty v-else description="选择左侧一起事件查看详情" :image-size="80" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { equipApi, laneApi, safetyApi, shortTime } from '../api'

const ROLE_KEY = 'safety.role'
const NAMES_KEY = 'safety.names'

const options = ref({})
const events = ref([])
const detail = ref(null)
const selectedId = ref(null)
const lanes = ref([])
const equips = ref([])
const onlyActive = ref(true)
const creating = ref(false)
const acting = ref(false)

// 角色与操作人：刷新 / 重进后保持一致
const role = ref(localStorage.getItem(ROLE_KEY) || 'MANAGER')
const names = reactive(JSON.parse(localStorage.getItem(NAMES_KEY) || '{}'))
const operator = computed({
  get: () => names[role.value] || '',
  set: (value) => { names[role.value] = value }
})
watch(role, (value) => localStorage.setItem(ROLE_KEY, value))
watch(names, (value) => localStorage.setItem(NAMES_KEY, JSON.stringify(value)), { deep: true })

const createForm = reactive({ reason: 'LANE_DEVICE', severity: 'MAJOR', description: '', laneIds: [], equipmentIds: [] })
const reviewForm = reactive({ conclusion: 'PASS', note: '' })

const isManager = computed(() => role.value === 'MANAGER')
const isCoach = computed(() => role.value === 'COACH')
const isKeeper = computed(() => role.value === 'KEEPER')
const roleName = computed(() => {
  const hit = (options.value.roles || []).find((r) => r.code === role.value)
  return hit ? hit.name : '值班经理'
})

const activeCount = computed(() => events.value.filter((e) => e.status !== 'RELEASED').length)
const shownEvents = computed(() => (onlyActive.value ? events.value.filter((e) => e.status !== 'RELEASED') : events.value))

const stepActive = computed(() => {
  if (!detail.value) return 0
  return { PENDING: 0, REVIEWING: 1, CLEARED: 2, RELEASED: 4 }[detail.value.status] ?? 0
})

const currentReviews = computed(() => {
  if (!detail.value) return []
  return detail.value.reviews.filter((r) => r.reviewRound === detail.value.reviewRound)
})

const pastReviews = computed(() => {
  if (!detail.value) return []
  return detail.value.reviews.filter((r) => r.reviewRound !== detail.value.reviewRound && r.conclusion)
})

const myReviewItem = computed(() => {
  if (!detail.value) return null
  const item = isCoach.value ? 'RANGE' : isKeeper.value ? 'EQUIPMENT' : null
  if (!item) return null
  return currentReviews.value.find((r) => r.item === item) || null
})

const canStartReview = computed(() => isManager.value && detail.value && detail.value.status === 'PENDING')
const canRelease = computed(() => isManager.value && detail.value && detail.value.status === 'CLEARED')
const canReview = computed(() =>
  (isCoach.value || isKeeper.value)
  && detail.value && detail.value.status === 'REVIEWING'
  && myReviewItem.value && !myReviewItem.value.conclusion
)
const hasAnyAction = computed(() => canStartReview.value || canReview.value || canRelease.value)

const waitHint = computed(() => {
  if (!detail.value) return ''
  const status = detail.value.status
  if (status === 'PENDING') return isManager.value ? '' : '等待值班经理发起分项复核'
  if (status === 'REVIEWING') {
    if (isManager.value) return '复核进行中：等待教练与器材管理员分别确认'
    return myReviewItem.value && myReviewItem.value.conclusion ? '你已完成本项复核，等待另一项复核结论' : ''
  }
  if (status === 'CLEARED') return isManager.value ? '' : '两项复核均通过，等待值班经理执行复射放行'
  return ''
})

function severityType(severity) {
  return { NOTICE: 'info', MAJOR: 'warning', CRITICAL: 'danger' }[severity] || 'info'
}

function statusType(status) {
  return { PENDING: 'danger', REVIEWING: 'warning', CLEARED: 'primary', RELEASED: 'success' }[status] || 'info'
}

function conclusionType(conclusion) {
  return conclusion === 'PASS' ? 'success' : conclusion === 'FAIL' ? 'danger' : 'info'
}

const fmtTime = (value) => (value ? String(value).slice(0, 16) : '')

function requireOperator() {
  if (!operator.value.trim()) {
    ElMessage.warning(`请先填写当前${roleName.value}姓名`)
    return false
  }
  return true
}

async function loadEvents(keepSelection = true) {
  events.value = await safetyApi.list()
  if (!keepSelection) return
  if (selectedId.value && events.value.some((e) => e.id === selectedId.value)) return
  const firstActive = events.value.find((e) => e.status !== 'RELEASED')
  selectedId.value = (firstActive || events.value[0] || {}).id || null
}

async function loadDetail() {
  if (!selectedId.value) {
    detail.value = null
    return
  }
  detail.value = await safetyApi.detail(selectedId.value)
}

async function loadResources() {
  lanes.value = await laneApi.list()
  equips.value = await equipApi.list()
}

function onPick(row) {
  if (row && row.id !== selectedId.value) {
    selectedId.value = row.id
    reviewForm.conclusion = 'PASS'
    reviewForm.note = ''
    loadDetail()
  }
}

async function submitCreate() {
  if (!requireOperator()) return
  if (!createForm.description.trim()) {
    ElMessage.warning('请填写现场说明')
    return
  }
  if (!createForm.laneIds.length && !createForm.equipmentIds.length) {
    ElMessage.warning('请至少选择一条箭道或一件器材')
    return
  }
  creating.value = true
  try {
    const created = await safetyApi.create({
      reason: createForm.reason,
      severity: createForm.severity,
      description: createForm.description.trim(),
      laneIds: createForm.laneIds,
      equipmentIds: createForm.equipmentIds,
      operator: operator.value.trim()
    })
    ElMessage.success('停射事件已建立，受影响资源已安全锁定')
    createForm.description = ''
    createForm.laneIds = []
    createForm.equipmentIds = []
    selectedId.value = created.id
    await loadEvents()
    await loadDetail()
    await loadResources()
  } finally {
    creating.value = false
  }
}

async function doStartReview() {
  if (!requireOperator()) return
  acting.value = true
  try {
    detail.value = await safetyApi.startReview(selectedId.value, operator.value.trim())
    ElMessage.success('已发起分项复核')
    await loadEvents()
  } finally {
    acting.value = false
  }
}

async function doReview() {
  if (!requireOperator()) return
  if (reviewForm.conclusion === 'FAIL' && !reviewForm.note.trim()) {
    ElMessage.warning('复核不通过时必须填写原因')
    return
  }
  acting.value = true
  try {
    detail.value = await safetyApi.review(selectedId.value, {
      item: myReviewItem.value.item,
      operator: operator.value.trim(),
      conclusion: reviewForm.conclusion,
      note: reviewForm.note.trim()
    })
    ElMessage.success('复核结论已记录')
    reviewForm.note = ''
    await loadEvents()
  } finally {
    acting.value = false
  }
}

async function doRelease() {
  if (!requireOperator()) return
  try {
    await ElMessageBox.confirm(
      '确认复射放行？受影响箭道、回合与器材将恢复锁定前的业务状态。',
      `复射放行 · ${detail.value.eventNo}`,
      { type: 'warning', confirmButtonText: '确认放行', cancelButtonText: '再想想' }
    )
  } catch (e) {
    return
  }
  acting.value = true
  try {
    detail.value = await safetyApi.release(selectedId.value, operator.value.trim())
    ElMessage.success('已复射放行，受影响资源恢复原状态')
    await loadEvents()
    await loadResources()
  } finally {
    acting.value = false
  }
}

let timer = null
onMounted(async () => {
  options.value = await safetyApi.options()
  await Promise.all([loadEvents(), loadResources()])
  await loadDetail()
  // 多人协同：定时刷新列表与详情，两个窗口看到的状态保持一致
  timer = setInterval(async () => {
    if (acting.value || creating.value) return
    try {
      await loadEvents()
      await loadDetail()
      await loadResources()
    } catch (e) { /* 轮询失败不打断页面 */ }
  }, 5000)
})

onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.board {
  display: grid;
  grid-template-columns: minmax(520px, 5fr) minmax(480px, 6fr);
  gap: 14px;
  margin-top: 14px;
  align-items: start;
}

.create-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(320px, 1fr);
  gap: 18px;
}

.create-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.create-pick {
  border: 1px dashed var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  max-height: 300px;
  overflow: auto;
}

.pick-title {
  font-size: 12px;
  font-weight: 600;
  margin: 6px 0 6px;
}

.pick-title:first-child {
  margin-top: 0;
}

.pick-item {
  display: flex;
  margin-right: 18px;
}

.pick-item em {
  font-style: normal;
  font-size: 11px;
  margin-left: 4px;
  color: var(--ink-mute);
}

.pick-item em.st-locked {
  color: #c62828;
  font-weight: 700;
}

.only-active {
  margin-left: auto;
}

.steps {
  margin: 6px 0 12px;
}

.desc-box {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fbfdfd;
  font-size: 13px;
}

.desc-box p {
  margin: 6px 0;
  line-height: 1.6;
}

.res-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-top: 12px;
}

.res-box {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px 10px;
  min-height: 76px;
}

.res-title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 6px;
}

.res-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 3px 0;
  flex-wrap: wrap;
}

.res-row .arrow {
  color: var(--ink-mute);
}

.review-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.review-card {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 12px;
}

.review-card.rv-pass {
  border-color: #a8ddc0;
  background: #f2fbf5;
}

.review-card.rv-fail {
  border-color: #f2b8b8;
  background: #fdf3f3;
}

.review-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.review-note {
  margin: 4px 0 0;
  padding: 4px 8px;
  border-radius: 6px;
  background: #f4f7f7;
}

.review-note.is-fail {
  background: #fdecec;
  color: #b3261e;
}

.action-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 14px;
  padding: 10px 12px;
  border: 1px dashed var(--el-color-primary-light-5);
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
}

.action-bar.is-hint {
  border-style: solid;
  background: #fafcfc;
}

.past-review {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 4px 0;
  flex-wrap: wrap;
}

.trail {
  margin-top: 8px;
  padding-left: 2px;
}

.c-danger {
  color: #c62828;
}

@media (max-width: 1200px) {
  .board,
  .create-grid,
  .res-grid {
    grid-template-columns: 1fr;
  }
}
</style>
