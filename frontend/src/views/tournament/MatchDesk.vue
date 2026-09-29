<template>
  <div class="desk">
    <!-- 选择赛事 / 场次 -->
    <div class="panel">
      <div class="panel-title">
        选择场次
        <small>只有双方队伍到齐（晋级来源已确定）的场次才能进入记分</small>
      </div>
      <div class="pick-row">
        <el-select v-model="identity.tournamentId" placeholder="赛事" style="width: 280px" @change="onTournamentChange">
          <el-option v-for="item in tournaments" :key="item.id" :value="item.id" :label="item.name" />
        </el-select>
        <el-select v-model="identity.matchId" placeholder="场次" style="width: 320px" @change="loadMatch">
          <el-option
            v-for="item in matchOptions"
            :key="item.id"
            :value="item.id"
            :disabled="!item.homeTeamName || !item.awayTeamName"
            :label="`第${item.matchNo}场 · ${item.roundName} · ${item.homeTeamName || item.homeSource || '待定'} VS ${item.awayTeamName || item.awaySource || '待定'}【${item.statusName}】`"
          />
        </el-select>
        <el-button @click="loadMatch">刷新场次</el-button>
        <el-button link type="primary" @click="$emit('go-bracket', identity.tournamentId)">去对阵树选择</el-button>
      </div>
    </div>

    <template v-if="match">
      <!-- 场次头部 -->
      <div class="panel">
        <div class="match-head">
          <div class="team-banner">
            <span class="team-banner-name">{{ match.match.homeTeamName || match.match.homeSource }}</span>
            <b class="team-banner-score">{{ match.match.homeScore }}</b>
            <span class="muted">X {{ match.match.homeXCount }}</span>
          </div>
          <div class="vs-block">
            <el-tag size="small" :type="matchStatusType(match.match.status)" effect="dark">
              {{ match.match.statusName }}
            </el-tag>
            <div class="vs-word">VS</div>
            <el-tag size="small" effect="plain">{{ match.match.roundName }} · 第{{ match.match.matchNo }}场</el-tag>
            <el-tag v-if="match.match.stage === 'SHOOTOFF'" size="small" type="warning" effect="dark">
              加赛箭 · 第 {{ currentShootoff?.roundNo || match.match.shootoffRounds }} 轮
            </el-tag>
          </div>
          <div class="team-banner is-away">
            <span class="team-banner-name">{{ match.match.awayTeamName || match.match.awaySource }}</span>
            <b class="team-banner-score">{{ match.match.awayScore }}</b>
            <span class="muted">X {{ match.match.awayXCount }}</span>
          </div>
        </div>

        <div v-if="locked" class="lock-tip">
          本场已确认，胜者「{{ winnerTeamName }}」已锁定并自动晋级，成绩不可倒退或改写。
        </div>
        <div v-else-if="!canScore" class="lock-tip is-readonly">
          当前身份为普通会员，仅可查看；记分、撤回最后一局、确认胜者请切换到裁判身份。
        </div>
      </div>

      <!-- 规定局：进行中的一局录入矩阵 -->
      <div v-if="!locked && match.match.stage === 'REGULATION'" class="panel">
        <div class="panel-title">
          第 {{ (match.draftEnd?.endNo) || (match.match.confirmedEnds + 1) }} 局记分
          <small>
            每名队员每局 {{ tournamentMeta.arrowsPerEnd }} 支 ·
            已录 {{ match.draftEnd?.recordedArrows || 0 }}/{{ match.draftEnd?.expectedArrows || '—' }} 支
          </small>
        </div>

        <div v-if="!match.draftEnd && match.match.confirmedEnds >= (match.match.totalEnds || 0)" class="muted">
          规定局已全部结束。
        </div>

        <div v-for="side in sides" :key="side.key" class="end-side">
          <div class="end-side-title">{{ side.team.teamName }}（{{ side.team.seedLabel }}）</div>
          <div class="member-grid">
            <div v-for="member in side.team.members" :key="member.memberId" class="member-row">
              <span class="member-name">{{ member.name }}</span>
              <div class="arrow-slots">
                <button
                  v-for="index in arrowSlots"
                  :key="index - 1"
                  type="button"
                  class="ring-chip slot-btn"
                  :class="[chipClass(arrowOf(member.memberId, index - 1)?.ring), { disabled: !canScore }]"
                  @click="pickArrow(member.memberId, index - 1)"
                >
                  {{ arrowOf(member.memberId, index - 1)?.ring || '·' }}
                </button>
              </div>
              <span class="member-subtotal mono">{{ memberTotal(member.memberId) }}</span>
            </div>
          </div>
        </div>

        <div class="desk-actions">
          <el-button :disabled="!canScore || match.ends.length === 0" @click="openRetract">
            撤回最后一局（需原因）
          </el-button>
          <el-dialog v-model="retractDialog" title="撤回最后一局" width="420px" append-to-body>
            <el-input v-model="retractReason" type="textarea" :rows="3" placeholder="请填写撤回原因（将记入操作轨迹）" />
            <template #footer>
              <el-button @click="retractDialog = false">取消</el-button>
              <el-button type="danger" @click="confirmRetract">确认撤回</el-button>
            </template>
          </el-dialog>
          <el-button
            type="primary"
            :disabled="!canScore || !draftComplete"
            @click="confirmEnd"
          >
            确认第 {{ (match.draftEnd?.endNo) || (match.match.confirmedEnds + 1) }} 局
          </el-button>
        </div>
        <div v-if="canScore && !draftComplete" class="muted action-hint">
          箭数未齐：每队三名队员须各射满 {{ arrowSlots }} 支才能确认本局。
        </div>
      </div>

      <!-- 加赛箭录入 -->
      <div v-if="!locked && match.match.stage === 'SHOOTOFF' && currentShootoff" class="panel">
        <div class="panel-title">
          加赛箭 · 第 {{ currentShootoff.roundNo }} 轮
          <small>每名队员各射一支 · 先比加赛总分，再比 X 数；仍平自动开下一轮</small>
        </div>
        <div v-for="side in sides" :key="side.key" class="end-side">
          <div class="end-side-title">{{ side.team.teamName }}</div>
          <div class="member-grid">
            <div v-for="member in side.team.members" :key="member.memberId" class="member-row">
              <span class="member-name">{{ member.name }}</span>
              <button
                type="button"
                class="ring-chip slot-btn wide"
                :class="[chipClass(shootoffArrowOf(member.memberId)?.ring), { disabled: !canScore }]"
                @click="pickShootoff(member.memberId)"
              >
                {{ shootoffArrowOf(member.memberId)?.ring || '·' }}
              </button>
            </div>
          </div>
        </div>
        <div class="so-running mono">
          本轮暂计 {{ currentShootoff.homeScore }} : {{ currentShootoff.awayScore }}
          （X {{ currentShootoff.homeXCount }}:{{ currentShootoff.awayXCount }}）
        </div>
        <div class="desk-actions">
          <el-button :disabled="!canScore || match.ends.length === 0" @click="openRetract">
            撤回最后一局（回退加赛，需原因）
          </el-button>
          <el-button type="primary" :disabled="!canScore || shootoffArrowsCount < 6" @click="lockShootoff">
            锁定本轮加赛
          </el-button>
        </div>
        <div v-if="canScore && shootoffArrowsCount < 6" class="muted action-hint">
          箭数未齐：加赛轮两队六名队员须各射一支（6 支）才能锁定。
        </div>
      </div>

      <!-- 待确认胜者 -->
      <div v-if="match.match.status === 'AWAIT_CONFIRM'" class="panel await-panel">
        <div class="await-text">
          已产生领先方：<b>{{ leaderName }}</b>
          （{{ match.match.stage === 'SHOOTOFF' ? '加赛决胜' : '规定局总分领先' }}）。
          裁判确认后锁定本场、自动带入下一轮；确认后不可倒退。
        </div>
        <el-button type="success" size="large" :disabled="!canScore" @click="confirmWinner">
          确认胜者（{{ leaderName }} 晋级）
        </el-button>
      </div>

      <!-- 已确认局明细 + 加赛历史 -->
      <div class="panel">
        <div class="panel-title">局分明细</div>
        <el-table :data="match.ends" size="small" border>
          <el-table-column label="局" width="60" prop="endNo" />
          <el-table-column label="主队得分" width="100">
            <template #default="{ row }">{{ row.homeScore }}（X {{ row.homeXCount }}）</template>
          </el-table-column>
          <el-table-column label="客队得分" width="100">
            <template #default="{ row }">{{ row.awayScore }}（X {{ row.awayXCount }}）</template>
          </el-table-column>
          <el-table-column label="箭值明细" min-width="260">
            <template #default="{ row }">
              <span v-for="(arrow, i) in row.arrows" :key="i" class="hist-arrow">
                <span class="ring-chip" :class="chipClass(arrow.ring)">{{ arrow.ring }}</span>
                <em class="muted">{{ arrow.memberName }}</em>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="确认人" width="100" prop="confirmedBy" />
        </el-table>

        <template v-if="match.shootoffRounds.length">
          <div class="panel-title" style="margin-top: 14px">加赛轮次</div>
          <el-table :data="match.shootoffRounds" size="small" border>
            <el-table-column label="轮" width="60" prop="roundNo" />
            <el-table-column label="主队" width="110">
              <template #default="{ row }">{{ row.homeScore }}（X {{ row.homeXCount }}）</template>
            </el-table-column>
            <el-table-column label="客队" width="110">
              <template #default="{ row }">{{ row.awayScore }}（X {{ row.awayXCount }}）</template>
            </el-table-column>
            <el-table-column label="箭值" min-width="220">
              <template #default="{ row }">
                <span v-for="(arrow, i) in row.arrows" :key="i" class="hist-arrow">
                  <span class="ring-chip" :class="chipClass(arrow.ring)">{{ arrow.ring }}</span>
                  <em class="muted">{{ arrow.memberName }}</em>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.winnerLabel" size="small" type="success" effect="dark">{{ row.winnerLabel }} 胜</el-tag>
                <el-tag v-else size="small" type="warning" effect="plain">仍平，继续</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </template>

    <el-empty v-else-if="tournaments.length" description="请选择一场双方到齐的场次" :image-size="80" />
    <el-empty v-else description="还没有赛事，请先到「赛事总览」建立" :image-size="80" />

    <!-- 环数键盘（规定局 & 加赛共用） -->
    <el-dialog v-model="keypadDialog" :title="keypadTitle" width="420px" append-to-body>
      <div class="keypad">
        <button
          v-for="ring in rings"
          :key="ring"
          type="button"
          class="key"
          :class="ringKeyClass(ring)"
          @click="chooseRing(ring)"
        >
          {{ ring }}
        </button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { tournamentApi } from '../../api'
