<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">会员管理 <em>开卡 · 充值 · 等级与余额</em></h2>
      <div class="page-tools">
        <el-button type="primary" @click="createVisible = true">开卡</el-button>
      </div>
    </div>

    <div class="rule-tip">
      单次充值 100 ~ 10000 元；累计消费满 3000 元升银卡、满 10000 元升金卡，等级只升不降，折扣分别为 9.5 折 / 9 折。
    </div>

    <div class="merge-bar">
      <div class="merge-bar-tip">
        <b>重复档案合并</b>
        <span>同一会员被误开两张卡时，由值班经理选定保留档案与来源档案：先预演核对互斥项，确认后迁移全部业务关系并封存来源卡（不删卡、不改手机号，全程留痕）。</span>
      </div>
      <div class="merge-bar-actions">
        <el-input v-model="lookupCardNo" placeholder="输入旧卡号查询 / 跳转" clearable style="width: 210px" @keyup.enter="lookupCard" />
        <el-button @click="lookupCard">旧卡查询</el-button>
        <el-button type="warning" plain @click="openMergePicker">重复档案合并</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table :data="members" :row-class-name="rowClass">
        <el-table-column prop="cardNo" label="会员卡号" width="150">
          <template #default="{ row }">
            {{ row.cardNo }}
            <el-tag v-if="row.status === 'MERGED'" size="small" type="info" effect="plain">已封存</el-tag>
          </template>
        </el-table-column>
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
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'MERGED'">
              <el-button link type="info" @click="jumpToTarget(row)">查看保留卡</el-button>
              <el-button link type="warning" @click="openTrails(row)">合并轨迹</el-button>
            </template>
            <template v-else>
              <el-button link type="primary" @click="openRecharge(row)">充值</el-button>
              <el-button link type="success" @click="openCerts(row)">认证</el-button>
              <el-button link type="warning" @click="openTrails(row)">轨迹</el-button>
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

    <!-- 重复档案合并：第一步选择保留档案 / 来源档案 / 值班经理 / 原因 -->
    <el-dialog v-model="mergePickerVisible" title="重复会员档案合并 · 选择档案" width="520px">
      <el-form label-width="92px">
        <el-form-item label="保留档案">
          <el-select v-model="mergeForm.keepMemberId" filterable placeholder="合并后继续使用的卡" style="width: 100%">
            <el-option v-for="m in activeMembers" :key="'k' + m.id"
              :label="`${m.cardNo} · ${m.name} · 余额¥${money(m.balance)} · ${m.levelName}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源档案">
          <el-select v-model="mergeForm.sourceMemberId" filterable placeholder="合并后封存的历史卡" style="width: 100%">
            <el-option v-for="m in activeMembers.filter(x => x.id !== mergeForm.keepMemberId)" :key="'s' + m.id"
              :label="`${m.cardNo} · ${m.name} · 余额¥${money(m.balance)} · ${m.levelName}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="值班经理">
          <el-input v-model="mergeForm.operator" placeholder="请填写值班经理姓名" maxlength="32" />
        </el-form-item>
        <el-form-item label="合并原因">
          <el-input v-model="mergeForm.reason" type="textarea" :rows="2" maxlength="500" show-word-limit
            placeholder="如：同一会员被误开两张卡，经核对为同一人" />
        </el-form-item>
      </el-form>
      <div class="rule-tip">
        下一步先出<b>合并预演</b>：分别核对两张卡的余额、累计消费、等级、课程报名、未结束回合、历史成绩、
        当前器材租借、认证申请与有效认证；有互斥项（双方进行中回合 / 同件器材 / 同弓种有效认证）时不能合并。
      </div>
      <template #footer>
        <el-button @click="mergePickerVisible = false">取消</el-button>
        <el-button type="warning" :loading="previewLoading" @click="runPreview">生成合并预演</el-button>
      </template>
    </el-dialog>

    <!-- 合并预演：两侧画像 + 互斥项 + 处理项 + 金额等级 + 迁移量 -->
    <el-dialog v-model="previewVisible" title="合并预演（确认前请逐项核对）" width="1080px" top="4vh">
      <div v-if="preview" v-loading="previewLoading">
        <el-alert v-if="!preview.mergeable" type="error" show-icon :closable="false" class="merge-alert"
          title="存在必须先解除的互斥项，本次不能合并；请在业务中处理后重新预演" />
        <el-alert v-else type="success" show-icon :closable="false" class="merge-alert"
          title="无互斥项，可以确认合并；确认后来源档案将封存为历史档案，不可撤销" />

        <!-- 两张卡对照 -->
        <div class="merge-sides">
          <div v-for="side in [['keep', preview.keep], ['source', preview.source]]" :key="side[0]"
            class="merge-side" :class="side[0] === 'keep' ? 'is-keep' : 'is-source'">
            <div class="merge-side-head">
              <el-tag :type="side[0] === 'keep' ? 'success' : 'warning'" effect="dark">
                {{ side[0] === 'keep' ? '保留档案' : '来源档案（合并后封存）' }}
              </el-tag>
              <b>{{ side[1].cardNo }} · {{ side[1].name }}</b>
              <span class="muted">{{ side[1].phone }}</span>
            </div>
            <div class="merge-finance-row">
              <span>余额 <b>¥{{ money(side[1].balance) }}</b></span>
              <span>累计消费 <b>¥{{ money(side[1].totalSpend) }}</b></span>
              <span>等级 <el-tag size="small" effect="plain">{{ side[1].levelName }}</el-tag></span>
            </div>
            <div class="merge-block">
              <div class="merge-block-title">未结束计分回合（{{ side[1].ongoingRounds.length }}）</div>
              <div v-if="side[1].ongoingRounds.length" class="merge-tags">
                <el-tag v-for="r in side[1].ongoingRounds" :key="r.id" size="small" type="danger" effect="plain">
                  {{ r.roundNo }} · {{ r.bowTypeName }}{{ r.distance }}m · {{ r.statusName }}
                </el-tag>
              </div>
              <span v-else class="muted">无</span>
            </div>
            <div class="merge-block">
              <div class="merge-block-title">
                历史成绩（{{ side[1].roundHistory.submittedCount }} 个已提交回合 · {{ side[1].roundHistory.personalBestCount }} 条个人最好）
              </div>
              <div class="merge-tags">
                <el-tag v-for="r in side[1].roundHistory.rounds.slice(0, 8)" :key="r.id" size="small" effect="plain">
                  {{ r.roundNo }} · {{ r.bowTypeName }}{{ r.distance }}m · {{ r.arrowCount }}支
                </el-tag>
                <span v-if="side[1].roundHistory.rounds.length > 8" class="muted">
                  等 {{ side[1].roundHistory.rounds.length }} 个
                </span>
                <span v-if="!side[1].roundHistory.rounds.length" class="muted">无</span>
              </div>
            </div>
            <div class="merge-block">
              <div class="merge-block-title">课程报名（{{ side[1].enrollments.length }}）</div>
              <div class="merge-tags">
                <el-tag v-for="e in side[1].enrollments" :key="e.enrollId" size="small" type="info" effect="plain">
                  {{ e.courseName }} · {{ shortTime(e.enrollTime) }}
                </el-tag>
                <span v-if="!side[1].enrollments.length" class="muted">无</span>
              </div>
            </div>
            <div class="merge-block">
              <div class="merge-block-title">当前器材租借（{{ side[1].rentedEquipment.length }}）</div>
              <div class="merge-tags">
                <el-tag v-for="e in side[1].rentedEquipment" :key="e.equipmentId" size="small" type="warning" effect="plain">
                  {{ e.equipCode }} · {{ e.typeName }}
                </el-tag>
                <span v-if="!side[1].rentedEquipment.length" class="muted">无</span>
              </div>
            </div>
            <div class="merge-block">
              <div class="merge-block-title">认证申请（{{ side[1].certApplications.length }}）/ 有效认证（{{ side[1].validCerts.length }}）</div>
              <div class="merge-tags">
                <el-tag v-for="c in side[1].validCerts" :key="c.applicationId" size="small" type="success" effect="plain">
                  {{ c.appNo }} · {{ c.bowTypeName }}{{ c.distance }}m 有效
                </el-tag>
                <el-tag v-for="c in side[1].certApplications.filter(x => x.displayStatus !== 'APPROVED')" :key="c.applicationId"
                  size="small" type="info" effect="plain">
                  {{ c.appNo }} · {{ c.bowTypeName }}{{ c.distance }}m · {{ c.displayStatusName }}
                </el-tag>
                <el-tag v-for="c in side[1].certApplications.filter(x => x.displayStatus === 'EXPIRED')" :key="'e'+c.applicationId"
                  size="small" type="info" effect="plain">
                  {{ c.appNo }} · {{ c.bowTypeName }}{{ c.distance }}m · 已过期
                </el-tag>
                <span v-if="!side[1].certApplications.length" class="muted">无</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 互斥项清单 -->
        <div class="merge-section">
          <div class="merge-section-title danger">互斥项（{{ preview.conflicts.length }}）— 必须先解除，系统不会自行挑一条覆盖</div>
          <el-table v-if="preview.conflicts.length" :data="preview.conflicts" size="small" border>
            <el-table-column prop="typeName" label="互斥类型" width="240" />
            <el-table-column prop="detail" label="具体互斥项与处理要求" />
            <el-table-column label="保留档案" width="160">
              <template #default="{ row }">{{ row.keepRefLabel || '—' }}</template>
            </el-table-column>
            <el-table-column label="来源档案" width="160">
              <template #default="{ row }">{{ row.sourceRefLabel || '—' }}</template>
            </el-table-column>
          </el-table>
          <div v-else class="muted">无互斥项。</div>
        </div>

        <!-- 自动处理项：重复报名去重 -->
        <div class="merge-section">
          <div class="merge-section-title">自动处理项（{{ preview.resolves.length }}）</div>
          <el-table v-if="preview.resolves.length" :data="preview.resolves" size="small" border>
            <el-table-column prop="typeName" label="处理规则" width="260" />
            <el-table-column prop="detail" label="处理结果" />
            <el-table-column label="保留" width="180">
              <template #default="{ row }">
                报名 #{{ row.keptEnrollId }}（{{ row.keptSide === 'KEEP' ? '保留卡' : '来源卡' }}
                · {{ shortTime(row.keptEnrollTime) }}）
              </template>
            </el-table-column>
            <el-table-column label="删除" width="180">
              <template #default="{ row }">
                报名 #{{ row.droppedEnrollId }}（{{ row.droppedSide === 'KEEP' ? '保留卡' : '来源卡' }}
                · {{ shortTime(row.droppedEnrollTime) }}）
              </template>
            </el-table-column>
          </el-table>
          <div v-else class="muted">无重复项，无需去重。</div>
        </div>

        <!-- 金额与等级 -->
        <div class="merge-section">
          <div class="merge-section-title">余额、累计消费与等级合并结果</div>
          <div class="merge-finance-grid">
            <div class="merge-fin-cell"><span>余额合并</span>
              <b>¥{{ money(preview.finance.keepBalance) }} + ¥{{ money(preview.finance.sourceBalance) }}
                = ¥{{ money(preview.finance.mergedBalance) }}</b></div>
            <div class="merge-fin-cell"><span>累计消费合并</span>
              <b>¥{{ money(preview.finance.keepTotalSpend) }} + ¥{{ money(preview.finance.sourceTotalSpend) }}
                = ¥{{ money(preview.finance.mergedTotalSpend) }}</b></div>
            <div class="merge-fin-cell"><span>等级重算（只升不降）</span>
              <b>
                保留卡 {{ levelNameOf(preview.finance.keepLevel) }} / 来源卡 {{ levelNameOf(preview.finance.sourceLevel) }}
                → 按累计消费重算 {{ levelNameOf(preview.finance.recalculatedLevel) }}
                → 最终 <el-tag size="small" type="warning" effect="dark">{{ preview.finance.finalLevelName }}</el-tag>
              </b></div>
          </div>
        </div>

        <!-- 迁移量 -->
        <div class="merge-section">
          <div class="merge-section-title">业务关系迁移量</div>
          <div class="merge-counts">
            <span>计分回合 <b>{{ preview.migration.roundsMoved }}</b></span>
            <span>课程报名迁移 <b>{{ preview.migration.enrollmentsMoved }}</b></span>
            <span>重复报名去重 <b>{{ preview.migration.enrollmentsDropped }}</b></span>
            <span>器材租借 <b>{{ preview.migration.equipmentMoved }}</b></span>
            <span>占用箭道 <b>{{ preview.migration.lanesMoved }}</b></span>
            <span>认证申请 <b>{{ preview.migration.certsMoved }}</b></span>
            <span>赛事参与 <b>{{ preview.migration.tournamentMoved }}</b></span>
          </div>
          <div class="rule-tip">
            历史回合的箭值、完成时间、认证规则快照一律不改写；迁移完成后会按保留会员重新核对个人最好分组。
            预演后若任一张卡的余额、报名、回合、租借或认证发生变化，确认将被拒绝，需要重新预演。
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">取消</el-button>
        <el-button @click="runPreview">重新预演</el-button>
        <el-popconfirm
          :title="`确认把来源卡 ${preview ? preview.source.cardNo : ''} 合并到保留卡 ${preview ? preview.keep.cardNo : ''}？封存后不可撤销`"
          confirm-button-text="确认合并" cancel-button-text="再想想" @confirm="doConfirm">
          <template #reference>
            <el-button type="danger" :disabled="!preview || !preview.mergeable" :loading="confirmLoading">
              确认合并
            </el-button>
          </template>
        </el-popconfirm>
      </template>
    </el-dialog>

    <!-- 合并结果 -->
    <el-dialog v-model="resultVisible" title="合并完成" width="560px">
      <div v-if="mergeResult" class="merge-result">
        <el-result icon="success" :title="`${mergeResult.sourceCardNo} 已合并封存到 ${mergeResult.keepCardNo}`"
          :sub-title="`合并单号 ${mergeResult.mergeNo} · 操作人 ${mergeResult.operator} · ${fmtTime(mergeResult.mergedAt)}`" />
        <div class="merge-counts">
          <span>合并后余额 <b>¥{{ money(mergeResult.balanceAfter) }}</b></span>
          <span>合并后累计消费 <b>¥{{ money(mergeResult.totalSpendAfter) }}</b></span>
          <span>最终等级 <b>{{ mergeResult.levelAfterName }}</b></span>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- 合并轨迹 -->
    <el-dialog v-model="trailsVisible" :title="`合并轨迹 · ${trailsPick ? trailsPick.name : ''}`" width="860px">
      <div v-loading="trailsLoading">
        <el-empty v-if="!trails.length" description="该会员暂无合并记录" :image-size="70" />
        <el-timeline v-else>
          <el-timeline-item v-for="t in trails" :key="t.id" :timestamp="`${fmtTime(t.mergedAt)} · ${t.operator}`" placement="top">
            <div class="trail-card">
              <div class="trail-head">
                <el-tag size="small" type="warning" effect="dark">{{ t.mergeNo }}</el-tag>
                <b>{{ t.sourceCardNo }}（{{ t.sourceName }}）</b>
                <span class="muted">合并到</span>
                <b>{{ t.keepCardNo }}（{{ t.keepName }}）</b>
              </div>
              <div class="trail-finance">
                <span>余额 {{ money(t.keepBalanceBefore) }} + {{ money(t.sourceBalanceBefore) }}
                  = <b>{{ money(t.balanceAfter) }}</b></span>
                <span>累计消费 → <b>{{ money(t.totalSpendAfter) }}</b></span>
                <span>等级 {{ t.levelBeforeName }} → <b>{{ t.levelAfterName }}</b></span>
              </div>
              <div class="trail-counts">
                回合 {{ t.roundsMoved }} · 报名迁移 {{ t.enrollmentsMoved }} · 去重 {{ t.enrollmentsDropped }}
                · 器材 {{ t.equipmentMoved }} · 箭道 {{ t.lanesMoved }} · 认证 {{ t.certsMoved }} · 赛事 {{ t.tournamentMoved }}
              </div>
              <div class="trail-reason">原因：{{ t.reason }}</div>
              <el-collapse v-if="t.details && t.details.length" class="trail-detail-collapse">
                <el-collapse-item title="逐项迁移明细">
                  <div v-for="(d, i) in t.details" :key="i" class="trail-detail-line">
                    <el-tag size="small" effect="plain">{{ d.categoryName }}</el-tag>
                    <span>{{ d.detail }}</span>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-dialog>

    <!-- 旧卡号查询 / 跳转 -->
    <el-dialog v-model="lookupVisible" title="旧卡号查询结果" width="520px">
      <div v-if="lookup" class="lookup-box">
        <template v-if="lookup.status === 'MERGED'">
          <el-alert type="warning" show-icon :closable="false" class="merge-alert"
            :title="`卡号 ${lookup.sourceCardNo}（${lookup.sourceName}）是已封存的历史档案`" />
          <div class="lookup-lines">
            <div>已合并到保留档案：<b>{{ lookup.targetCardNo }} · {{ lookup.targetName }}</b></div>
            <div>合并时间：{{ fmtTime(lookup.mergedAt) }}</div>
            <div>合并原因：{{ lookup.mergeReason }}</div>
            <div>操作值班经理：{{ lookup.mergedBy }}</div>
          </div>
          <el-button type="primary" @click="goTargetMember(lookup.targetMemberId); lookupVisible = false">
            跳转到保留档案
          </el-button>
        </template>
        <template v-else>
          <el-alert type="success" show-icon :closable="false" class="merge-alert"
            :title="`${lookup.targetCardNo} · ${lookup.targetName} 是在用档案，可继续使用。`" />
        </template>
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

