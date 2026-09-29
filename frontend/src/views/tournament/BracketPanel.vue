<template>
  <div class="br">
    <div v-if="!detail" class="panel">
      <el-empty description="请先在「赛事总览」建立或选择一场赛事" :image-size="80" />
    </div>

    <template v-else>
      <div class="panel">
        <div class="bracket-head">
          <h3 class="detail-name">
            {{ detail.tournament.name }}
            <el-tag size="small" :type="tourStatusType(detail.tournament.status)" effect="dark">
              {{ detail.tournament.statusName }}
            </el-tag>
            <el-tag v-if="detail.tournament.currentRoundName" size="small" type="warning" effect="plain">
              当前轮：{{ detail.tournament.currentRoundName }}
            </el-tag>
          </h3>
          <el-button-group>
            <el-button
              v-for="round in rounds"
              :key="round.no"
              size="small"
              :type="round.no === detail.tournament.currentRound ? 'primary' : ''"
            >
              {{ round.name }}
            </el-button>
          </el-button-group>
        </div>

        <!-- 对阵树：每轮一列，晋级来源由后端给出（第 N 场胜者），前端只渲染 -->
        <div class="tree">
          <div v-for="round in rounds" :key="round.no" class="tree-col">
            <div class="tree-col-head">{{ round.name }}</div>
            <div class="tree-col-body" :style="{ justifyContent: justify(round.no) }">
              <div
                v-for="match in matchesOf(round.no)"
                :key="match.id"
                class="match-card"
                :class="{
                  'is-current': match.currentMatch,
                  'is-confirmed': match.status === 'CONFIRMED',
                  'is-open': canOpen(match)
                }"
                @click="canOpen(match) && $emit('open-match', detail.tournament.id, match.id)"
              >
                <div class="match-card-no">第 {{ match.matchNo }} 场 · {{ match.statusName }}</div>
                <div class="match-side" :class="{ winner: match.winnerTeamId === match.homeTeamId }">
                  <span class="side-name">{{ match.homeTeamName || '待定' }}</span>
                  <span class="side-source">{{ match.homeTeamName ? match.homeSeedLabel : match.homeSource }}</span>
                  <b class="side-score">{{ match.homeTeamName ? match.homeScore : '' }}</b>
                </div>
                <div class="match-side" :class="{ winner: match.winnerTeamId === match.awayTeamId }">
                  <span class="side-name">{{ match.awayTeamName || '待定' }}</span>
                  <span class="side-source">{{ match.awayTeamName ? match.awaySeedLabel : match.awaySource }}</span>
                  <b class="side-score">{{ match.awayTeamName ? match.awayScore : '' }}</b>
                </div>
                <div v-if="match.shootoffRounds > 0" class="match-so">
                  加赛 {{ match.shootoffRounds }} 轮（X {{ match.homeXCount }}:{{ match.awayXCount }}）
                </div>
                <div class="match-foot">
                  <span class="muted small">局 {{ match.confirmedEnds }}/{{ match.totalEnds }}</span>
                  <el-button v-if="canOpen(match)" link type="primary" size="small">进入记分</el-button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="muted tree-legend">
          待开赛场次显示晋级来源（如「第 1 场胜者」）；上一场确认胜者后由后端自动带入对应槽位，刷新或重启不丢失。
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { tournamentApi } from '../../api'
import { identity } from './identity'
import { tourStatusType } from './ui'

const detail = ref(null)

const rounds = computed(() => {
  if (!detail.value) return []
  const total = detail.value.tournament.teamSize === 4 ? 2 : 3
  const names = detail.value.tournament.teamSize === 4
    ? ['首轮', '决赛']
    : ['首轮', '半决赛', '决赛']
  return names.map((name, index) => ({ no: index + 1, name }))
})

function matchesOf(roundNo) {
  return detail.value.matches.filter((match) => match.roundNo === roundNo)
}

/** 每轮卡片纵向分布，越往后越靠中，营造树形 */
function justify(roundNo) {
  return roundNo === 1 ? 'flex-start' : 'center'
}

function canOpen(match) {
  // 双方已到齐且未确认可进入记分；已确认也允许只读查看
  return match.status === 'CONFIRMED' || match.readyToPlay || match.status === 'AWAIT_CONFIRM'
}

async function load() {
  if (identity.tournamentId) {
    try {
      detail.value = await tournamentApi.detail(identity.tournamentId)
    } catch (e) {
      detail.value = null
    }
  } else {
    const list = await tournamentApi.list()
    if (list.length) {
      identity.tournamentId = list[0].id
      detail.value = await tournamentApi.detail(identity.tournamentId)
    }
  }
}

watch(
  () => identity.tournamentId,
  () => load()
)

onMounted(load)
defineExpose({ reload: load })
</script>

<style scoped>
.bracket-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.detail-name {
  margin: 0;
  font-size: 17px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.tree {
  display: flex;
  gap: 26px;
  overflow-x: auto;
  padding-bottom: 8px;
}

.tree-col {
  flex: 1;
  min-width: 240px;
  display: flex;
  flex-direction: column;
}

.tree-col-head {
  font-weight: 700;
  color: var(--el-color-primary);
  margin-bottom: 10px;
  text-align: center;
}

.tree-col-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  flex: 1;
}

.match-card {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px 10px;
  background: #fff;
  cursor: default;
  transition: box-shadow 0.15s, border-color 0.15s;
}

.match-card.is-open {
  cursor: pointer;
}

.match-card.is-open:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 4px 14px rgba(0, 105, 92, 0.15);
}

.match-card.is-current {
  border-color: var(--el-color-warning);
}

.match-card.is-confirmed {
  background: #f7fbf8;
}

.match-card-no {
  font-size: 11px;
  color: var(--ink-mute);
  margin-bottom: 6px;
  display: flex;
  justify-content: space-between;
}

.match-side {
  display: grid;
  grid-template-columns: 1fr auto;
  grid-template-areas:
    'name score'
    'source score';
  padding: 4px 6px;
  border-radius: 6px;
  column-gap: 8px;
}

.match-side + .match-side {
  border-top: 1px dashed var(--line);
}

.match-side.winner {
  background: rgba(103, 194, 58, 0.12);
}

.side-name {
  grid-area: name;
  font-weight: 600;
}

.side-source {
  grid-area: source;
  font-size: 11px;
  color: var(--ink-mute);
}

.side-score {
  grid-area: score;
  align-self: center;
  font-size: 18px;
}

.winner .side-name {
  color: var(--el-color-success);
}

.match-so {
  margin-top: 6px;
  font-size: 11px;
  color: var(--el-color-warning);
  text-align: center;
}

.match-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
}

.small {
  font-size: 11px;
}

.tree-legend {
  margin-top: 12px;
}
</style>