import { actor, identity, isReferee } from './identity'
import { chipClass, matchStatusType } from './ui'

const tournaments = ref([])
const matchOptions = ref([])
const match = ref(null)
const tournamentMeta = ref({ arrowsPerEnd: 2 })

const keypadDialog = ref(false)
const keypadTitle = ref('选择箭值 X / 10 ~ 1 / M')
const pending = ref(null) // { kind:'end'|'so', memberId, shotIndex }
const rings = ['X', '10', '9', '8', '7', '6', '5', '4', '3', '2', '1', 'M']
const retractDialog = ref(false)
const retractReason = ref('')

const canScore = computed(() => isReferee() && !!identity.operator)
const locked = computed(() => match.value && match.value.match.status === 'CONFIRMED')

const sides = computed(() => {
  if (!match.value || !match.value.homeTeam || !match.value.awayTeam) return []
  return [
    { key: 'home', team: match.value.homeTeam },
    { key: 'away', team: match.value.awayTeam }
  ]
})

const arrowSlots = computed(() => tournamentMeta.value.arrowsPerEnd || 2)

const currentShootoff = computed(() => {
  const rounds = match.value?.shootoffRounds || []
  return rounds.filter((round) => round.status === 'DRAFT').slice(-1)[0] || null
})

const draftArrows = computed(() => match.value?.draftEnd?.arrows || [])
const shootoffArrowsCount = computed(() => currentShootoff.value?.arrows?.length || 0)
const draftComplete = computed(() => {
  const end = match.value?.draftEnd
  if (!end) return false
  return end.recordedArrows >= end.expectedArrows
})