// 重复档案合并
const mergePickerVisible = ref(false)
const previewVisible = ref(false)
const resultVisible = ref(false)
const trailsVisible = ref(false)
const lookupVisible = ref(false)
const previewLoading = ref(false)
const confirmLoading = ref(false)
const trailsLoading = ref(false)
const preview = ref(null)
const mergeResult = ref(null)
const trails = ref([])
const trailsPick = ref(null)
const lookup = ref(null)
const lookupCardNo = ref('')
const mergeForm = reactive({ keepMemberId: null, sourceMemberId: null, operator: '', reason: '' })

const activeMembers = computed(() => members.value.filter((m) => m.status !== 'MERGED'))

function levelNameOf(code) {
  return { NORMAL: '普通会员', SILVER: '银卡会员', GOLD: '金卡会员' }[code] || code
}

function rowClass({ row }) {
  return row.status === 'MERGED' ? 'row-merged' : ''
}

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
    EXPIRED: 'info'
  }[status] || 'info'
}

const fmtTime = (value) => (value ? String(value).slice(0, 16) : '')

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

// ================= 重复档案合并 =================

function openMergePicker() {
  mergeForm.keepMemberId = null
  mergeForm.sourceMemberId = null
  mergeForm.operator = ''
  mergeForm.reason = ''
  mergePickerVisible.value = true
}

