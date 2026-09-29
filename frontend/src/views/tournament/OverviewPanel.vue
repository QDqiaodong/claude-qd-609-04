<template>
  <div class="ov">
    <!-- 建立赛事：仅值班经理 -->
    <div v-if="isManager" class="panel">
      <div class="panel-title">
        建立淘汰赛
        <small>选择参赛队数（4 / 8）、每场局数与每名队员每局箭数；报名阶段可调整队伍，开赛后冻结</small>
      </div>
      <div class="create-row">
        <el-input v-model="createForm.name" placeholder="赛事名称，如：2026 秋季团体淘汰赛" style="width: 260px" />
        <el-radio-group v-model="createForm.teamSize">
          <el-radio-button v-for="size in options.teamSizes || [4, 8]" :key="size" :value="size">
            {{ size }} 支队
          </el-radio-button>
        </el-radio-group>
        <el-select v-model="createForm.endsPerMatch" placeholder="每场局数" style="width: 130px">
          <el-option v-for="n in options.endsPerMatch || []" :key="n" :value="n" :label="`${n} 局/场`" />
        </el-select>
        <el-select v-model="createForm.arrowsPerEnd" placeholder="每局每人箭数" style="width: 150px">
          <el-option v-for="n in options.arrowsPerEnd || []" :key="n" :value="n" :label="`每人每局 ${n} 支`" />
        </el-select>
        <el-button type="primary" :loading="creating" @click="submitCreate">建立赛事</el-button>
      </div>
    </div>

    <!-- 赛事列表 -->
    <div class="panel">
      <div class="panel-title">
        赛事
        <small>共 {{ tournaments.length }} 场 · 进行中 {{ ongoingCount }} 场 · 已完赛 {{ finishedCount }} 场</small>
      </div>
      <el-table :data="tournaments" size="small" highlight-current-row @current-change="onPickTournament">
        <el-table-column prop="name" label="赛事" min-width="200" />
        <el-table-column label="规模" width="120">
          <template #default="{ row }">{{ row.teamSize }} 队 · {{ row.teamCount }} 已报</template>
        </el-table-column>
        <el-table-column label="局数" width="130">
          <template #default="{ row }">{{ row.endsPerMatch }} 局 · 每人每局 {{ row.arrowsPerEnd }} 支</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="tourStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前轮" width="90">
          <template #default="{ row }">{{ row.currentRoundName || '—' }}</template>
        </el-table-column>
        <el-table-column label="冠军" width="110">
          <template #default="{ row }">{{ row.championTeamName || '—' }}</template>
        </el-table-column>
        <el-table-column label="建立人" width="100">
          <template #default="{ row }">{{ row.createdBy }}</template>
        </el-table-column>
      </el-table>
    </div>

    <template v-if="detail">
      <!-- 赛事头部 + 参赛队管理 -->
      <div class="panel">
        <div class="detail-head">
          <div>
            <h3 class="detail-name">
              {{ detail.tournament.name }}
              <el-tag size="small" :type="tourStatusType(detail.tournament.status)" effect="dark">
                {{ detail.tournament.statusName }}
              </el-tag>
              <el-tag v-if="detail.tournament.currentRoundName" size="small" type="warning" effect="plain">
                当前：{{ detail.tournament.currentRoundName }}
              </el-tag>
              <el-tag v-if="detail.tournament.championTeamName" size="small" type="success" effect="dark">
                冠军：{{ detail.tournament.championTeamName }}
              </el-tag>
            </h3>
            <div class="muted">
              {{ detail.tournament.teamSize }} 支队 · 每场 {{ detail.tournament.endsPerMatch }} 局 ·
              每人每局 {{ detail.tournament.arrowsPerEnd }} 支 · 建立人 {{ detail.tournament.createdBy }}
            </div>
          </div>
          <div class="detail-actions">
            <el-button size="small" @click="$emit('open-bracket', detail.tournament.id)">查看对阵树</el-button>
            <el-button
              v-if="isManager && draft"
              size="small"
              type="primary"
              :disabled="detail.teams.length !== detail.tournament.teamSize"
              @click="startTournament"
            >
              开赛并生成对阵（{{ detail.teams.length }}/{{ detail.tournament.teamSize }} 队）
            </el-button>
          </div>
        </div>

        <div class="team-grid">
          <div v-for="team in detail.teams" :key="team.id" class="team-card">
            <div class="team-card-head">
              <strong>{{ team.teamName }}</strong>
              <el-tag size="small" :type="team.seedNo ? 'info' : 'warning'" effect="plain">
                {{ team.seedLabel }}
              </el-tag>
              <el-tag v-if="team.finalRank === 1" size="small" type="success" effect="dark">冠军</el-tag>
              <el-tag v-else-if="team.finalRank === 2" size="small" type="info" effect="dark">亚军</el-tag>
            </div>
            <div class="team-members">
              <span v-for="member in team.members" :key="member.memberId" class="team-member">
                {{ member.name }}<em class="muted">{{ member.cardNo }}</em>
              </span>
            </div>
            <div v-if="isManager && draft" class="team-card-actions">
              <el-button link type="primary" size="small" @click="editTeam(team)">调整</el-button>
              <el-popconfirm title="确定移除这支参赛队吗？" @confirm="removeTeam(team.id)">
                <template #reference>
                  <el-button link type="danger" size="small">移除</el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>

          <!-- 新增参赛队 -->
          <div v-if="isManager && draft && detail.teams.length < detail.tournament.teamSize" class="team-card is-add">
            <el-button type="primary" plain @click="openAdd">＋ 新增参赛队（三名现有会员）</el-button>
          </div>
        </div>
        <div v-if="draft" class="muted team-tip">
          报名中可调整参赛队与队员；同一会员不能同时加入两支队伍。开赛（生成对阵）后队伍与对阵关系将冻结。
        </div>
      </div>

      <!-- 全部场次：状态 / 局分 / 加赛轮 / 胜者 -->
      <div class="panel">
        <div class="panel-title">
          全部场次
          <small>当前轮「{{ detail.tournament.currentRoundName || '—' }}」· 后端生成与推进，页面不拼接对阵结果</small>
        </div>
        <el-table :data="detail.matches" size="small" border>
          <el-table-column label="场次" width="70">
            <template #default="{ row }">第{{ row.matchNo }}场</template>
          </el-table-column>
          <el-table-column label="轮次" width="80">
            <template #default="{ row }">{{ row.roundName }}</template>
          </el-table-column>
          <el-table-column label="主队" min-width="150">
            <template #default="{ row }">
              <span :class="{ 'is-winner': row.winnerTeamId === row.homeTeamId }">
                {{ row.homeTeamName || row.homeSource || '待定' }}
              </span>
              <em v-if="!row.homeTeamName" class="muted">（{{ row.homeSource }}）</em>
            </template>
          </el-table-column>
          <el-table-column label="比分" width="110" align="center">
            <template #default="{ row }">
              <b>{{ row.homeScore }} : {{ row.awayScore }}</b>
              <div class="muted small">X {{ row.homeXCount }}:{{ row.awayXCount }}</div>
            </template>
          </el-table-column>
          <el-table-column label="客队" min-width="150">
            <template #default="{ row }">
              <span :class="{ 'is-winner': row.winnerTeamId === row.awayTeamId }">
                {{ row.awayTeamName || row.awaySource || '待定' }}
              </span>
              <em v-if="!row.awayTeamName" class="muted">（{{ row.awaySource }}）</em>
            </template>
          </el-table-column>
          <el-table-column label="局" width="70" align="center">
            <template #default="{ row }">{{ row.confirmedEnds }}/{{ row.totalEnds }}</template>
          </el-table-column>
          <el-table-column label="加赛" width="70" align="center">
            <template #default="{ row }">{{ row.shootoffRounds > 0 ? row.shootoffRounds + ' 轮' : '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="matchStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="晋级" width="130">
            <template #default="{ row }">{{ winnerName(row) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button
                size="small"
                type="primary"
                link
                :disabled="!row.readyToPlay && row.status !== 'CONFIRMED' && row.status !== 'AWAIT_CONFIRM'"
                @click="$emit('open-match', detail.tournament.id, row.id)"
              >
                {{ row.status === 'CONFIRMED' ? '查看' : '进入记分' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 完整操作轨迹 -->
      <div class="panel">
        <div class="panel-title">
          完整操作轨迹
          <small>建队 / 开赛 / 确认局 / 撤回最后一局（含原因）/ 加赛 / 确认胜者 / 自动晋级，全程留痕</small>
        </div>
        <el-timeline class="trail">
          <el-timeline-item
            v-for="logItem in detail.logs"
            :key="logItem.id"
            :timestamp="fmtTime(logItem.createdAt)"
            :type="logType(logItem.action)"
            size="large"
          >
            <div class="log-line">
              <el-tag size="small" :type="roleTagType(logItem.role)" effect="plain">{{ logItem.roleName }}</el-tag>
              <b>{{ logItem.actionName }}</b>
              <span class="muted">{{ logItem.operator }}</span>
            </div>
            <div class="log-detail">{{ logItem.detail }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </template>

    <!-- 新增 / 调整参赛队对话框 -->
    <el-dialog v-model="teamDialog" :title="editingTeam ? '调整参赛队' : '新增参赛队'" width="520px">
      <el-form label-width="70px">
        <el-form-item label="队名">
          <el-input v-model="teamForm.teamName" placeholder="如：雷霆三队" maxlength="48" />
        </el-form-item>
        <el-form-item label="队员">
          <el-select
            v-model="teamForm.memberIds"
            multiple
            placeholder="恰好选择三名现有会员"
            style="width: 100%"
            :multiple-limit="3"
          >
            <el-option
              v-for="member in availableMembers"
              :key="member.id"
              :label="`${member.name} · ${member.cardNo}`"
              :value="member.id"
            />
          </el-select>
        </el-form-item>
        <div class="muted">每队固定三名会员；已在本赛事其他队伍的会员不再出现在可选列表。</div>
      </el-form>
      <template #footer>
        <el-button @click="teamDialog = false">取消</el-button>
        <el-button type="primary" @click="saveTeam">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { tournamentApi } from '../../api'
import { actor, identity, isManager } from './identity'
import { fmtTime, matchStatusType, roleTagType, tourStatusType } from './ui'

const props = defineProps({
  members: { type: Array, default: () => [] },
  options: { type: Object, default: () => ({}) },
  identity: { type: Object, required: true }
})
const emit = defineEmits(['open-bracket', 'open-match'])

const tournaments = ref([])
const detail = ref(null)
const creating = ref(false)
const teamDialog = ref(false)
const editingTeam = ref(null)

const createForm = reactive({
  name: '',
  teamSize: 4,
  endsPerMatch: 4,
  arrowsPerEnd: 2
})
const teamForm = reactive({ teamName: '', memberIds: [] })

const draft = computed(() => detail.value && detail.value.tournament.status === 'DRAFT')
const ongoingCount = computed(() => tournaments.value.filter((item) => item.status === 'ONGOING').length)
const finishedCount = computed(() => tournaments.value.filter((item) => item.status === 'FINISHED').length)

/** 已被其他队伍占用的会员不可再选（同一会员不能在两队） */
const availableMembers = computed(() => {
  if (!detail.value) return props.members
  const occupied = new Set()
  for (const team of detail.value.teams) {
    if (editingTeam.value && team.id === editingTeam.value.id) continue
    for (const member of team.members) occupied.add(member.memberId)
  }
  return props.members.filter((member) => !occupied.has(member.id))
})

async function loadList() {
  tournaments.value = await tournamentApi.list()
  const preferred = identity.tournamentId
  if (preferred && tournaments.value.some((item) => item.id === preferred)) {
    await loadDetail(preferred)
  } else if (tournaments.value.length) {
    await loadDetail(tournaments.value[0].id)
  } else {
    detail.value = null
  }
}

async function loadDetail(id) {
  identity.tournamentId = id
  detail.value = await tournamentApi.detail(id)
}

async function onPickTournament(row) {
  if (row) await loadDetail(row.id)
}

async function submitCreate() {
  if (!identity.operator) {
    ElMessage.warning('请先在右上角填写值班经理姓名')
    return
  }
  creating.value = true
  try {
    await tournamentApi.create(
      actor({
        name: createForm.name.trim(),
        teamSize: createForm.teamSize,
        endsPerMatch: createForm.endsPerMatch,
        arrowsPerEnd: createForm.arrowsPerEnd
      })
    )
    ElMessage.success('赛事已建立')
    createForm.name = ''
    await loadList()
  } finally {
    creating.value = false
  }
}

function openAdd() {
  editingTeam.value = null
  teamForm.teamName = ''
  teamForm.memberIds = []
  teamDialog.value = true
}

function editTeam(team) {
  editingTeam.value = team
  teamForm.teamName = team.teamName
  teamForm.memberIds = team.members.map((member) => member.memberId)
  teamDialog.value = true
}

async function saveTeam() {
  const tid = detail.value.tournament.id
  const payload = actor({ teamName: teamForm.teamName.trim(), memberIds: teamForm.memberIds })
  if (editingTeam.value) {
    await tournamentApi.updateTeam(tid, editingTeam.value.id, payload)
    ElMessage.success('参赛队已调整')
  } else {
    await tournamentApi.addTeam(tid, payload)
    ElMessage.success('参赛队已加入')
  }
  teamDialog.value = false
  await Promise.all([loadList(), loadDetail(tid)])
}

async function removeTeam(teamId) {
  const tid = detail.value.tournament.id
  await tournamentApi.removeTeam(tid, teamId, actor())
  ElMessage.success('参赛队已移除')
  await loadDetail(tid)
  tournaments.value = await tournamentApi.list()
}

async function startTournament() {
  const tid = detail.value.tournament.id
  await tournamentApi.start(tid, actor())
  ElMessage.success('已开赛，对阵由系统生成并冻结')
  await Promise.all([loadList(), loadDetail(tid)])
}

function winnerName(row) {
  if (row.winnerTeamId === row.homeTeamId) return row.homeTeamName
  if (row.winnerTeamId === row.awayTeamId) return row.awayTeamName
  if (row.homeTeamName && row.awayTeamName && row.status !== 'CONFIRMED') return '待定'
  return '等待晋级'
}

function logType(action) {
  if (action === 'RETRACT_END') return 'danger'
  if (action === 'CONFIRM_WINNER' || action === 'ADVANCE_AUTO' || action === 'CREATE') return 'success'
  if (action.startsWith('ENTER') || action.startsWith('CONTINUE')) return 'warning'
  return 'primary'
}

onMounted(loadList)
defineExpose({ reload: loadList })
</script>

<style scoped>
.create-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.detail-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}

.detail-name {
  margin: 0 0 6px;
  font-size: 17px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.team-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 10px;
}

.team-card {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fafcfc;
}

.team-card.is-add {
  display: flex;
  align-items: center;
  justify-content: center;
  border-style: dashed;
  background: #fff;
}

.team-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.team-members {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.team-member {
  font-size: 13px;
  display: flex;
  justify-content: space-between;
}

.team-card-actions {
  margin-top: 8px;
  text-align: right;
}

.team-tip {
  margin-top: 10px;
}

.is-winner {
  font-weight: 700;
  color: var(--el-color-success);
}

.small {
  font-size: 11px;
}

.log-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.log-detail {
  margin-top: 2px;
  font-size: 13px;
  color: var(--ink-soft);
}

.trail {
  padding-left: 6px;
  max-height: 420px;
  overflow: auto;
}
</style>