const winnerTeamName = computed(() => {
  const m = match.value?.match
  if (!m) return ''
  if (m.winnerTeamId === m.homeTeamId) return m.homeTeamName
  if (m.winnerTeamId === m.awayTeamId) return m.awayTeamName
  return ''
})

const leaderName = computed(() => {
  const m = match.value?.match
  if (!m) return ''
  if (m.homeScore > m.awayScore) return m.homeTeamName
  if (m.awayScore > m.homeScore) return m.awayTeamName
  // 加赛决胜轮的胜者
  const decisive = (match.value.shootoffRounds || []).filter((r) => r.winnerLabel).slice(-1)[0]
  return decisive?.winnerLabel || ''
})

function arrowOf(memberId, shotIndex) {
  return draftArrows.value.find((a) => a.memberId === memberId && a.shotIndex === shotIndex)
}

function memberTotal(memberId) {
  return draftArrows.value.filter((a) => a.memberId === memberId).reduce((sum, a) => sum + a.ringValue, 0)
}

function shootoffArrowOf(memberId) {
  return (currentShootoff.value?.arrows || []).find((a) => a.memberId === memberId)
}

function ringKeyClass(ring) {
  if (ring === 'X') return 'is-x'
  if (ring === 'M') return 'is-miss'
  if (ring === '10') return 'is-ten'
  return ''
}