async function runPreview() {
  if (!mergeForm.keepMemberId || !mergeForm.sourceMemberId) {
    ElMessage.warning('请分别选择保留档案与来源档案')
    return
  }
  if (mergeForm.keepMemberId === mergeForm.sourceMemberId) {
    ElMessage.warning('保留档案与来源档案不能是同一张卡')
    return
  }
  if (!mergeForm.operator.trim()) {
    ElMessage.warning('请填写值班经理姓名')
    return
  }
  if (!mergeForm.reason.trim()) {
    ElMessage.warning('请填写合并原因')
    return
  }
  previewLoading.value = true
  try {
    preview.value = await mergeApi.preview({
      keepMemberId: mergeForm.keepMemberId,
      sourceMemberId: mergeForm.sourceMemberId,
      operator: mergeForm.operator.trim(),
      reason: mergeForm.reason.trim()
    })
    mergePickerVisible.value = false
    previewVisible.value = true
    if (preview.value.mergeable) {
      ElMessage.success('预演完成：无互斥项，可核对后确认合并')
    } else {
      ElMessage.error(`存在 ${preview.value.conflicts.length} 个互斥项，需先解除后重新预演`)
    }
  } finally {
    previewLoading.value = false
  }
}

async function doConfirm() {
  if (!preview.value) return
  confirmLoading.value = true
  try {
    mergeResult.value = await mergeApi.confirm({
      previewId: preview.value.previewId,
      operator: mergeForm.operator.trim()
    })
    previewVisible.value = false
    resultVisible.value = true
    ElMessage.success('合并完成，来源档案已封存')
    await loadMembers()
  } finally {
    confirmLoading.value = false
  }
}

