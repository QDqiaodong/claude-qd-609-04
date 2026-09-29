<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">弓种能力认证 <em>证据窗口 · 版本化规则 · 教练复核 · 有效期与适用范围</em></h2>
      <div class="page-tools">
        <el-input v-model="operator" placeholder="当前教练姓名" style="width: 170px" />
      </div>
    </div>

    <div class="rule-tip">
      教练从会员<b>已完成</b>的计分回合中划定有明确起止的证据窗口，按弓种规则版本建立申请；系统按规则的每组箭数、射距与平均环标准预评出待复核结果。
      复核可<b>通过 / 驳回 / 要求补充证据</b>，通过后认证带有效期与适用范围；规则调整只发新版本，历史认证始终保留评定当时的规则快照与证据回合。
    </div>

    <el-tabs v-model="tab" class="cert-tabs">
      <!-- ============ 申请与复核 ============ -->
      <el-tab-pane label="申请与复核" name="apps">
        <!-- 建立申请 / 补充证据重新评定 -->
        <div class="panel">
          <div class="panel-title">
            {{ resubmitApp ? `补充证据重新评定 · ${resubmitApp.appNo}` : '建立认证申请' }}
            <small>选会员 → 选现行规则 → 划定证据窗口 → 勾选窗口内符合弓种 / 射距 / 箭数的已完成回合</small>
            <el-button v-if="resubmitApp" link type="info" size="small" @click="exitResubmit">退出补充模式</el-button>
          </div>

          <div v-if="resubmitApp" class="resubmit-banner">
            当前申请处于「待补充证据」：<b>{{ resubmitApp.memberName }} · {{ resubmitApp.bowTypeName }}
            {{ resubmitApp.distance }} 米</b>，重新评定仍沿用原规则快照
            <b>{{ currentRuleLabel(resubmitRuleId) }}</b>。上一轮复核意见：{{ resubmitApp.reviewNote }}
          </div>

          <div class="create-grid">
            <el-form label-width="92px" label-position="left">
              <el-form-item label="会员">
                <el-select
                  v-model="form.memberId"
                  :disabled="!!resubmitApp"
                  placeholder="请选择会员"
                  style="width: 100%"
                  @change="onMemberChange"
                >
                  <el-option v-for="m in members" :key="m.id" :label="`${m.name} · ${m.cardNo}`" :value="m.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="规则版本">
                <el-select
                  v-model="form.ruleId"
                  :disabled="!!resubmitApp"
                  placeholder="选择现行规则"
                  style="width: 100%"
                >
                  <el-option v-for="r in ruleOptions" :key="r.id" :label="ruleOptionLabel(r)" :value="r.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="证据窗口">
                <el-date-picker
                  v-model="form.range"
                  type="datetimerange"
                  range-separator="至"
                  start-placeholder="窗口开始"
                  end-placeholder="窗口结束"
                  value-format="YYYY-MM-DDTHH:mm:ss"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="预评结果">
                <template v-if="preview">
                  <el-tag size="small" :type="preview.pass ? 'success' : 'danger'" effect="dark">
                    {{ preview.pass ? '系统预评达标' : '系统预评未达标' }}
                  </el-tag>
                  <span class="muted" style="margin-left: 8px">
                    {{ preview.count }} 回合 / {{ preview.arrows }} 支 / 平均 {{ preview.avg }} 环
                    （标准 ≥{{ selectedRule.minRounds }} 回合且 ≥{{ selectedRule.minAverage }} 环）
                  </span>
                </template>
                <span v-else class="muted">勾选证据回合后自动预评</span>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="submitting" @click="submitForm">
                  {{ resubmitApp ? '补充证据并重新评定' : '建立申请（提交复核）' }}
                </el-button>
                <span class="muted" style="margin-left: 10px">
                  未完成 / 弓种不符 / 射距不符 / 箭数不符 / 跨出窗口的回合不能勾选
                </span>
              </el-form-item>
            </el-form>

            <div class="round-pick">
              <div class="pick-title">
                {{ form.memberId ? '该会员的计分回合' : '请先选择会员' }}
                <small class="muted">只采信已完成（SUBMITTED）回合</small>
              </div>
              <el-checkbox-group v-model="form.roundIds" class="round-list">
                <div v-for="r in memberRounds" :key="r.id" class="round-row">
                  <el-checkbox :value="r.id" :disabled="!roundState(r).ok">
                    <span class="mono">{{ r.roundNo }}</span>
                    <em class="round-meta">{{ r.bowTypeName }} · {{ r.distance }}m · {{ r.arrowCount }}支组
                      · {{ r.totalScore }}分 · {{ shortTime(r.startTime) }}</em>
                  </el-checkbox>
                  <el-tag size="small" :type="roundState(r).ok ? 'success' : 'info'" effect="plain">
                    {{ roundState(r).ok ? '符合' : roundState(r).reason }}
                  </el-tag>
                </div>
                <div v-if="form.memberId && !memberRounds.length" class="muted">该会员暂无计分回合</div>
              </el-checkbox-group>
            </div>
          </div>
        </div>

        <div class="board">
          <!-- 申请列表 -->
          <div class="panel list-panel">
            <div class="panel-title">
              认证申请
              <small>待复核 <b class="c-danger">{{ pendingCount }}</b> 笔</small>
              <el-checkbox v-model="onlyPending" label="只看待处理" class="only-active" />
            </div>
            <el-table
              :data="shownApps"
              row-key="id"
              highlight-current-row
              :current-row-key="selectedId"
              height="560"
              @current-change="onPick"
            >
              <el-table-column prop="appNo" label="申请号" width="140">
                <template #default="{ row }"><span class="mono">{{ row.appNo }}</span></template>
              </el-table-column>
              <el-table-column prop="memberName" label="会员" width="90" />
              <el-table-column label="弓种 / 射距" width="110">
                <template #default="{ row }">{{ row.bowTypeName }} {{ row.distance }}m</template>
              </el-table-column>
              <el-table-column label="预评" width="70">
                <template #default="{ row }">
                  <el-tag v-if="row.passFlag !== null" size="small" :type="row.passFlag ? 'success' : 'danger'" effect="plain">
                    {{ row.passFlag ? '达标' : '未达标' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="92">
                <template #default="{ row }">
                  <el-tag size="small" :type="appStatusType(row.displayStatus)" effect="dark">
                    {{ row.displayStatusName }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="有效期至" width="105">
                <template #default="{ row }">
                  <span v-if="row.validUntil" :class="{ 'muted': row.displayStatus === 'EXPIRED' }">
                    {{ shortDate(row.validUntil) }}
                  </span>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 申请详情 / 复核台 -->
          <div class="panel detail-panel">
            <template v-if="detail">
              <div class="panel-title">
                申请 {{ detail.summary.appNo }}
                <el-tag size="small" :type="appStatusType(detail.summary.displayStatus)" effect="dark">
                  {{ detail.summary.displayStatusName }}
                </el-tag>
                <small>{{ detail.summary.memberName }} · 第 {{ detail.summary.evalSeq }} 轮评定</small>
              </div>

              <!-- 适用范围与有效期 -->
              <div class="scope-box">
                <div>
                  <span class="scope-label">适用范围</span>
                  <b>{{ detail.summary.bowTypeName }} · {{ detail.summary.distance }} 米及以内射距</b>
                </div>
                <div v-if="detail.summary.validFrom">
                  <span class="scope-label">有效期</span>
                  {{ fmtTime(detail.summary.validFrom) }} ~ {{ fmtTime(detail.summary.validUntil) }}
                  <el-tag v-if="detail.summary.displayStatus === 'EXPIRED'" size="small" type="info" effect="dark">已过期</el-tag>
                </div>
                <div v-if="detail.summary.withdrawnAt">
                  <span class="scope-label">撤回</span>
                  {{ detail.summary.withdrawnBy }} · {{ fmtTime(detail.summary.withdrawnAt) }}
                </div>
              </div>

              <!-- 当前评定：规则快照 + 预评 -->
              <div v-if="detail.currentEval" class="eval-box" :class="detail.currentEval.passFlag ? 'is-pass' : 'is-fail'">
                <div class="eval-head">
                  <b>第 {{ detail.currentEval.evalSeq }} 轮系统预评</b>
                  <el-tag size="small" :type="detail.currentEval.passFlag ? 'success' : 'danger'" effect="dark">
                    {{ detail.currentEval.passFlag ? '达标' : '未达标' }}
                  </el-tag>
                  <el-tag size="small" type="info" effect="plain">
                    规则快照 {{ detail.currentEval.ruleCode }} v{{ detail.currentEval.ruleVersion }}
                  </el-tag>
                </div>
                <p class="eval-msg">{{ detail.currentEval.evalMessage }}</p>
                <div class="snap-line">
                  <span>每组 {{ detail.currentEval.snapGroupSize }} 支</span>
                  <span>≥ {{ detail.currentEval.snapMinRounds }} 回合</span>
                  <span>平均 ≥ {{ detail.currentEval.snapMinAverage }} 环/支</span>
                  <span>有效期 {{ detail.currentEval.snapValidityMonths }} 个月</span>
                  <span>窗口 {{ fmtTime(detail.currentEval.windowStart) }} ~ {{ fmtTime(detail.currentEval.windowEnd) }}</span>
                </div>
              </div>

              <!-- 证据回合 -->
              <div class="res-title">
                采信证据回合（{{ detail.currentEval ? detail.currentEval.rounds.length : 0 }}）
                <small class="muted">均为同会员已完成、弓种 / 射距 / 箭数匹配且落在窗口内的回合</small>
              </div>
              <el-table :data="detail.currentEval ? detail.currentEval.rounds : []" size="small" border>
                <el-table-column prop="roundNo" label="回合号" width="135" />
                <el-table-column prop="bowTypeName" label="弓种" width="90" />
                <el-table-column label="射距" width="70">
                  <template #default="{ row }">{{ row.distance }} 米</template>
                </el-table-column>
                <el-table-column prop="laneNo" label="箭道" width="70" />
                <el-table-column label="箭支" width="80">
                  <template #default="{ row }">{{ row.arrowCount }} 支 · {{ row.totalScore }} 环</template>
                </el-table-column>
                <el-table-column label="平均" width="70">
                  <template #default="{ row }">{{ Number(row.averageScore).toFixed(2) }}</template>
                </el-table-column>
                <el-table-column prop="startTime" label="时间" :formatter="(r) => fmtTime(r.startTime)" />
                <el-table-column prop="statusName" label="回合状态" width="80" />
              </el-table>

              <!-- 教练动作区 -->
              <div v-if="detail.summary.status === 'PENDING'" class="action-bar">
                <el-radio-group v-model="reviewForm.action">
                  <el-radio-button value="APPROVE">通过</el-radio-button>
                  <el-radio-button value="REJECT">驳回</el-radio-button>
                  <el-radio-button value="NEED_MORE">要求补充证据</el-radio-button>
                </el-radio-group>
                <el-input
                  v-model="reviewForm.note"
                  :placeholder="notePlaceholder"
                  style="width: 300px"
                  maxlength="500"
                />
                <el-button
                  :type="reviewForm.action === 'APPROVE' ? 'success' : reviewForm.action === 'REJECT' ? 'danger' : 'warning'"
                  :loading="acting"
                  @click="doDecide"
                >
                  提交复核结论
                </el-button>
              </div>

              <div v-else-if="detail.summary.status === 'NEED_MORE'" class="action-bar">
                <el-button type="warning" @click="startResubmit">补充证据重新评定</el-button>
                <span class="muted">可重新划定窗口并勾选回合，系统按原规则快照重新预评（历史评定保留）</span>
              </div>

              <div v-else-if="detail.summary.status === 'APPROVED' && detail.summary.displayStatus !== 'EXPIRED'" class="action-bar">
                <el-button type="danger" plain :loading="acting" @click="doWithdraw">撤回认证</el-button>
                <span class="muted">撤回后该会员的箭道开台与弓具租借入口立即不再承认该认证</span>
              </div>

              <!-- 历史评定轮次 -->
              <template v-if="detail.evalHistory.length">
                <div class="res-title" style="margin-top: 12px">历史评定（旧轮次保留原规则快照）</div>
                <div v-for="ev in detail.evalHistory" :key="ev.id" class="past-eval">
                  <el-tag size="small" effect="plain">第 {{ ev.evalSeq }} 轮</el-tag>
                  <el-tag size="small" type="info" effect="plain">{{ ev.ruleCode }} v{{ ev.ruleVersion }}</el-tag>
                  <el-tag size="small" :type="ev.passFlag ? 'success' : 'danger'" effect="dark">
                    {{ ev.passFlag ? '达标' : '未达标' }}
                  </el-tag>
                  <span class="muted">{{ ev.roundCount }} 回合 / {{ ev.arrowTotal }} 支 / 平均 {{ ev.avgScore }} 环</span>
                  <span class="muted">{{ fmtTime(ev.createdAt) }}</span>
                </div>
              </template>

              <!-- 状态轨迹 -->
              <div class="res-title" style="margin-top: 12px">状态轨迹</div>
              <el-timeline class="trail">
                <el-timeline-item
                  v-for="(log, index) in detail.logs"
                  :key="index"
                  :timestamp="fmtTime(log.createdAt)"
                  :type="log.action === 'APPROVE' ? 'success'
                    : ['REJECT', 'WITHDRAW', 'SUPERSEDE'].includes(log.action) ? 'danger'
                    : log.action === 'NEED_MORE' ? 'warning' : 'primary'"
                >
                  <b>{{ log.actionName }}</b>
                  <span class="muted"> · {{ log.operator }}（{{ log.roleName }}）</span>
                  <div v-if="log.detail" class="muted">{{ log.detail }}</div>
                </el-timeline-item>
              </el-timeline>
            </template>
            <el-empty v-else description="选择左侧一笔申请查看详情与复核" :image-size="80" />
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ 认证规则（版本化） ============ -->
      <el-tab-pane label="认证规则" name="rules">
        <div class="panel">
          <div class="panel-title">
            认证规则（版本化）
            <small>调整标准请发布新版本，旧版本停用但历史认证保留当时快照</small>
            <el-button size="small" type="primary" style="margin-left: auto" @click="openCreateRule">新建规则</el-button>
          </div>
          <el-table :data="rules" border>
            <el-table-column prop="ruleCode" label="规则谱系" width="140" />
            <el-table-column label="版本" width="70">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
            <el-table-column label="弓种" width="90">
              <template #default="{ row }">{{ row.bowTypeName }}</template>
            </el-table-column>
            <el-table-column label="射距" width="70">
              <template #default="{ row }">{{ row.distance }} 米</template>
            </el-table-column>
            <el-table-column label="通过标准" min-width="220">
              <template #default="{ row }">
                每组 {{ row.groupSize }} 支 · ≥{{ row.minRounds }} 回合 · 平均 ≥{{ row.minAverage }} 环
                · 有效 {{ row.validityMonths }} 月
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="dark">
                  {{ row.statusName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="note" label="说明" min-width="180" />
            <el-table-column label="发布" width="150">
              <template #default="{ row }">
                <span class="muted">{{ row.createdBy }} · {{ shortDate(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="row.status !== 'ACTIVE'" @click="openNewVersion(row)">
                  新版本
                </el-button>
                <el-button link type="warning" :disabled="row.status !== 'ACTIVE'" @click="doRetire(row)">
                  停用
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建规则 / 发布新版本 -->
    <el-dialog v-model="ruleDialogVisible" :title="ruleDialogMode === 'create' ? '新建认证规则（v1）' : `发布新版本 · ${ruleBase ? ruleBase.ruleCode : ''}`" width="520px">
      <el-form label-width="110px">
        <el-form-item label="弓种">
          <el-select v-model="ruleForm.bowType" :disabled="ruleDialogMode === 'version'" style="width: 100%">
            <el-option v-for="b in options.bowTypes || []" :key="b.code" :label="b.name" :value="b.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="射距（米）">
          <el-select v-model="ruleForm.distance" :disabled="ruleDialogMode === 'version'" style="width: 100%">
            <el-option v-for="d in options.distances || []" :key="d" :label="`${d} 米`" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="每组箭数">
          <el-radio-group v-model="ruleForm.groupSize">
            <el-radio-button v-for="s in options.groupSizes || [6, 12]" :key="s" :value="s">{{ s }} 支</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="最少回合数">
          <el-input-number v-model="ruleForm.minRounds" :min="1" :max="20" />
          <span class="muted" style="margin-left: 8px">证据窗口内</span>
        </el-form-item>
        <el-form-item label="平均环下限">
          <el-input-number v-model="ruleForm.minAverage" :min="0" :max="10" :step="0.1" :precision="2" />
          <span class="muted" style="margin-left: 8px">环 / 支</span>
        </el-form-item>
        <el-form-item label="有效期">
          <el-input-number v-model="ruleForm.validityMonths" :min="1" :max="60" />
          <span class="muted" style="margin-left: 8px">个月</span>
        </el-form-item>
        <el-form-item label="规则说明">
          <el-input v-model="ruleForm.note" maxlength="200" placeholder="如：平均环标准由 7.5 上调至 8.0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRule">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { certApi, memberApi, roundApi, shortTime, shortDate } from '../api'

const COACH_KEY = 'cert.coach'

const tab = ref('apps')
const operator = ref(localStorage.getItem(COACH_KEY) || '')
watch(operator, (value) => localStorage.setItem(COACH_KEY, value))

const options = ref({})
const rules = ref([])
const members = ref([])
const applications = ref([])
const detail = ref(null)
const selectedId = ref(null)
const onlyPending = ref(true)
const acting = ref(false)
const submitting = ref(false)

const memberRounds = ref([])
const resubmitApp = ref(null)
const reviewForm = reactive({ action: 'APPROVE', note: '' })

const form = reactive({ memberId: null, ruleId: null, range: [], roundIds: [] })

const activeRules = computed(() => options.value.activeRules || [])
// 补充证据重新评定时沿用原规则版本（可能已停用），规则下拉取全量版本；新建申请只能选现行版本
const ruleOptions = computed(() => (resubmitApp.value ? rules.value : activeRules.value))
const selectedRule = computed(() => ruleOptions.value.find((r) => r.id === form.ruleId) || null)
const resubmitRuleId = computed(() => {
  if (!resubmitApp.value || !detail.value) return null
  const ev = detail.value.currentEval
  return ev ? ev.ruleId : null
})

const pendingCount = computed(() => applications.value.filter((a) => ['PENDING', 'NEED_MORE'].includes(a.status)).length)
const shownApps = computed(() =>
  onlyPending.value
    ? applications.value.filter((a) => ['PENDING', 'NEED_MORE'].includes(a.status))
    : applications.value
)

const notePlaceholder = computed(() => {
  if (reviewForm.action === 'REJECT') return '必填：驳回原因'
  if (reviewForm.action === 'NEED_MORE') return '必填：需要补充哪些证据'
  if (detail.value && detail.value.currentEval && !detail.value.currentEval.passFlag) {
    return '预评未达标仍要通过时，必须填写理由'
  }
  return '复核意见（选填）'
})

const chosenRounds = computed(() => memberRounds.value.filter((r) => form.roundIds.includes(r.id)))
const preview = computed(() => {
  if (!selectedRule.value || !chosenRounds.value.length) return null
  const rs = chosenRounds.value
  const count = rs.length
  const arrows = rs.reduce((sum, r) => sum + r.arrowCount, 0)
  const score = rs.reduce((sum, r) => sum + r.totalScore, 0)
  const avg = arrows ? (score / arrows).toFixed(2) : '0.00'
  const pass = count >= selectedRule.value.minRounds && Number(avg) >= Number(selectedRule.value.minAverage)
  return { count, arrows, score, avg, pass }
})

function ruleOptionLabel(r) {
  return `${r.bowTypeName} ${r.distance}米 · v${r.version} · 每组${r.groupSize}支 · ≥${r.minRounds}回合 · 平均≥${r.minAverage} · 有效${r.validityMonths}月`
}

function currentRuleLabel(ruleId) {
  const hit = rules.value.find((r) => r.id === ruleId)
  return hit ? `${hit.ruleCode} v${hit.version}` : `规则 id=${ruleId}`
}

function appStatusType(status) {
  return {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
    NEED_MORE: 'warning',
    WITHDRAWN: 'info',
    SUPERSEDED: 'info',
    EXPIRED: 'info'
  }[status] || 'info'
}

const fmtTime = (value) => (value ? String(value).slice(0, 16) : '')

/** 单条回合对当前规则 / 窗口的可采信判定（服务端会再次逐条硬校验） */
function roundState(r) {
  if (r.status !== 'SUBMITTED') return { ok: false, reason: '未完成' }
  const rule = selectedRule.value
  if (!rule) return { ok: false, reason: '选规则' }
  if (r.bowType !== rule.bowType) return { ok: false, reason: '弓种不符' }
  if (r.distance !== rule.distance) return { ok: false, reason: '射距不符' }
  if (r.arrowCount !== rule.groupSize) return { ok: false, reason: '箭数不符' }
  if (!form.range || form.range.length !== 2) return { ok: false, reason: '选窗口' }
  const t = new Date(String(r.startTime).replace(' ', 'T')).getTime()
  if (t < new Date(form.range[0]).getTime() || t > new Date(form.range[1]).getTime()) {
    return { ok: false, reason: '跨出窗口' }
  }
  return { ok: true, reason: '符合' }
}

// 规则 / 窗口变化后，剔除已不符合的勾选
watch([() => form.ruleId, () => form.range], () => {
  form.roundIds = form.roundIds.filter((id) => {
    const r = memberRounds.value.find((item) => item.id === id)
    return r && roundState(r).ok
  })
}, { deep: true })

function requireOperator() {
  if (!operator.value.trim()) {
    ElMessage.warning('请先填写当前教练姓名')
    return false
  }
  return true
}

async function loadOptions() {
  options.value = await certApi.options()
}

async function loadRules() {
  rules.value = await certApi.rules()
}

async function loadApplications(keepSelection = true) {
  applications.value = await certApi.list()
  if (!keepSelection) return
  if (selectedId.value && applications.value.some((a) => a.id === selectedId.value)) return
  const first = applications.value.find((a) => ['PENDING', 'NEED_MORE'].includes(a.status)) || applications.value[0]
  selectedId.value = first ? first.id : null
}

async function loadDetail() {
  if (!selectedId.value) {
    detail.value = null
    return
  }
  detail.value = await certApi.detail(selectedId.value)
}

async function onMemberChange() {
  form.roundIds = []
  memberRounds.value = form.memberId ? await roundApi.list({ memberId: form.memberId }) : []
}

function onPick(row) {
  if (row && row.id !== selectedId.value) {
    selectedId.value = row.id
    reviewForm.action = 'APPROVE'
    reviewForm.note = ''
    loadDetail()
  }
}

function resetForm() {
  form.memberId = null
  form.ruleId = null
  form.range = []
  form.roundIds = []
  memberRounds.value = []
  resubmitApp.value = null
}

async function submitForm() {
  if (!requireOperator()) return
  if (resubmitApp.value) {
    if (!form.range || form.range.length !== 2 || !form.roundIds.length) {
      ElMessage.warning('请划定新的证据窗口并勾选至少一个回合')
      return
    }
    submitting.value = true
    try {
      detail.value = await certApi.resubmit(resubmitApp.value.id, {
        windowStart: form.range[0],
        windowEnd: form.range[1],
        roundIds: form.roundIds,
        operator: operator.value.trim()
      })
      ElMessage.success('已补充证据并重新评定，等待复核')
      resetForm()
      await loadApplications()
    } finally {
      submitting.value = false
    }
    return
  }

  if (!form.memberId) {
    ElMessage.warning('请选择会员')
    return
  }
  if (!form.ruleId) {
    ElMessage.warning('请选择认证规则版本')
    return
  }
  if (!form.range || form.range.length !== 2) {
    ElMessage.warning('请划定证据窗口起止时间')
    return
  }
  if (!form.roundIds.length) {
    ElMessage.warning('请勾选至少一个证据回合')
    return
  }
  submitting.value = true
  try {
    const created = await certApi.create({
      memberId: form.memberId,
      ruleId: form.ruleId,
      windowStart: form.range[0],
      windowEnd: form.range[1],
      roundIds: form.roundIds,
      operator: operator.value.trim()
    })
    ElMessage.success('申请已建立，系统预评结果为待复核')
    selectedId.value = created.summary.id
    resetForm()
    await loadApplications()
    await loadDetail()
  } finally {
    submitting.value = false
  }
}

async function doDecide() {
  if (!requireOperator()) return
  if (reviewForm.action !== 'APPROVE' && !reviewForm.note.trim()) {
    ElMessage.warning(reviewForm.action === 'REJECT' ? '驳回必须填写原因' : '要求补充证据必须填写说明')
    return
  }
  if (reviewForm.action === 'APPROVE'
      && detail.value.currentEval && !detail.value.currentEval.passFlag && !reviewForm.note.trim()) {
    ElMessage.warning('系统预评未达标，仍要通过时必须填写复核意见')
    return
  }
  acting.value = true
  try {
    detail.value = await certApi.decide(selectedId.value, {
      action: reviewForm.action,
      operator: operator.value.trim(),
      note: reviewForm.note.trim()
    })
    ElMessage.success('复核结论已记录')
    reviewForm.note = ''
    await loadApplications()
  } finally {
    acting.value = false
  }
}

function startResubmit() {
  const summary = detail.value.summary
  resubmitApp.value = summary
  tab.value = 'apps'
  form.memberId = summary.memberId
  form.ruleId = detail.value.currentEval.ruleId
  form.range = [summary.windowStart, summary.windowEnd]
  form.roundIds = []
  onMemberChange()
  ElMessage.info('已进入补充证据模式：重新划定窗口并勾选回合')
}

function exitResubmit() {
  resetForm()
}

async function doWithdraw() {
  if (!requireOperator()) return
  let reason = ''
  try {
    const ret = await ElMessageBox.prompt('撤回认证必须填写原因（撤回后相关入口立即失效）',
      `撤回认证 · ${detail.value.summary.appNo}`, {
        confirmButtonText: '确认撤回',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputValidator: (value) => (value && value.trim() ? true : '请填写撤回原因')
      })
    reason = ret.value
  } catch (e) {
    return
  }
  acting.value = true
  try {
    detail.value = await certApi.withdraw(selectedId.value, {
      operator: operator.value.trim(),
      reason: reason.trim()
    })
    ElMessage.success('认证已撤回')
    await loadApplications()
  } finally {
    acting.value = false
  }
}

// ---------- 规则管理 ----------
const ruleDialogVisible = ref(false)
const ruleDialogMode = ref('create')
const ruleBase = ref(null)
const ruleForm = reactive({ bowType: 'RECURVE', distance: 10, groupSize: 6, minRounds: 1, minAverage: 7, validityMonths: 12, note: '' })

function openCreateRule() {
  ruleDialogMode.value = 'create'
  ruleBase.value = null
  Object.assign(ruleForm, { bowType: 'RECURVE', distance: 10, groupSize: 6, minRounds: 1, minAverage: 7, validityMonths: 12, note: '' })
  ruleDialogVisible.value = true
}

function openNewVersion(row) {
  if (!requireOperator()) return
  ruleDialogMode.value = 'version'
  ruleBase.value = row
  Object.assign(ruleForm, {
    bowType: row.bowType,
    distance: row.distance,
    groupSize: row.groupSize,
    minRounds: row.minRounds,
    minAverage: Number(row.minAverage),
    validityMonths: row.validityMonths,
    note: ''
  })
  ruleDialogVisible.value = true
}

async function submitRule() {
  if (!requireOperator()) return
  const payload = { ...ruleForm, operator: operator.value.trim() }
  if (ruleDialogMode.value === 'create') {
    await certApi.createRule(payload)
    ElMessage.success('规则已发布（v1）')
  } else {
    await certApi.newVersion(ruleBase.value.id, payload)
    ElMessage.success('新版本已发布，旧版本停用且历史认证不变')
  }
  ruleDialogVisible.value = false
  await Promise.all([loadRules(), loadOptions()])
}

async function doRetire(row) {
  if (!requireOperator()) return
  try {
    await ElMessageBox.confirm(`确认停用 ${row.ruleCode} v${row.version}？停用后不能再用于新申请，已发出的认证不受影响。`,
      '停用规则', { type: 'warning' })
  } catch (e) {
    return
  }
  await certApi.retireRule(row.id, operator.value.trim())
  ElMessage.success('规则已停用')
  await Promise.all([loadRules(), loadOptions()])
}

// 多人协同：定时刷新列表与详情
let timer = null
onMounted(async () => {
  members.value = await memberApi.options()
  await Promise.all([loadOptions(), loadRules(), loadApplications(false)])
  if (!selectedId.value) {
    const first = applications.value.find((a) => ['PENDING', 'NEED_MORE'].includes(a.status)) || applications.value[0]
    selectedId.value = first ? first.id : null
  }
  await loadDetail()
  timer = setInterval(async () => {
    if (acting.value || submitting.value) return
    try {
      await loadApplications()
      await loadDetail()
      await loadRules()
    } catch (e) { /* 轮询失败不打断页面 */ }
  }, 5000)
})

onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.cert-tabs {
  margin-top: 4px;
}

.create-grid {
  display: grid;
  grid-template-columns: minmax(360px, 5fr) minmax(360px, 6fr);
  gap: 18px;
}

.resubmit-banner {
  margin-bottom: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  background: #fdf6ec;
  border: 1px solid #f5dab1;
  color: #8a6100;
  font-size: 12px;
  line-height: 1.7;
}

.round-pick {
  border: 1px dashed var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  max-height: 320px;
  overflow: auto;
}

.pick-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.round-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.round-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 3px 4px;
  border-radius: 6px;
}

.round-row:hover {
  background: #f4f7f7;
}

.round-meta {
  font-style: normal;
  font-size: 12px;
  color: var(--ink-mute);
  margin-left: 6px;
}

.only-active {
  margin-left: auto;
}

.board {
  display: grid;
  grid-template-columns: minmax(540px, 5fr) minmax(500px, 6fr);
  gap: 14px;
  margin-top: 14px;
  align-items: start;
}

.scope-box {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fbfdfd;
  font-size: 13px;
  margin-bottom: 10px;
}

.scope-label {
  display: inline-block;
  font-size: 12px;
  color: var(--ink-mute);
  margin-right: 6px;
}

.eval-box {
  border-radius: 10px;
  padding: 10px 12px;
  margin-bottom: 10px;
}

.eval-box.is-pass {
  border: 1px solid #a8ddc0;
  background: #f2fbf5;
}

.eval-box.is-fail {
  border: 1px solid #f2b8b8;
  background: #fdf3f3;
}

.eval-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.eval-msg {
  margin: 6px 0;
  font-size: 13px;
  line-height: 1.6;
}

.snap-line {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--ink-soft);
}

.res-title {
  font-size: 13px;
  font-weight: 600;
  margin: 10px 0 6px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.action-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px dashed var(--el-color-primary-light-5);
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
}

.past-eval {
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
  .create-grid {
    grid-template-columns: 1fr;
  }
}
</style>
