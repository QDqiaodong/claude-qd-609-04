<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">课程预约 <em>卡片列表 · 满员禁用 · 等级门槛</em></h2>
      <div class="page-tools">
        <el-select v-model="filterLevel" clearable placeholder="按等级" style="width: 120px" @change="loadCourses">
          <el-option v-for="l in options.levels || []" :key="l.code" :label="l.name" :value="l.code" />
        </el-select>
        <el-button type="primary" @click="createVisible = true">发布课程</el-button>
      </div>
    </div>

    <div class="rule-tip">
      初级课所有会员可报；进阶课要求银卡及以上；竞技课要求金卡会员。同一会员不能重复报名，满员自动禁用报名按钮。
    </div>

    <div class="cards">
      <div v-for="course in courses" :key="course.id" class="course-card" :class="{ 'is-full': course.full }">
        <div class="card-head">
          <strong>{{ course.courseName }}</strong>
          <el-tag size="small" :type="levelType(course.level)" effect="dark">{{ course.levelName }}</el-tag>
        </div>

        <div class="card-meta">
          <span>教练 <b>{{ course.coach }}</b></span>
          <span>场地 <b>{{ course.venue }}</b></span>
          <span class="mono">{{ shortTime(course.classTime) }}</span>
        </div>

        <div class="seat">
          <div class="seat-bar"><i :style="{ width: seatPercent(course) + '%' }" :class="{ 'is-full': course.full }" /></div>
          <span class="mono">{{ course.enrolled }} / {{ course.capacity }} 人</span>
        </div>

        <div v-if="course.enrolls.length" class="chips">
          <span v-for="item in course.enrolls" :key="item.id" class="chip">
            {{ item.memberName }}
            <i class="chip-x" @click="cancelEnroll(course, item.memberId)">×</i>
          </span>
        </div>
        <div v-else class="muted">暂无人报名</div>

        <div class="card-foot">
          <span class="muted">
            门槛：{{ minLevelText(course.level) }}
          </span>
          <el-button
            size="small"
            type="primary"
            :disabled="course.full"
            @click="openEnroll(course)"
          >
            {{ course.full ? '已满员' : '报名' }}
          </el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="enrollVisible" :title="`报名 · ${pick ? pick.courseName : ''}`" width="400px">
      <el-form label-width="70px">
        <el-form-item label="会员">
          <el-select v-model="enrollMemberId" placeholder="请选择会员" style="width: 100%" @change="loadMemberCerts">
            <el-option
              v-for="m in members"
              :key="m.id"
              :label="`${m.name} · ${m.levelName}`"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="弓种认证">
          <div v-if="enrollCerts.length" class="cert-chips">
            <el-tag v-for="c in enrollCerts" :key="c.applicationId" size="small" type="success" effect="plain">
              {{ c.bowTypeName }} {{ c.distance }}米 · 至 {{ shortDate(c.validUntil) }}
            </el-tag>
          </div>
          <span v-else class="muted">当前无有效弓种认证（30/50 米课程训练与弓具租借将受限）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="enrollVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEnroll">确认报名</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="发布课程" width="440px">
      <el-form label-width="80px">
        <el-form-item label="课程名"><el-input v-model="createForm.courseName" /></el-form-item>
        <el-form-item label="教练"><el-input v-model="createForm.coach" /></el-form-item>
        <el-form-item label="等级">
          <el-select v-model="createForm.level" style="width: 100%">
            <el-option v-for="l in options.levels || []" :key="l.code" :label="l.name" :value="l.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="上课时间">
          <el-date-picker
            v-model="createForm.classTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="人数上限"><el-input-number v-model="createForm.capacity" :min="1" :max="30" /></el-form-item>
        <el-form-item label="场地"><el-input v-model="createForm.venue" placeholder="如 二号教学区" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { certApi, courseApi, memberApi, shortDate, shortTime } from '../api'

const courses = ref([])
const members = ref([])
const options = ref({})
const filterLevel = ref(null)
const enrollCerts = ref([])

const enrollVisible = ref(false)
const createVisible = ref(false)
const pick = ref(null)
const enrollMemberId = ref(null)
const createForm = reactive({
  courseName: '',
  coach: '',
  level: 'BASIC',
  classTime: '',
  capacity: 8,
  venue: '一号教学区'
})

function levelType(level) {
  if (level === 'COMPETITION') return 'danger'
  if (level === 'ADVANCED') return 'warning'
  return 'success'
}

function seatPercent(course) {
  return Math.min(100, (course.enrolled / course.capacity) * 100)
}

function minLevelText(level) {
  const hit = (options.value.levels || []).find((item) => item.code === level)
  return hit ? hit.minLevelName : '普通会员'
}

async function loadCourses() {
  courses.value = await courseApi.list(filterLevel.value ? { level: filterLevel.value } : {})
}

function openEnroll(course) {
  pick.value = course
  enrollMemberId.value = members.value.length ? members.value[0].id : null
  enrollCerts.value = []
  if (enrollMemberId.value) loadMemberCerts()
  enrollVisible.value = true
}

async function loadMemberCerts() {
  enrollCerts.value = []
  if (enrollMemberId.value) {
    const data = await certApi.memberCerts(enrollMemberId.value)
    enrollCerts.value = data.current || []
  }
}

async function submitEnroll() {
  if (!enrollMemberId.value) {
    ElMessage.warning('请选择会员')
    return
  }
  await courseApi.enroll(pick.value.id, enrollMemberId.value)
  ElMessage.success('报名成功')
  enrollVisible.value = false
  await loadCourses()
}

async function cancelEnroll(course, memberId) {
  await ElMessageBox.confirm('确认取消该会员的报名？', '取消报名', { type: 'warning' })
  await courseApi.cancel(course.id, memberId)
  ElMessage.success('已取消报名')
  await loadCourses()
}

async function submitCreate() {
  if (!createForm.courseName.trim() || !createForm.classTime) {
    ElMessage.warning('请填写课程名与上课时间')
    return
  }
  await courseApi.create({ ...createForm, courseName: createForm.courseName.trim(), coach: createForm.coach.trim() })
  ElMessage.success('课程已发布')
  createVisible.value = false
  createForm.courseName = ''
  await loadCourses()
}

onMounted(async () => {
  options.value = await courseApi.options()
  members.value = await memberApi.options()
  await loadCourses()
})
</script>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}

.course-card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 14px;
  box-shadow: 0 1px 2px rgba(0, 60, 50, 0.05);
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-top: 3px solid var(--el-color-primary);
}

.course-card.is-full {
  border-top-color: #c62828;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-head strong {
  font-size: 15px;
}

.card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--ink-mute);
}

.card-meta b {
  color: var(--ink);
  font-weight: 600;
}

.seat {
  display: flex;
  align-items: center;
  gap: 8px;
}

.seat-bar {
  flex: 1;
  height: 7px;
  border-radius: 999px;
  background: #e8efee;
  overflow: hidden;
}

.seat-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #4db6a5, #00695c);
}

.seat-bar i.is-full {
  background: linear-gradient(90deg, #ef7a76, #c62828);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  color: #04463c;
}

.chip-x {
  font-style: normal;
  cursor: pointer;
  color: var(--ink-mute);
  font-size: 13px;
  line-height: 1;
}

.chip-x:hover {
  color: #c62828;
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  padding-top: 4px;
  border-top: 1px dashed var(--line);
}

.cert-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
