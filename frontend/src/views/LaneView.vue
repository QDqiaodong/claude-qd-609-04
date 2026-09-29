<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">箭道管理 <em>靶场平面图 · 点一条道即可开台</em></h2>
      <div class="page-tools">
        <el-select v-model="filterDistance" clearable placeholder="按距离" style="width: 120px" @change="loadLanes">
          <el-option v-for="d in options.distances || []" :key="d" :label="`${d} 米`" :value="d" />
        </el-select>
        <el-select v-model="filterStatus" clearable placeholder="按状态" style="width: 120px" @change="loadLanes">
          <el-option v-for="s in options.statuses || []" :key="s.code" :label="s.name" :value="s.code" />
        </el-select>
        <el-button type="primary" @click="createVisible = true">新增箭道</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">
        靶场平面图
        <small>开放 <b class="c-open">{{ countOf('OPEN') }}</b> · 占用 <b class="c-busy">{{ countOf('OCCUPIED') }}</b> ·
          维护 <b class="c-grey">{{ countOf('MAINTENANCE') }}</b> ·
          锁定 <b class="c-lock">{{ countOf('LOCKED') }}</b></small>
      </div>

      <div class="rule-tip">
        绿色 = 开放，可点开台；深青 = 占用中，可点收台；灰色 = 维护中，禁止开台；红色 = 安全锁定（停射事件未放行，禁止开台与收台）。开台按「单价 × 时长 × 会员折扣」预扣余额。
        <b>30 / 50 米为认证射距：会员须持有覆盖该射距的当前有效弓种认证（认证射距 ≥ 箭道射距），认证过期 / 撤回 / 被取代后开台将被拒绝。</b>
      </div>

      <div class="hall">
        <div class="hall-line hall-line-top"><span>靶 线 · TARGET LINE</span></div>

        <div class="lane-row">
          <div
            v-for="lane in lanes"
            :key="lane.id"
            class="lane"
            :class="['is-' + lane.status.toLowerCase(), { 'is-pick': pickId === lane.id }]"
            @click="pickLane(lane)"
          >
            <svg class="target" viewBox="0 0 44 44" aria-hidden="true">
              <circle cx="22" cy="22" r="20" fill="#f6f9f9" stroke="#cfd8d6" />
              <circle cx="22" cy="22" r="15" fill="#ffffff" stroke="#b9c7c4" />
              <circle cx="22" cy="22" r="11" fill="#e53935" stroke="#b71c1c" />
              <circle cx="22" cy="22" r="6" fill="#fdd835" stroke="#e8b90f" />
              <circle cx="22" cy="22" r="2.4" fill="#00695c" />
            </svg>

            <div class="strip">
              <span class="strip-dist">{{ lane.distance }}m</span>
              <span class="strip-type">{{ lane.targetType }}</span>
              <span v-if="lane.status === 'OCCUPIED'" class="strip-who">{{ lane.occupantName || '占用中' }}</span>
            </div>

            <div class="lane-foot">
              <strong>{{ lane.laneNo }}</strong>
              <span class="lane-status">{{ lane.statusName }}</span>
              <span class="lane-price">¥{{ money(lane.hourlyPrice) }}/时</span>
            </div>
          </div>
        </div>

        <div class="hall-line hall-line-bottom"><span>起 射 线 · SHOOTING LINE</span></div>
      </div>

      <div class="legend">
        <span><i class="sw sw-open" />开放</span>
        <span><i class="sw sw-busy" />占用</span>
        <span><i class="sw sw-grey" />维护</span>
        <span><i class="sw sw-lock" />安全锁定</span>
        <span class="muted">共 {{ lanes.length }} 条道</span>
      </div>
    </div>

    <!-- 开台 -->
    <el-dialog v-model="openVisible" :title="`开台 · ${pick ? pick.laneNo : ''}`" width="420px">
      <div v-if="pick" class="dlg-tip">
        {{ pick.laneNo }} · {{ pick.distance }}米 · {{ pick.targetType }} · ¥{{ money(pick.hourlyPrice) }}/小时
      </div>
      <el-form label-width="84px">
        <el-form-item label="会员">
          <el-select v-model="openForm.memberId" placeholder="请选择会员" style="width: 100%" @change="loadMemberCerts">
            <el-option v-for="m in members" :key="m.id" :label="`${m.name}（${m.cardNo}）`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="pick && gatedDistances.includes(pick.distance)" label="认证识别">
          <el-tag size="small" :type="distanceCertOk ? 'success' : 'danger'" effect="dark">
            {{ distanceCertOk ? `持有覆盖 ${pick.distance} 米的有效认证` : `无覆盖 ${pick.distance} 米的有效认证` }}
          </el-tag>
          <span class="muted" style="margin-left: 8px">
            {{ distanceCertText }}
          </span>
        </el-form-item>
        <el-form-item label="时长">
          <el-input-number v-model="openForm.hours" :min="1" :max="options.maxOpenHours || 8" />
          <span class="muted" style="margin-left: 8px">小时</span>
        </el-form-item>
        <el-form-item label="预估费用">
          <b class="cost">¥ {{ previewCost }}</b>
          <span class="muted" style="margin-left: 8px">{{ discountText }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="openVisible = false">取消</el-button>
        <el-button type="primary" @click="submitOpen">确认开台</el-button>
      </template>
    </el-dialog>

    <!-- 新增箭道 -->
    <el-dialog v-model="createVisible" title="新增箭道" width="420px">
      <el-form label-width="84px">
        <el-form-item label="道号"><el-input v-model="createForm.laneNo" placeholder="如 A13" /></el-form-item>
        <el-form-item label="距离">
          <el-select v-model="createForm.distance" placeholder="请选择" style="width: 100%">
            <el-option v-for="d in options.distances || []" :key="d" :label="`${d} 米`" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="箭靶类型"><el-input v-model="createForm.targetType" placeholder="如 三联靶" /></el-form-item>
        <el-form-item label="每小时单价">
          <el-input-number v-model="createForm.hourlyPrice" :min="0" :step="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { certApi, laneApi, memberApi, money } from '../api'

const lanes = ref([])
const members = ref([])
const options = ref({})
const certOptions = ref({ gatedDistances: [] })
const memberCerts = ref([])
const filterDistance = ref(null)
const filterStatus = ref(null)
const pickId = ref(null)

const openVisible = ref(false)
const createVisible = ref(false)
const openForm = reactive({ memberId: null, hours: 2 })
const createForm = reactive({ laneNo: '', distance: 18, targetType: '三联靶', hourlyPrice: 80 })

const pick = computed(() => lanes.value.find((item) => item.id === pickId.value) || null)
const pickMember = computed(() => members.value.find((m) => m.id === openForm.memberId) || null)
const gatedDistances = computed(() => certOptions.value.gatedDistances || [30, 50])
const distanceCertOk = computed(() => {
  if (!pick.value) return false
  return memberCerts.value.some((c) => c.distance >= pick.value.distance)
})
const distanceCertText = computed(() => {
  const hit = memberCerts.value.find((c) => pick.value && c.distance >= pick.value.distance)
  return hit ? `${hit.bowTypeName} ${hit.distance} 米认证，有效期至 ${String(hit.validUntil).slice(0, 10)}`
    : '请先在「认证」页完成弓种能力认证'
})

const previewCost = computed(() => {
  if (!pick.value || !pickMember.value) return '0.00'
  const raw = Number(pick.value.hourlyPrice) * openForm.hours * Number(pickMember.value.discount || 1)
  return raw.toFixed(2)
})

const discountText = computed(() => {
  if (!pickMember.value) return ''
  const rate = Number(pickMember.value.discount || 1)
  return rate < 1 ? `${pickMember.value.levelName} ${(rate * 10).toFixed(1)} 折` : `${pickMember.value.levelName} 无折扣`
})

function countOf(status) {
  return lanes.value.filter((item) => item.status === status).length
}

async function loadLanes() {
  const params = {}
  if (filterStatus.value) params.status = filterStatus.value
  else if (filterDistance.value) params.distance = filterDistance.value
  lanes.value = await laneApi.list(params)
}

async function loadMembers() {
  members.value = await memberApi.options()
}

async function loadMemberCerts() {
  memberCerts.value = []
  if (openForm.memberId) {
    const data = await certApi.memberCerts(openForm.memberId)
    memberCerts.value = data.current || []
  }
}

async function pickLane(lane) {
  pickId.value = lane.id
  if (lane.status === 'LOCKED') {
    ElMessage.warning(`${lane.laneNo} 处于安全锁定，停射事件放行前禁止开台与收台`)
    return
  }
  if (lane.status === 'OPEN') {
    openForm.memberId = members.value.length ? members.value[0].id : null
    openForm.hours = 2
    memberCerts.value = []
    if (openForm.memberId) await loadMemberCerts()
    openVisible.value = true
    return
  }
  if (lane.status === 'OCCUPIED') {
    ElMessageBox.confirm(`${lane.laneNo} 当前由 ${lane.occupantName || '会员'} 占用，确认收台？`, '收台', {
      type: 'warning'
    }).then(async () => {
      await laneApi.release(lane.id)
      ElMessage.success('已收台')
      await loadLanes()
    }).catch(() => {})
    return
  }
  ElMessage.warning(`${lane.laneNo} 维护中，暂不可用`)
}

async function submitOpen() {
  if (!openForm.memberId) {
    ElMessage.warning('请选择会员')
    return
  }
  await laneApi.open(pickId.value, { memberId: openForm.memberId, hours: openForm.hours })
  ElMessage.success('开台成功')
  openVisible.value = false
  await loadLanes()
  await loadMembers()
}

async function submitCreate() {
  if (!createForm.laneNo.trim()) {
    ElMessage.warning('请填写道号')
    return
  }
  await laneApi.create({
    laneNo: createForm.laneNo.trim(),
    distance: createForm.distance,
    targetType: createForm.targetType.trim() || '三联靶',
    hourlyPrice: createForm.hourlyPrice
  })
  ElMessage.success('箭道已新增')
  createVisible.value = false
  createForm.laneNo = ''
  await loadLanes()
}

onMounted(async () => {
  options.value = await laneApi.options()
  certOptions.value = await certApi.options()
  await loadLanes()
  await loadMembers()
})
</script>

<style scoped>
.hall {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: linear-gradient(180deg, #fbfdfd, #f3f7f6);
  padding: 10px 12px 12px;
}

.hall-line {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 22px;
  font-size: 11px;
  letter-spacing: 3px;
  color: var(--ink-mute);
  border-top: 1px dashed #c9d6d4;
}

.hall-line-bottom {
  border-top: 2px solid var(--el-color-primary-light-5);
  margin-top: 6px;
}

.lane-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
  margin: 8px 0 6px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.lane {
  flex: 1 0 76px;
  min-width: 76px;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6px 4px 8px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s, border-color 0.15s;
}

.lane:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 14px rgba(0, 105, 92, 0.14);
}

.lane.is-pick {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

.target {
  width: 40px;
  height: 40px;
}

.strip {
  position: relative;
  flex: 1;
  width: 30px;
  min-height: 150px;
  margin: 6px 0 8px;
  border-radius: 6px;
  background: repeating-linear-gradient(180deg, #eef3f2 0 12px, #e4ecea 12px 24px);
  border: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
}

.strip-dist {
  font-size: 12px;
  font-weight: 700;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--ink-soft);
}

.strip-type {
  position: absolute;
  top: -20px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 10px;
  color: var(--ink-mute);
  white-space: nowrap;
}

.strip-who {
  position: absolute;
  bottom: 6px;
  font-size: 10px;
  color: #fff;
  background: rgba(0, 105, 92, 0.9);
  border-radius: 4px;
  padding: 1px 4px;
  white-space: nowrap;
}

.lane-foot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.lane-foot strong {
  font-size: 13px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.lane-status {
  font-size: 11px;
  padding: 0 6px;
  border-radius: 999px;
}

.lane-price {
  font-size: 11px;
  color: var(--ink-mute);
}

/* 状态配色 */
.lane.is-open .strip {
  background: repeating-linear-gradient(180deg, #e8f6ee 0 12px, #dcf1e6 12px 24px);
  border-color: #a8ddc0;
}

.lane.is-open .lane-status {
  background: #e4f6ec;
  color: #1f8a4c;
}

.lane.is-occupied .strip {
  background: repeating-linear-gradient(180deg, #dceeeb 0 12px, #cbe6e1 12px 24px);
  border-color: var(--el-color-primary-light-5);
}

.lane.is-occupied .lane-status {
  background: var(--el-color-primary);
  color: #fff;
}

.lane.is-maintenance .strip {
  background: repeating-linear-gradient(180deg, #f0f1f2 0 12px, #e6e8e9 12px 24px);
  border-color: #d3d7d8;
}

.lane.is-maintenance .lane-status {
  background: #eceef0;
  color: #8b9aa1;
}

.lane.is-maintenance {
  cursor: not-allowed;
  opacity: 0.85;
}

.lane.is-locked .strip {
  background: repeating-linear-gradient(180deg, #fde8e8 0 12px, #fbd9d9 12px 24px);
  border-color: #f2b8b8;
}

.lane.is-locked .lane-status {
  background: #c62828;
  color: #fff;
}

.lane.is-locked {
  cursor: not-allowed;
}

.legend {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--ink-soft);
}

.sw {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 3px;
  margin-right: 5px;
}

.sw-open {
  background: #1f8a4c;
}

.sw-busy {
  background: var(--el-color-primary);
}

.sw-grey {
  background: #b6bfc3;
}

.sw-lock {
  background: #c62828;
}

.c-open {
  color: #1f8a4c;
}

.c-busy {
  color: var(--el-color-primary);
}

.c-grey {
  color: #8b9aa1;
}

.c-lock {
  color: #c62828;
}

.dlg-tip {
  margin-bottom: 12px;
  padding: 8px 10px;
  font-size: 12px;
  border-radius: 6px;
  background: var(--el-color-primary-light-9);
  color: #04463c;
}

.cost {
  color: #c62828;
  font-size: 18px;
}
</style>