async function openTrails(row) {
  trailsPick.value = row
  trails.value = []
  trailsVisible.value = true
  trailsLoading.value = true
  try {
    trails.value = await mergeApi.trails(row.id)
  } finally {
    trailsLoading.value = false
  }
}

async function lookupCard() {
  if (!lookupCardNo.value.trim()) {
    ElMessage.warning('请输入要查询的会员卡号')
    return
  }
  lookup.value = await mergeApi.lookup(lookupCardNo.value.trim())
  lookupVisible.value = true
}

function jumpToTarget(row) {
  if (row.mergedIntoId) goTargetMember(row.mergedIntoId)
}

function goTargetMember(targetId) {
  const target = members.value.find((m) => m.id === targetId)
  ElMessage.success(target ? `已跳转到保留档案 ${target.cardNo} · ${target.name}` : '已跳转到保留档案')
}

onMounted(loadMembers)
</script>

<style scoped>
.best {
  margin-left: 6px;
  font-size: 12px;
  color: var(--el-color-primary);
}

/* ---------- 重复档案合并 ---------- */
.merge-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 14px;
  margin-bottom: 12px;
  border: 1px solid #e8c9a0;
  background: #fdf7ee;
  border-radius: 10px;
}

.merge-bar-tip {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: var(--ink-mute);
}

