<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">器材租赁 <em>在库 / 租出 / 维修 三态流转</em></h2>
      <div class="page-tools">
        <el-select v-model="filterType" clearable placeholder="按类型" style="width: 130px" @change="loadList">
          <el-option v-for="t in options.types || []" :key="t.code" :label="t.name" :value="t.code" />
        </el-select>
        <el-select v-model="filterStatus" clearable placeholder="按状态" style="width: 120px" @change="loadList">
          <el-option v-for="s in options.statuses || []" :key="s.code" :label="s.name" :value="s.code" />
        </el-select>
        <el-button type="primary" @click="createVisible = true">器材入库</el-button>
      </div>
    </div>

    <div class="rule-tip">
      只有「在库」器材可租借，租借按 1 小时租金从会员余额扣；「租出」可归还；「维修」中的器材不能租借，修好后回到在库。
      「安全锁定」由停射事件触发，锁定期间禁止租借、归还与切换维修状态，放行后恢复锁定前状态。
      <b>租借反曲弓 / 复合弓 / 传统弓器材，会员必须持有该弓种的当前有效认证；护具与箭支不受限制。</b>
    </div>

    <div class="panel">
      <el-table :data="list">
        <el-table-column prop="equipCode" label="器材编号" width="130" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.typeName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="brand" label="品牌" width="130" />
        <el-table-column label="租金" width="110">
          <template #default="{ row }">¥ {{ money(row.rentPrice) }} / 小时</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前租借人" min-width="180">
          <template #default="{ row }">
            <span v-if="row.renterName">{{ row.renterName }} · {{ shortTime(row.rentedAt) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.status !== 'INSTOCK'" @click="openRent(row)">租借</el-button>
            <el-button link type="success" :disabled="row.status !== 'RENTED'" @click="giveBack(row)">归还</el-button>
            <el-button link type="warning" :disabled="row.status === 'RENTED' || row.status === 'LOCKED'" @click="repair(row)">
              {{ row.status === 'REPAIR' ? '修好' : '报修' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="rentVisible" :title="`租借 · ${pick ? pick.equipCode : ''}`" width="400px">
      <div v-if="pick" class="dlg-tip">
        {{ pick.typeName }} · {{ pick.brand }} · ¥{{ money(pick.rentPrice) }}/小时，按 1 小时从余额扣费。
      </div>
      <div v-if="pick && isBowType(pick.type)" class="cert-line">
        <el-tag size="small" :type="bowCertOk ? 'success' : 'danger'" effect="dark">
          {{ bowCertOk ? `持有有效「${pick.typeName}」认证` : `无有效「${pick.typeName}」认证` }}
        </el-tag>
        <span class="muted" style="margin-left: 8px">{{ bowCertText }}</span>
      </div>
      <el-form label-width="70px">
        <el-form-item label="会员">
          <el-select v-model="rentMemberId" placeholder="请选择会员" style="width: 100%" @change="loadMemberCerts">
            <el-option
              v-for="m in members"
              :key="m.id"
              :label="`${m.name} · 余额 ¥${money(m.balance)}`"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rentVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRent">确认租借</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="器材入库" width="420px">
      <el-form label-width="80px">
        <el-form-item label="编号"><el-input v-model="createForm.equipCode" placeholder="如 E-RC-04" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="createForm.type" style="width: 100%">
            <el-option v-for="t in options.types || []" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="品牌"><el-input v-model="createForm.brand" /></el-form-item>
        <el-form-item label="租金">
          <el-input-number v-model="createForm.rentPrice" :min="0" :step="5" />
          <span class="muted" style="margin-left: 8px">元 / 小时</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { certApi, equipApi, memberApi, money, shortTime } from '../api'

const BOW_TYPES = ['RECURVE', 'COMPOUND', 'TRADITIONAL']

const list = ref([])
const members = ref([])
const options = ref({})
const memberCerts = ref([])
const filterType = ref(null)
const filterStatus = ref(null)

const rentVisible = ref(false)
const createVisible = ref(false)
const pick = ref(null)
const rentMemberId = ref(null)
const createForm = reactive({ equipCode: '', type: 'RECURVE', brand: '', rentPrice: 40 })

function isBowType(type) {
  return BOW_TYPES.includes(type)
}

const bowCertOk = computed(() => {
  if (!pick.value) return false
  return memberCerts.value.some((c) => c.bowType === pick.value.type)
})

const bowCertText = computed(() => {
  if (!pick.value) return ''
  const hit = memberCerts.value.find((c) => c.bowType === pick.value.type)
  return hit
    ? `${hit.distance} 米认证，有效期至 ${String(hit.validUntil).slice(0, 10)}`
    : '租借将被拒绝，请先在「认证」页完成该弓种认证'
})

function statusType(status) {
  if (status === 'INSTOCK') return 'success'
  if (status === 'RENTED') return 'primary'
  if (status === 'LOCKED') return 'danger'
  return 'info'
}

async function loadList() {
  const params = {}
  if (filterType.value) params.type = filterType.value
  else if (filterStatus.value) params.status = filterStatus.value
  list.value = await equipApi.list(params)
}

async function loadMembers() {
  members.value = await memberApi.options()
}

async function loadMemberCerts() {
  memberCerts.value = []
  if (rentMemberId.value) {
    const data = await certApi.memberCerts(rentMemberId.value)
    memberCerts.value = data.current || []
  }
}

async function openRent(row) {
  pick.value = row
  rentMemberId.value = members.value.length ? members.value[0].id : null
  memberCerts.value = []
  await loadMemberCerts()
  rentVisible.value = true
}

async function submitRent() {
  if (!rentMemberId.value) {
    ElMessage.warning('请选择租借会员')
    return
  }
  await equipApi.rent(pick.value.id, rentMemberId.value)
  ElMessage.success('租借成功')
  rentVisible.value = false
  await loadList()
  await loadMembers()
}

async function giveBack(row) {
  await equipApi.giveBack(row.id)
  ElMessage.success('已归还')
  await loadList()
}

async function repair(row) {
  await equipApi.repair(row.id)
  ElMessage.success(row.status === 'REPAIR' ? '已修好，回到在库' : '已报修')
  await loadList()
}

async function submitCreate() {
  if (!createForm.equipCode.trim() || !createForm.brand.trim()) {
    ElMessage.warning('请填写编号与品牌')
    return
  }
  await equipApi.create({
    equipCode: createForm.equipCode.trim(),
    type: createForm.type,
    brand: createForm.brand.trim(),
    rentPrice: createForm.rentPrice
  })
  ElMessage.success('器材已入库')
  createVisible.value = false
  createForm.equipCode = ''
  createForm.brand = ''
  await loadList()
}

onMounted(async () => {
  options.value = await equipApi.options()
  await loadList()
  await loadMembers()
})
</script>

<style scoped>
.dlg-tip {
  margin-bottom: 12px;
  padding: 8px 10px;
  font-size: 12px;
  border-radius: 6px;
  background: var(--el-color-primary-light-9);
  color: #04463c;
}

.cert-line {
  margin-bottom: 12px;
  padding: 8px 10px;
  font-size: 12px;
  border-radius: 6px;
  background: #f6f8f8;
  border: 1px solid var(--line);
}
</style>