async function loadTournaments() {
  tournaments.value = await tournamentApi.list()
  if (identity.tournamentId && !tournaments.value.some((item) => item.id === identity.tournamentId)) {
    identity.tournamentId = tournaments.value[0]?.id || null
    identity.matchId = null
  } else if (!identity.tournamentId && tournaments.value.length) {
    identity.tournamentId = tournaments.value[0].id
  }
  if (identity.tournamentId) await loadMatchOptions()
}

async function onTournamentChange() {
  identity.matchId = null
  match.value = null
  await loadMatchOptions()
}

async function loadMatchOptions() {
  const detail = await tournamentApi.detail(identity.tournamentId)
  matchOptions.value = detail.matches
  tournamentMeta.value = detail.tournament
  // 默认选当前轮第一场可打的
  if (!identity.matchId) {
    const first = detail.matches.find((item) => item.readyToPlay) || detail.matches[0]
    if (first && first.homeTeamName && first.awayTeamName) {
      identity.matchId = first.id
      await loadMatch()
    }
  }
}

async function loadMatch() {
  if (!identity.matchId) {
    match.value = null
    return
  }
  match.value = await tournamentApi.match(identity.matchId)
  tournamentMeta.value = (await tournamentApi.detail(identity.tournamentId)).tournament
}

function pickArrow(memberId, shotIndex) {
  if (!canScore.value || locked.value) return
  pending.value = { kind: 'end', memberId, shotIndex }
  keypadDialog.value = true
}

function pickShootoff(memberId) {
  if (!canScore.value || locked.value) return
  pending.value = { kind: 'so', memberId }
  keypadDialog.value = true
}