.merge-bar-tip b {
  font-size: 13px;
  color: #a06a1c;
}

.merge-bar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

:deep(.row-merged) {
  color: var(--ink-mute);
  background: #f7f7f8;
}

.merge-sides {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 12px;
}

.merge-side {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #fcfdfd;
}

.merge-side.is-keep {
  border-color: #a8ddc0;
  background: #f4fbf6;
}

.merge-side.is-source {
  border-color: #eccfa3;
  background: #fefbf5;
}

.merge-side-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.merge-finance-row {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: var(--ink-soft);
  flex-wrap: wrap;
}

.merge-block {
  border-top: 1px dashed var(--line);
  padding-top: 6px;
}

.merge-block-title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 4px;
  color: var(--ink-soft);
}

.merge-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.merge-section {
  margin-top: 12px;
}

.merge-section-title {
  font-size: 13px;
  font-weight: 700;
  margin-bottom: 6px;
}

.merge-section-title.danger {
  color: #c0392b;
}

.merge-alert {
  margin-bottom: 12px;
}

.merge-finance-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 6px;
}

.merge-fin-cell {
  display: flex;
  justify-content: space-between;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
  background: #fff;
}

.merge-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 13px;
  color: var(--ink-soft);
  margin-bottom: 8px;
}

.merge-counts b {
  color: #c0392b;
  font-size: 15px;
}

.merge-result .merge-counts {
  justify-content: center;
  margin-top: 6px;
}

.trail-card {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fff;
}

.trail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.trail-finance {
  display: flex;
  gap: 16px;
  margin: 6px 0;
  font-size: 12px;
  color: var(--ink-soft);
  flex-wrap: wrap;
}

.trail-counts {
  font-size: 12px;
  color: var(--ink-mute);
}

.trail-reason {
  font-size: 12px;
  margin-top: 4px;
}

.trail-detail-line {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  padding: 2px 0;
}

.trail-detail-collapse {
  margin-top: 4px;
}

.lookup-box {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lookup-lines {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
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
</style>
