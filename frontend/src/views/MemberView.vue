<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">会员管理 <em>开卡 · 充值 · 等级与余额 · 重复档案合并</em></h2>
      <div class="page-tools">
        <el-input
          v-model="cardEntryNo"
          placeholder="按会员卡号进入（旧卡自动跳转）"
          clearable
          style="width: 250px"
          @keyup.enter="enterByCard"
        >
          <template #append>
            <el-button @click="enterByCard">进入</el-button>
          </template>
        </el-input>
        <el-button type="primary" @click="createVisible = true">开卡</el-button>
      </div>
    </div>

    <div class="rule-tip">
      单次充值 100 ~ 10000 元；累计消费满 3000 元升银卡、满 10000 元升金卡，等级只升不降，折扣分别为 9.5 折 / 9 折。
      同一人误开两张卡时由值班经理使用「合并档案」：先预演核对差异与互斥项，确认后余额与全部业务关系迁到保留卡，来源卡封存不可再用。
    </div>

    <div class="panel">
      <el-table :data="members">
        <el-table-column prop="cardNo" label="会员卡号" width="150" />
        <el-table-column prop="name" label="姓名" width="110" />
        <el-table-column prop="phone" label="手机号" width="140" />
        <el-table-column label="等级" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="levelType(row.level)" effect="dark">{{ row.levelName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="折扣" width="90">
          <template #default="{ row }">{{ (Number(row.discount) * 10).toFixed(1) }} 折</template>
        </el-table-column>
        <el-table-column label="余额" width="120">
          <template #default="{ row }">¥ {{ money(row.balance) }}</template>
        </el-table-column>
        <el-table-column label="累计消费" width="120">
          <template #default="{ row }">¥ {{ money(row.totalSpend) }}</template>
        </el-table-column>
        <el-table-column prop="registerDate" label="注册日期" width="120" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'MERGED'" size="small" type="info" effect="plain">已合并封存</el-tag>
            <el-tag v-else size="small" type="success" effect="plain">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效认证" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.validCertCount > 0" size="small" type="success" effect="dark">
              {{ row.validCertCount }} 项有效
            </el-tag>
            <span v-else class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="回合 / 个人最好" min-width="170">
          <template #default="{ row }">
            {{ row.roundCount }} 个回合
            <el-button v-if="row.bestGroupCount > 0" link type="danger" @click="openBests(row)">
              {{ row.bestGroupCount }} 组 PB
            </el-button>
            <span v-else class="muted">暂无成绩</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'MERGED'">
              <el-button link type="warning" @click="openMergedRedirect(row)">合并去向</el-button>
              <el-button link type="primary" @click="openMergeTrail(row)">合并轨迹</el-button>
            </template>
            <template v-else>
              <el-button link type="primary" @click="openRecharge(row)">充值</el-button>
              <el-button link type="success" @click="openCerts(row)">认证</el-button>
              <el-button link type="warning" @click="startMerge(row)">合并档案</el-button>
              <el-button link @click="openMergeTrail(row)">轨迹</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createVisible" title="会员开卡" width="420px">
      <el-form label-width="80px">
        <el-form-item label="会员卡号"><el-input v-model="createForm.cardNo" placeholder="如 AR-2026-0013" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="createForm.name" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="createForm.phone" placeholder="11 位手机号" /></el-form-item>
        <el-form-item label="等级">
          <el-select v-model="createForm.level" style="width: 100%">
            <el-option label="普通会员" value="NORMAL" />
            <el-option label="银卡会员" value="SILVER" />
            <el-option label="金卡会员" value="GOLD" />
          </el-select>
        </el-form-item>
        <el-form-item label="初始余额"><el-input-number v-model="createForm.balance" :min="0" :step="100" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">开卡</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rechargeVisible" :title="`充值 · ${pick ? pick.name : ''}`" width="380px">
      <el-form label-width="80px">
        <el-form-item label="当前余额">
          <span v-if="pick">¥ {{ money(pick.balance) }}</span>
        </el-form-item>
        <el-form-item label="充值金额"><el-input-number v-model="rechargeAmount" :min="100" :max="10000" :step="100" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rechargeVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRecharge">确认充值</el-button>
      </template>
    </el-dialog>

    <!-- ============ 重复档案合并：选来源卡 + 预演 ============ -->
    <el-dialog
      v-model="mergeVisible"
      :title="`重复档案合并 · 保留卡 ${mergeForm.keep ? mergeForm.keep.cardNo + '（' + mergeForm.keep.name + '）' : ''}`"
      width="900px"
    >
      <el-form label-width="92px">
        <el-form-item label="来源档案">
          <el-select
            v-model="mergeForm.sourceId"
            filterable
            placeholder="选择同一会员误开的另一张卡（将被封存）"
            style="width: 100%"
          >
            <el-option
              v-for="m in mergeCandidates"
              :key="m.id"
              :label="`${m.cardNo}｜${m.name}｜${m.phone}｜余额 ¥${money(m.balance)}`"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="值班经理">
          <el-input v-model="mergeForm.operator" placeholder="值班经理姓名" style="width: 220px" />
        </el-form-item>
        <el-form-item label="合并原因">
          <el-input v-model="mergeForm.reason" type="textarea" :rows="2" maxlength="500" show-word-limit
            placeholder="如：会员本人确认两张卡为同一人，申请合并到保留卡" />
        </el-form-item>
      </el-form>

      <div v-if="preview" class="preview-box">
        <el-alert
          :title="preview.canMerge
            ? '预演无互斥项，可确认合并（确认将严格按此预演执行）'
            : `预演发现 ${preview.conflicts.length} 个互斥项，必须先处理，不能合并`"
          :type="preview.canMerge ? 'success' : 'error'"
          :closable="false"
          show-icon
          style="margin-bottom: 10px"
        />

        <!-- 互斥项清单 -->
        <div v-if="preview.conflicts.length" class="conflict-box">
          <div class="section-title danger">互斥项清单（系统不会自行挑选覆盖）</div>
          <el-table :data="preview.conflicts" size="small" border>
            <el-table-column prop="typeName" label="互斥类型" width="170" />
            <el-table-column prop="target" label="具体互斥项" min-width="220" />
            <el-table-column prop="detail" label="处理要求" min-width="240" />
          </el-table>
        </div>

        <!-- 两卡逐项对比 -->
        <el-table :data="sideCompareRows" size="small" border class="cmp-table">
          <el-table-column prop="label" label="核对项" width="150" />
          <el-table-column label="保留档案（保留）" min-width="280">
            <template #default="{ row }">
              <span v-html="row.keep"></span>
            </template>
          </el-table-column>
          <el-table-column label="来源档案（封存）" min-width="280">
            <template #default="{ row }">
              <span v-html="row.source"></span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 合并结果 -->
        <div v-if="preview.plan" class="plan-box">
          <div class="section-title">合并结果预演</div>
          <el-descriptions :column="3" size="small" border>
            <el-descriptions-item label="保留卡余额">¥ {{ money(preview.plan.keepBalanceBefore) }}</el-descriptions-item>
            <el-descriptions-item label="来源卡余额">¥ {{ money(preview.plan.sourceBalanceBefore) }}</el-descriptions-item>
            <el-descriptions-item label="合并后余额">
              <b class="ok">¥ {{ money(preview.plan.balanceAfter) }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="保留卡累计消费">¥ {{ money(preview.plan.keepSpendBefore) }}</el-descriptions-item>
            <el-descriptions-item label="来源卡累计消费">¥ {{ money(preview.plan.sourceSpendBefore) }}</el-descriptions-item>
            <el-descriptions-item label="合并后累计消费">
              <b class="ok">¥ {{ money(preview.plan.spendAfter) }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="保留卡原等级">{{ preview.plan.keepLevelBeforeName }}</el-descriptions-item>
            <el-descriptions-item label="按累计消费重算">{{ preview.plan.levelAfterName }}</el-descriptions-item>
            <el-descriptions-item label="最终等级（不降级）">
              <el-tag size="small" type="warning" effect="dark">{{ preview.plan.levelAfterName }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="报名迁移">
              {{ preview.plan.enrollmentsToMigrate.length }} 条
            </el-descriptions-item>
            <el-descriptions-item label="重复报名去重">
              <span :class="{ danger: preview.plan.duplicateEnrollments.length }">
                {{ preview.plan.duplicateEnrollments.length }} 条（保留较早一条）
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="未结束回合迁移">
              {{ preview.plan.openRoundsToMigrate.length }} 个
            </el-descriptions-item>
            <el-descriptions-item label="历史成绩迁移">
              {{ preview.plan.historyRoundsToMigrate }} 个（箭值 / 完成时间不改写）
            </el-descriptions-item>
            <el-descriptions-item label="当前器材租借迁移">
              {{ preview.plan.rentalsToMigrate.length }} 件
            </el-descriptions-item>
            <el-descriptions-item label="认证申请迁移">{{ preview.plan.certApplicationsToMigrate }} 笔（评定快照保留）</el-descriptions-item>
          </el-descriptions>

          <div v-if="preview.plan.duplicateEnrollments.length" class="sub-block">
            <div class="section-title">重复报名去重明细（同一课程保留报名较早的一条）</div>
            <el-table :data="preview.plan.duplicateEnrollments" size="small" border>
              <el-table-column prop="courseName" label="课程" min-width="180" />
              <el-table-column label="保留（较早）" width="220">
                <template #default="{ row }">
                  {{ row.keptCardNo }} · {{ fmtTime(row.keptEnrollTime) }}
                </template>
              </el-table-column>
              <el-table-column label="删除（较晚）" width="220">
                <template #default="{ row }">
                  <span class="danger">{{ row.droppedCardNo }} · {{ fmtTime(row.droppedEnrollTime) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="sub-block">
            <div class="section-title">个人最好重新归组预演（迁移后按保留会员核对，仅改 PB 标记）</div>
            <el-table :data="preview.plan.personalBestGroups" size="small" border max-height="220">
              <el-table-column prop="groupLabel" label="可比组别（弓种 · 射距 · 箭数）" min-width="200" />
              <el-table-column prop="roundCount" label="组内回合" width="80" />
              <el-table-column label="最好回合" width="130">
                <template #default="{ row }">
                  {{ row.winnerRoundNo }}
                  <el-tag size="small" :type="row.winnerSide === 'SOURCE' ? 'warning' : 'success'" effect="plain">
                    {{ row.winnerSide === 'SOURCE' ? '来自来源卡' : '来自保留卡' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="winnerTotalScore" label="总分" width="70" />
              <el-table-column label="完成时间" width="135">
                <template #default="{ row }">{{ fmtTime(row.winnerCompletedAt) }}</template>
              </el-table-column>
            </el-table>
            <div class="muted small">已完成回合、认证历史与个人最好标记均保留原始内容迁移；箭值、完成时间、认证规则快照不会被改写。</div>
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button :loading="previewLoading" @click="doPreview">
          {{ preview ? '重新预演' : '生成合并预演' }}
        </el-button>
        <el-popconfirm
          v-if="preview && preview.canMerge"
          title="确认按当前预演合并？来源卡将立即封存，不可撤销"
          confirm-button-text="确认合并"
          cancel-button-text="再想想"
          @confirm="doConfirm"
        >
          <template #reference>
            <el-button type="danger" :loading="confirmLoading">确认合并</el-button>
          </template>
        </el-popconfirm>
      </template>
    </el-dialog>

    <!-- ============ 已封存卡：合并去向（旧卡入口跳转） ============ -->
    <el-dialog v-model="redirectVisible" title="旧卡号入口 · 已合并封存" width="520px">
      <div v-if="redirect" class="redirect-box">
        <el-alert
          :title="`卡号 ${redirect.cardNo}（${redirect.name}）是已封存的历史档案`"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        />
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="合并时间">{{ fmtTime(redirect.mergedAt) }}</el-descriptions-item>
          <el-descriptions-item label="操作经理">{{ redirect.mergedBy || '—' }}</el-descriptions-item>
          <el-descriptions-item label="合并原因">{{ redirect.mergeReason || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="redirect.redirectCardNo" label="请使用保留档案">
            <el-link type="primary" @click="goRedirectMember">
              {{ redirect.redirectCardNo }}（{{ redirect.redirectName }}）→ 打开保留档案
            </el-link>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-dialog>

    <!-- ============ 合并轨迹 ============ -->
    <el-dialog v-model="trailVisible" :title="`合并轨迹 · ${trailMember ? trailMember.name : ''}`" width="780px">
      <div v-loading="trailLoading">
        <el-empty v-if="!trailList.length" description="该会员暂无合并记录" :image-size="60" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="item in trailList"
            :key="item.mergeId"
            :type="trailTagType(item.status)"
            :timestamp="`预演 ${fmtTime(item.previewedAt)}${item.confirmedAt ? ' ｜ 确认 ' + fmtTime(item.confirmedAt) : ''}`"
          >
            <div class="trail-head">
              <el-tag size="small" :type="trailTagType(item.status)" effect="dark">{{ item.statusName }}</el-tag>
              <b>合并单 {{ item.mergeNo }}</b>
            </div>
            <div class="muted small">
              保留卡 {{ item.keepCardNo }}（{{ item.keepName }}）＋ 来源卡 {{ item.sourceCardNo }}（{{ item.sourceName }}）
            </div>
            <div class="muted small">原因：{{ item.reason }}</div>
            <div class="muted small">预演：{{ item.previewOperator }} ｜ 确认：{{ item.confirmedBy || '—' }}</div>
            <el-button link type="primary" @click="openMergeDetail(item.mergeId)">查看预演差异与处理明细</el-button>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-dialog>

    <!-- 合并单详情（预演快照 + 轨迹日志） -->
    <el-dialog v-model="detailVisible" title="合并单详情 · 预演快照与操作日志" width="860px">
      <div v-if="mergeDetail">
        <el-descriptions :column="2" size="small" border style="margin-bottom: 10px">
          <el-descriptions-item label="合并单号">{{ mergeDetail.summary.mergeNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="trailTagType(mergeDetail.summary.status)" effect="dark">
              {{ mergeDetail.summary.statusName }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="保留卡">
            {{ mergeDetail.summary.keepCardNo }}（{{ mergeDetail.summary.keepName }}）
          </el-descriptions-item>
          <el-descriptions-item label="来源卡">
            {{ mergeDetail.summary.sourceCardNo }}（{{ mergeDetail.summary.sourceName }}）
          </el-descriptions-item>
        </el-descriptions>
        <el-timeline>
          <el-timeline-item
            v-for="log in mergeDetail.logs"
            :key="log.id"
            :timestamp="fmtTime(log.createdAt)"
            placement="top"
          >
            <b>{{ log.actionName }}</b> · {{ log.operator }}
            <div class="muted small">{{ log.detail }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-dialog>

    <!-- 个人最好：按可比组别（弓种 + 射距 + 箭数）各自独立，每组至多一条 -->
    <el-dialog v-model="bestsVisible" :title="`个人最好成绩 · ${bestsPick ? bestsPick.name : ''}`" width="760px">
      <div v-loading="bestsLoading">
        <div class="rule-tip">
          个人最好按可比组别独立计算：同一会员在同一弓种、同一射距、同一规定箭数内比较；
          6 支与 12 支、不同弓种 / 射距互不影响。同分保留最早完成的回合。
        </div>
        <el-table :data="bestsData.groups || []" border>
          <el-table-column label="可比组别" min-width="200">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.group.label }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最好回合" width="150">
            <template #default="{ row }">
              <el-link type="primary" @click="openBestRound(row.bestRound.id)">
                {{ row.bestRound.roundNo }}
              </el-link>
            </template>
          </el-table-column>
          <el-table-column label="完成时间" width="135">
            <template #default="{ row }">{{ shortTime(row.bestRound.completedAt || row.bestRound.startTime) }}</template>
          </el-table-column>
          <el-table-column prop="roundCount" label="组内回合" width="80" />
          <el-table-column label="总分" width="70">
            <template #default="{ row }">
              <b class="best-score">{{ row.bestRound.totalScore }}</b>
            </template>
          </el-table-column>
          <el-table-column label="平均环" width="80">
            <template #default="{ row }">{{ Number(row.bestRound.averageScore).toFixed(2) }}</template>
          </el-table-column>
        </el-table>

        <!-- 打开某个最好回合：完整箭支明细仍按 shot_index 顺序呈现 -->
        <div v-if="bestDetail" class="best-detail">
          <div class="best-detail-head">
            <b>{{ bestDetail.round.roundNo }}</b>
            <el-tag size="small" type="danger" effect="dark">个人最好</el-tag>
            <span class="muted">总分 {{ bestDetail.round.totalScore }} · 完整箭支明细</span>
          </div>
          <div class="best-shots">
            <span v-for="arrow in bestDetail.arrows" :key="arrow.shotIndex" class="best-shot">
              <em>第{{ arrow.shotIndex }}支</em>
              <b>{{ arrow.ring }}</b>
            </span>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- 弓种能力认证：当前有效认证 + 历史记录 -->
    <el-dialog v-model="certVisible" :title="`弓种能力认证 · ${certPick ? certPick.name : ''}`" width="720px">
      <div v-loading="certLoading">
        <div class="cert-section-title">当前有效认证（{{ certData.current.length }}）</div>
        <div v-if="certData.current.length" class="cert-current">
          <div v-for="c in certData.current" :key="c.applicationId" class="cert-card">
            <div class="cert-card-head">
              <b>{{ c.bowTypeName }} · {{ c.distance }} 米</b>
              <el-tag size="small" type="success" effect="dark">有效</el-tag>
            </div>
            <div class="muted">适用范围：{{ c.distance }} 米及以内射距</div>
            <div class="muted">规则快照：{{ c.ruleCode }} v{{ c.ruleVersion }}</div>
            <div class="muted">有效期：{{ fmtTime(c.validFrom) }} ~ {{ fmtTime(c.validUntil) }}</div>
          </div>
        </div>
        <el-empty v-else description="暂无有效认证（过期 / 撤回 / 被取代的认证不能用于箭道与弓具入口）" :image-size="60" />

        <div class="cert-section-title">认证历史记录（{{ certData.history.length }}）</div>
        <el-table :data="certData.history" size="small" border max-height="260">
          <el-table-column prop="appNo" label="申请号" width="130" />
          <el-table-column label="弓种 / 射距" width="120">
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
              <el-tag size="small" :type="certStatusType(row.displayStatus)" effect="dark">{{ row.displayStatusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="复核" width="120">
            <template #default="{ row }">
              <span v-if="row.reviewedBy" class="muted">{{ row.reviewedBy }} · {{ fmtTime(row.reviewedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="有效期至" width="105">
            <template #default="{ row }">{{ row.validUntil ? shortDate(row.validUntil) : '—' }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { certApi, memberApi, mergeApi, money, roundApi, shortDate, shortTime } from '../api'

const members = ref([])
const createVisible = ref(false)
const rechargeVisible = ref(false)
const certVisible = ref(false)
const certLoading = ref(false)
const pick = ref(null)
const certPick = ref(null)
const rechargeAmount = ref(200)
const certData = ref({ current: [], history: [] })
const createForm = reactive({ cardNo: '', name: '', phone: '', level: 'NORMAL', balance: 200 })

// 个人最好（按可比组别）
const bestsVisible = ref(false)
const bestsLoading = ref(false)
const bestsPick = ref(null)
const bestsData = ref({ groups: [] })
const bestDetail = ref(null)

// 旧卡号进入
const cardEntryNo = ref('')

// 合并预演
const mergeVisible = ref(false)
const previewLoading = ref(false)
const confirmLoading = ref(false)
const mergeForm = reactive({ keep: null, sourceId: null, operator: '', reason: '' })
const preview = ref(null)

// 封存卡跳转
const redirectVisible = ref(false)
const redirect = ref(null)

// 合并轨迹
const trailVisible = ref(false)
const trailLoading = ref(false)
const trailMember = ref(null)
const trailList = ref([])
const detailVisible = ref(false)
const mergeDetail = ref(null)

const mergeCandidates = computed(() =>
  members.value.filter((m) => m.status !== 'MERGED' && (!mergeForm.keep || m.id !== mergeForm.keep.id)))

const sideCompareRows = computed(() => {
  if (!preview.value) return []
  const k = preview.value.keep
  const s = preview.value.source
  const money2 = (v) => `¥ ${money(v)}`
  const enrollText = (list) => list.length
    ? list.map((e) => `${e.courseName}（${fmtTime(e.enrollTime)}）`).join('<br/>')
    : '<span class="muted">无</span>'
  const openText = (list) => list.length
    ? list.map((r) => `${r.roundNo} · ${r.laneNo} · ${r.bowTypeName} · ${r.statusName}`).join('<br/>')
    : '<span class="muted">无</span>'
  const historyText = (list) => list.length
    ? list.map((r) => `${r.roundNo} · ${r.bowTypeName}${r.distance ? ' ' + r.distance + '米' : ''} · ${r.arrowCount}支 · ${r.totalScore}分${r.personalBest ? ' 🏅PB' : ''}`).join('<br/>')
    : '<span class="muted">无</span>'
  const rentText = (list) => list.length
    ? list.map((r) => `${r.equipCode} · ${r.typeName} · ${r.brand}（${fmtTime(r.rentedAt)}）`).join('<br/>')
    : '<span class="muted">无在租器材</span>'
  const certAppText = (list) => list.length
    ? list.map((c) => `${c.appNo} · ${c.bowTypeName} ${c.distance}米 · ${c.displayStatusName}`).join('<br/>')
    : '<span class="muted">无</span>'
  const validCertText = (list) => list.length
    ? list.map((c) => `${c.bowTypeName} ${c.distance}米 · ${c.appNo}（至 ${shortDate(c.validUntil)}）`).join('<br/>')
    : '<span class="muted">无有效认证</span>'
  return [
    { label: '余额', keep: money2(k.balance), source: money2(s.balance) },
    { label: '累计消费', keep: money2(k.totalSpend), source: money2(s.totalSpend) },
    { label: '等级', keep: k.levelName, source: s.levelName },
    { label: '课程报名', keep: enrollText(k.enrollments), source: enrollText(s.enrollments) },
    { label: '未结束计分回合', keep: openText(k.openRounds), source: openText(s.openRounds) },
    { label: '历史成绩（已完成）', keep: historyText(k.historyRounds), source: historyText(s.historyRounds) },
    { label: '当前器材租借', keep: rentText(k.rentals), source: rentText(s.rentals) },
    { label: '认证申请', keep: certAppText(k.certApplications), source: certAppText(s.certApplications) },
    { label: '有效认证', keep: validCertText(k.validCerts), source: validCertText(s.validCerts) }
  ]
})

const fmtTime = (value) => (value ? String(value).slice(5, 16) : '')

function levelType(level) {
  if (level === 'GOLD') return 'warning'
  if (level === 'SILVER') return 'info'
  return 'success'
}

function certStatusType(status) {
  return {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
    NEED_MORE: 'warning',
    WITHDRAWN: 'info',
    SUPERSEDED: 'info',
    EXPIRED: 'info',
    CONFIRMED: 'success',
    PREVIEWED: 'warning',
    STALE: 'info',
    SUPERSEDED_STATUS: 'info'
  }[status] || 'info'
}

function trailTagType(status) {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'PREVIEWED') return 'warning'
  return 'info'
}

async function loadMembers() {
  members.value = await memberApi.list()
}

function openRecharge(row) {
  pick.value = row
  rechargeAmount.value = 200
  rechargeVisible.value = true
}

async function openCerts(row) {
  certPick.value = row
  certData.value = { current: [], history: [] }
  certVisible.value = true
  certLoading.value = true
  try {
    certData.value = await certApi.memberCerts(row.id)
  } finally {
    certLoading.value = false
  }
}

async function openBests(row) {
  bestsPick.value = row
  bestsData.value = { groups: [] }
  bestDetail.value = null
  bestsVisible.value = true
  bestsLoading.value = true
  try {
    bestsData.value = await roundApi.memberBests(row.id)
  } finally {
    bestsLoading.value = false
  }
}

async function openBestRound(id) {
  bestDetail.value = await roundApi.detail(id)
}

async function submitRecharge() {
  await memberApi.recharge(pick.value.id, rechargeAmount.value)
  ElMessage.success('充值成功')
  rechargeVisible.value = false
  await loadMembers()
}

async function submitCreate() {
  if (!createForm.cardNo.trim() || !createForm.name.trim()) {
    ElMessage.warning('请填写卡号与姓名')
    return
  }
  await memberApi.create({ ...createForm, cardNo: createForm.cardNo.trim(), name: createForm.name.trim() })
  ElMessage.success('开卡成功')
  createVisible.value = false
  createForm.cardNo = ''
  createForm.name = ''
  createForm.phone = ''
  await loadMembers()
}

// ---------- 旧卡号进入 ----------
async function enterByCard() {
  if (!cardEntryNo.value.trim()) return
  const entry = await mergeApi.byCard(cardEntryNo.value.trim())
  if (!entry.merged) {
    ElMessage.success(`卡号 ${entry.cardNo} 为正常档案：${entry.name}`)
    return
  }
  redirect.value = entry
  redirectVisible.value = true
}

async function goRedirectMember() {
  redirectVisible.value = false
  if (redirect.value.redirectMemberId) {
    await loadMembers()
    const row = members.value.find((m) => m.id === redirect.value.redirectMemberId)
    if (row) {
      ElMessage.success(`已跳转到保留档案 ${row.cardNo}（${row.name}）`)
      openMergeTrail(row)
    }
  }
}

function openMergedRedirect(row) {
  const target = members.value.find((x) => x.id === row.mergedIntoId)
  redirect.value = {
    cardNo: row.cardNo,
    name: row.name,
    mergedAt: row.mergedAt,
    mergedBy: row.mergedBy,
    mergeReason: row.mergeReason,
    redirectMemberId: row.mergedIntoId,
    redirectCardNo: target ? target.cardNo : '',
    redirectName: target ? target.name : ''
  }
  redirectVisible.value = true
}

// ---------- 重复档案合并 ----------
function startMerge(row) {
  mergeForm.keep = row
  mergeForm.sourceId = null
  mergeForm.reason = ''
  preview.value = null
  mergeVisible.value = true
}

async function doPreview() {
  if (!mergeForm.sourceId) {
    ElMessage.warning('请先选择来源档案')
    return
  }
  if (!mergeForm.operator.trim() || !mergeForm.reason.trim()) {
    ElMessage.warning('请填写值班经理与合并原因')
    return
  }
  previewLoading.value = true
  try {
    preview.value = await mergeApi.preview({
      keepMemberId: mergeForm.keep.id,
      sourceMemberId: mergeForm.sourceId,
      reason: mergeForm.reason.trim(),
      operator: mergeForm.operator.trim(),
      role: 'MANAGER'
    })
    ElMessage.success(preview.value.canMerge ? '预演完成：无互斥项，可确认合并' : '预演完成：存在互斥项，暂不能合并')
  } finally {
    previewLoading.value = false
  }
}

async function doConfirm() {
  if (!preview.value) return
  confirmLoading.value = true
  try {
    const result = await mergeApi.confirm({
      mergeId: preview.value.mergeId,
      operator: mergeForm.operator.trim(),
      role: 'MANAGER'
    })
    ElMessage.success('合并完成，来源卡已封存')
    mergeVisible.value = false
    preview.value = null
    await loadMembers()
    mergeDetail.value = result
    detailVisible.value = true
  } finally {
    confirmLoading.value = false
  }
}

// ---------- 合并轨迹（普通会员只读，自己的合并结果也在这里查看） ----------
async function openMergeTrail(row) {
  trailMember.value = row
  trailList.value = []
  trailVisible.value = true
  trailLoading.value = true
  try {
    trailList.value = await mergeApi.memberMerges(row.id)
  } finally {
    trailLoading.value = false
  }
}

async function openMergeDetail(mergeId) {
  mergeDetail.value = await mergeApi.detail(mergeId)
  detailVisible.value = true
}

onMounted(loadMembers)
</script>

<style scoped>
.best {
  margin-left: 6px;
  font-size: 12px;
  color: var(--el-color-primary);
}

.best-score {
  color: #c62828;
}

.best-detail {
  margin-top: 14px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fafcfc;
}

.best-detail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.best-shots {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.best-shot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.best-shot em {
  font-style: normal;
  font-size: 10px;
  color: var(--ink-mute);
}

.best-shot b {
  width: 34px;
  height: 34px;
  line-height: 34px;
  text-align: center;
  border-radius: 8px;
  background: #fff;
  border: 1px solid var(--line);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.cert-section-title {
  font-size: 13px;
  font-weight: 600;
  margin: 6px 0 8px;
}

.cert-current {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.cert-card {
  border: 1px solid #a8ddc0;
  background: #f2fbf5;
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}

.cert-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;
}

.muted {
  color: var(--ink-mute);
}

.small {
  font-size: 12px;
}

.danger {
  color: #c62828;
}

.ok {
  color: #1b8a5a;
}

.preview-box {
  margin-top: 8px;
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 4px;
}

.conflict-box {
  margin-bottom: 12px;
}

.cmp-table :deep(.cell) {
  line-height: 1.7;
}

.section-title {
  font-size: 13px;
  font-weight: 700;
  margin: 10px 0 6px;
}

.section-title.danger {
  color: #c62828;
}

.plan-box {
  margin-top: 10px;
}

.sub-block {
  margin-top: 12px;
}

.redirect-box {
  font-size: 13px;
}

.trail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 2px;
}
</style>