async function chooseRing(ring) {
  const task = pending.value
  keypadDialog.value = false
  if (!task) return
  if (task.kind === 'end') {
    await tournamentApi.recordArrow(
      identity.matchId,
      actor({ memberId: task.memberId, shotIndex: task.shotIndex, ring })
    )
  } else {
    await tournamentApi.shootoffArrow(identity.matchId, actor({ memberId: task.memberId, ring }))
  }
  await loadMatch()
}

async function confirmEnd() {
  await tournamentApi.confirmEnd(identity.matchId, actor())
  ElMessage.success('本局已确认')
  await reloadBoth()
}

function openRetract() {
  retractReason.value = ''
  retractDialog.value = true
}

async function confirmRetract() {
  if (!retractReason.value.trim()) {
    ElMessage.warning('请填写撤回原因')
    return
  }
  await tournamentApi.retractEnd(identity.matchId, actor({ reason: retractReason.value.trim() }))
  ElMessage.success('已撤回最后一局并保留撤回轨迹')
  retractDialog.value = false
  await reloadBoth()
}

async function lockShootoff() {
  await tournamentApi.lockShootoff(identity.matchId, actor())
  ElMessage.success('加赛轮已锁定')
  await reloadBoth()
}

async function confirmWinner() {
  try {
    await ElMessageBox.confirm(`确认「${leaderName.value}」为本场胜者？确认后不可倒退，并自动带入下一轮。`, '确认胜者', {
      type: 'warning',
      confirmButtonText: '确认胜者',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  await tournamentApi.confirmWinner(identity.matchId, actor())
  ElMessage.success('胜者已确认并自动晋级')
  await reloadBoth()
}

async function reloadBoth() {
  await Promise.all([loadMatch(), loadMatchOptions()])
}

onMounted(loadTournaments)
</script>

<style scoped>
.pick-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.match-head {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 12px;
}

.team-banner {
  display: flex;
  align-items: center;
  gap: 10px;
}

.team-banner.is-away {
  justify-content: flex-end;
}

.team-banner-name {
  font-size: 17px;
  font-weight: 700;
}

.team-banner-score {
  font-size: 30px;
  color: var(--el-color-primary);
}

.vs-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.vs-word {
  font-size: 20px;
  font-weight: 800;
  color: var(--ink-mute);
}

.lock-tip {
  margin-top: 12px;
  padding: 10px 14px;
  border-radius: 8px;
  background: rgba(103, 194, 58, 0.1);
  color: var(--el-color-success);
  font-weight: 600;
}

.lock-tip.is-readonly {
  background: #f4f4f5;
  color: var(--ink-soft);
  font-weight: 400;
}

.end-side {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  margin-bottom: 10px;
}

.end-side-title {
  font-weight: 700;
  margin-bottom: 8px;
}

.member-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.member-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.member-name {
  width: 90px;
  font-size: 14px;
}

.arrow-slots {
  display: flex;
  gap: 8px;
}

.slot-btn {
  width: 42px;
  height: 38px;
  font-size: 16px;
  cursor: pointer;
  border: 1px solid var(--line);
  background: #fff;
}

.slot-btn.wide {
  width: 56px;
}

.slot-btn.disabled {
  cursor: not-allowed;
  opacity: 0.7;
}

.member-subtotal {
  font-size: 15px;
  color: var(--el-color-primary);
}

.desk-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 10px;
}

.action-hint {
  text-align: right;
  margin-top: 6px;
  font-size: 12px;
}

.so-running {
  text-align: center;
  margin: 6px 0;
  font-size: 15px;
}

.await-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  background: #fffdf5;
}

.await-text {
  font-size: 14px;
}

.hist-arrow {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: 10px;
}

.hist-arrow em {
  font-style: normal;
  font-size: 11px;
}

.keypad {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.key {
  height: 56px;
  font-size: 22px;
  font-weight: 700;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: #f7f9f9;
  cursor: pointer;
}

.key.is-x {
  background: #00695c;
  color: #fff;
}

.key.is-ten {
  background: #26a69a;
  color: #fff;
}

.key.is-miss {
  background: #eceff1;
  color: #90a4ae;
}
</style>
