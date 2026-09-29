<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        团体淘汰赛计分台
        <em>赛事总览 · 对阵树 · 单场记分（独立于日常计分）</em>
      </h2>
      <div class="page-tools">
        <el-radio-group v-model="identity.role" size="small">
          <el-radio-button value="MANAGER">值班经理</el-radio-button>
          <el-radio-button value="REFEREE">裁判</el-radio-button>
          <el-radio-button value="MEMBER">普通会员</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="identity.operator"
          :placeholder="`当前${roleName}姓名`"
          style="width: 170px"
          size="small"
        />
      </div>
    </div>

    <div class="rule-tip">
      值班经理建立赛事、选择 4 或 8 支队（每队固定三名现有会员）、设置每场局数，开赛后队伍与对阵冻结；
      裁判进入场次按局记 X/10~1/M，三人规定箭数录齐才能确认，录错只能撤回当前未结束比赛的最后一局并写明原因；
      平分进入加赛箭，每轮三人各射一支，先比加赛总分再比 X，仍平继续直到唯一胜者。普通会员只读。
      当前身份：<b>{{ roleName }}</b>{{ identity.operator ? `（${identity.operator}）` : '' }}
    </div>

    <el-tabs v-model="identity.tab" class="t-tabs">
      <el-tab-pane label="赛事总览" name="overview">
        <OverviewPanel
          :members="members"
          :options="options"
          :identity="identity"
          @open-bracket="goBracket"
          @open-match="goMatch"
        />
      </el-tab-pane>
      <el-tab-pane label="对阵树" name="bracket">
        <BracketPanel :identity="identity" @open-match="goMatch" />
      </el-tab-pane>
      <el-tab-pane label="单场计分区" name="match">
        <MatchDesk :identity="identity" @go-bracket="goBracket" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { memberApi, tournamentApi } from '../api'
import { identity } from './tournament/identity'
import OverviewPanel from './tournament/OverviewPanel.vue'
import BracketPanel from './tournament/BracketPanel.vue'
import MatchDesk from './tournament/MatchDesk.vue'

const members = ref([])
const options = ref({})

const roleName = computed(() => {
  const hit = (options.value.roles || []).find((item) => item.code === identity.role)
  return hit ? hit.name : identity.role
})

function goBracket(tournamentId) {
  identity.tournamentId = tournamentId
  identity.tab = 'bracket'
}

function goMatch(tournamentId, matchId) {
  identity.tournamentId = tournamentId
  identity.matchId = matchId
  identity.tab = 'match'
}

onMounted(async () => {
  try {
    options.value = await tournamentApi.options()
  } catch (e) {
    // 提示已由拦截器统一弹出
  }
  try {
    members.value = await memberApi.options()
  } catch (e) {
    members.value = []
  }
})
</script>

<style scoped>
.t-tabs {
  margin-top: 4px;
}

.t-tabs :deep(.el-tabs__content) {
  overflow: visible;
}
</style>
