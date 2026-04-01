<template>
  <section class="document-detail-page">
    <transition name="drawer-fade">
      <div v-if="logDrawerOpen" class="drawer-overlay" @click="closeLogDrawer"></div>
    </transition>

    <transition name="drawer-slide">
      <aside v-if="logDrawerOpen" class="log-drawer">
        <div class="drawer-header">
          <div>
            <p class="section-eyebrow">Task Timeline</p>
            <h3>任务执行详情</h3>
            <p class="drawer-subtitle">
              任务 {{ documentDetail?.latestTaskId || '-' }} · {{ documentDetail?.latestTaskTypeName || '暂无任务类型' }}
            </p>
          </div>
          <button class="icon-button" type="button" @click="closeLogDrawer">
            <XMarkIcon class="drawer-icon" />
          </button>
        </div>

        <div class="drawer-summary">
          <div class="summary-chip">
            <span>当前状态</span>
            <AdminStatusBadge
              :label="documentDetail?.latestTaskStatusName || '暂无状态'"
              :code="documentDetail?.latestTaskStatus"
              type="task"
            />
          </div>
          <div class="summary-chip">
            <span>索引状态</span>
            <AdminStatusBadge
              :label="documentDetail?.indexStatusName || '暂无状态'"
              :code="documentDetail?.indexStatus"
              type="index"
            />
          </div>
        </div>

        <div v-if="logLoading" class="drawer-empty">正在加载任务日志...</div>
        <div v-else-if="!taskLogs.length" class="drawer-empty">当前任务还没有日志记录。</div>

        <div v-else class="drawer-timeline">
          <article v-for="log in taskLogs" :key="log.id" class="drawer-log-item">
            <div class="drawer-log-node"></div>
            <div class="drawer-log-body">
              <div class="drawer-log-head">
                <strong>{{ log.stageTypeName }} · {{ log.eventTypeName }}</strong>
                <span>{{ formatDateTime(log.createTime) }}</span>
              </div>
              <p>{{ log.content }}</p>
              <pre v-if="log.detailJson" class="drawer-log-detail">{{ log.detailJson }}</pre>
            </div>
          </article>
        </div>
      </aside>
    </transition>

    <div class="page-top">
      <button class="ghost-button" type="button" @click="goBack">
        <ArrowLeftIcon class="back-icon" />
        返回文档列表
      </button>
      <button class="ghost-button" type="button" :disabled="loading" @click="loadAll">
        {{ loading ? '刷新中...' : '刷新详情' }}
      </button>
    </div>

    <div v-if="pageNotice.message" class="page-notice" :class="`page-notice-${pageNotice.type}`">
      {{ pageNotice.message }}
    </div>

    <article v-if="documentDetail" class="panel-card detail-card">
      <div class="detail-content">
        <div class="detail-header">
          <div>
            <p class="section-eyebrow">Selected Document</p>
            <h3>{{ documentDetail.documentName }}</h3>
            <p class="detail-subtitle">{{ documentDetail.originalFileName }}</p>
          </div>

          <div class="detail-statuses">
            <AdminStatusBadge :label="documentDetail.parseStatusName" :code="documentDetail.parseStatus" type="parse" />
            <AdminStatusBadge :label="documentDetail.strategyStatusName" :code="documentDetail.strategyStatus" type="strategy" />
            <AdminStatusBadge :label="documentDetail.indexStatusName" :code="documentDetail.indexStatus" type="index" />
          </div>
        </div>

        <div class="meta-grid">
          <div class="meta-item">
            <span>文档 ID</span>
            <strong>{{ documentDetail.documentId }}</strong>
          </div>
          <div class="meta-item">
            <span>当前方案</span>
            <strong>{{ documentDetail.currentPlanId || '-' }}</strong>
          </div>
          <div class="meta-item">
            <span>最近任务</span>
            <strong>{{ documentDetail.latestTaskId || '-' }}</strong>
          </div>
          <div class="meta-item">
            <span>字符 / Token</span>
            <strong>{{ formatCount(documentDetail.charCount) }} / {{ formatCount(documentDetail.tokenCount) }}</strong>
          </div>
        </div>

        <div class="detail-secondary-actions">
          <button class="ghost-button" type="button" :disabled="!documentDetail.latestTaskId" @click="openLogDrawer">
            查看任务时间线
          </button>
        </div>

        <div v-if="showBuildTracker" class="build-progress-card">
          <div class="build-progress-header">
            <div>
              <p class="section-eyebrow">Index Build Tracker</p>
              <strong>{{ buildTrackerTitle }}</strong>
              <p class="build-progress-text">{{ buildTrackerDescription }}</p>
            </div>
            <span class="build-pulse" :class="{ 'build-pulse-static': !isBuildPolling }">
              {{ isBuildPolling ? '实时轮询中' : '轨迹已保留' }}
            </span>
          </div>

          <div class="sequence-board build-stage-board">
            <template v-for="(row, rowIndex) in buildStageRows" :key="`build-row-${rowIndex}`">
              <div class="sequence-row">
                <article v-if="row.leftItem" class="stage-card sequence-card" :class="`stage-${row.leftItem.status}`">
                  <div class="stage-order">{{ row.leftItem.order }}</div>
                  <div class="stage-body">
                    <strong>{{ row.leftItem.label }}</strong>
                    <span>{{ row.leftItem.description }}</span>
                    <em>{{ row.leftItem.statusLabel }}</em>
                  </div>
                </article>
                <div v-else class="sequence-card-placeholder"></div>

                <div v-if="row.leftItem && row.rightItem" class="sequence-inline-arrow">{{ row.direction === 'rtl' ? '←' : '→' }}</div>
                <div v-else class="sequence-inline-arrow sequence-inline-arrow-empty"></div>

                <article v-if="row.rightItem" class="stage-card sequence-card" :class="`stage-${row.rightItem.status}`">
                  <div class="stage-order">{{ row.rightItem.order }}</div>
                  <div class="stage-body">
                    <strong>{{ row.rightItem.label }}</strong>
                    <span>{{ row.rightItem.description }}</span>
                    <em>{{ row.rightItem.statusLabel }}</em>
                  </div>
                </article>
                <div v-else class="sequence-card-placeholder"></div>
              </div>

              <div v-if="rowIndex < buildStageRows.length - 1" class="sequence-down-row" :class="`sequence-down-row-${row.downColumn}`">
                <span class="sequence-down-arrow">↓</span>
              </div>
            </template>
          </div>

          <div class="tracker-footer">
            <span>任务 {{ buildTaskSnapshot?.taskId || activeBuildTaskId || '-' }}</span>
            <span>状态 {{ buildTaskSnapshot?.taskStatusName || (hasCode(documentDetail.indexStatus, 3) ? '成功' : '未知') }}</span>
            <span>耗时 {{ formatDuration(buildTaskSnapshot?.costMillis) }}</span>
          </div>
        </div>

        <section class="detail-section">
          <div class="section-headline">
            <h4>策略推荐与确认</h4>
            <span v-if="strategyPlan?.planReady">方案已就绪</span>
            <span v-else>等待策略推荐</span>
          </div>

          <div v-if="documentDetail.parseErrorMsg" class="inline-notice inline-notice-danger">
            {{ documentDetail.parseErrorMsg }}
          </div>

          <div v-if="planLoading" class="empty-block compact-empty">正在读取策略详情...</div>
          <div v-else-if="!strategyPlan?.planReady" class="empty-block compact-empty">
            当前文档尚未生成策略方案，解析完成后可点击刷新查看。
          </div>
          <template v-else>
            <div class="reason-card">
              <span>推荐原因</span>
              <p>{{ strategyPlan.plan?.recommendReason || '系统已生成推荐策略，可以根据业务需要再做补充。' }}</p>
            </div>

            <div class="timeline-list">
              <template v-for="(step, index) in strategyPlan.plan.steps" :key="`${strategyPlan.plan.planId}-${step.stepNo}`">
                <article class="timeline-item">
                  <div class="timeline-index">{{ String(step.stepNo).padStart(2, '0') }}</div>
                  <div class="timeline-main">
                    <strong>{{ step.strategyName }}</strong>
                    <p>{{ step.recommendReason || step.strategyRoleName }}</p>
                  </div>
                </article>
                <div
                  v-if="index < strategyPlan.plan.steps.length - 1"
                  :key="`${strategyPlan.plan.planId}-${step.stepNo}-arrow`"
                  class="flow-arrow"
                >
                  <span class="flow-arrow-icon" aria-hidden="true">↓</span>
                </div>
              </template>
            </div>

            <div class="section-headline editor-headline">
              <h4>策略调整</h4>
              <span>使用下方标签增删策略，并通过上移 / 下移调整执行顺序</span>
            </div>

            <div class="selected-flow-board">
              <span class="selected-flow-label">当前执行链路</span>

              <div v-if="selectedStrategyPreview.length" class="sequence-board selected-flow-sequence">
                <template v-for="(row, rowIndex) in selectedStrategyRows" :key="`strategy-row-${rowIndex}`">
                  <div class="sequence-row">
                    <article v-if="row.leftItem" class="selected-flow-card sequence-card">
                      <div class="selected-flow-order">{{ row.leftItem.order }}</div>
                      <div class="selected-flow-content">
                        <strong>{{ row.leftItem.label }}</strong>
                        <span>{{ row.leftItem.description }}</span>
                      </div>
                      <div class="selected-flow-actions">
                        <button class="flow-action-button" type="button" :disabled="row.leftItem.index === 0" @click="moveStrategy(row.leftItem.type, -1)">
                          上移
                        </button>
                        <button class="flow-action-button" type="button" :disabled="row.leftItem.index === selectedStrategyPreview.length - 1" @click="moveStrategy(row.leftItem.type, 1)">
                          下移
                        </button>
                      </div>
                    </article>
                    <div v-else class="sequence-card-placeholder"></div>

                    <div v-if="row.leftItem && row.rightItem" class="sequence-inline-arrow">{{ row.direction === 'rtl' ? '←' : '→' }}</div>
                    <div v-else class="sequence-inline-arrow sequence-inline-arrow-empty"></div>

                    <article v-if="row.rightItem" class="selected-flow-card sequence-card">
                      <div class="selected-flow-order">{{ row.rightItem.order }}</div>
                      <div class="selected-flow-content">
                        <strong>{{ row.rightItem.label }}</strong>
                        <span>{{ row.rightItem.description }}</span>
                      </div>
                      <div class="selected-flow-actions">
                        <button class="flow-action-button" type="button" :disabled="row.rightItem.index === 0" @click="moveStrategy(row.rightItem.type, -1)">
                          上移
                        </button>
                        <button class="flow-action-button" type="button" :disabled="row.rightItem.index === selectedStrategyPreview.length - 1" @click="moveStrategy(row.rightItem.type, 1)">
                          下移
                        </button>
                      </div>
                    </article>
                    <div v-else class="sequence-card-placeholder"></div>
                  </div>

                  <div v-if="rowIndex < selectedStrategyRows.length - 1" class="sequence-down-row" :class="`sequence-down-row-${row.downColumn}`">
                    <span class="sequence-down-arrow">↓</span>
                  </div>
                </template>
              </div>

              <div v-else class="selected-flow-empty">
                至少选择一个拆分策略，已选策略会在这里形成清晰的箭头处理链路。
              </div>
            </div>

            <div class="strategy-picker">
              <button
                v-for="item in strategyLibrary"
                :key="item.type"
                class="strategy-chip"
                :class="{ active: selectedStrategyTypes.includes(item.type) }"
                type="button"
                @click="toggleStrategy(item.type)"
              >
                <div class="strategy-chip-top">
                  <span class="strategy-chip-state">{{ selectedStrategyTypes.includes(item.type) ? '已选中' : '点击添加' }}</span>
                  <CheckCircleIcon v-if="selectedStrategyTypes.includes(item.type)" class="strategy-chip-check" />
                </div>
                <strong>{{ item.label }}</strong>
                <span>{{ item.description }}</span>
              </button>
            </div>

            <div class="preview-box">
              <span>最终提交顺序</span>
              <div v-if="selectedStrategyPreview.length" class="preview-flow">
                <template v-for="(item, index) in selectedStrategyPreview" :key="`preview-${item.type}`">
                  <span class="preview-tag">{{ item.label }}</span>
                  <ArrowRightIcon v-if="index < selectedStrategyPreview.length - 1" class="preview-arrow" />
                </template>
              </div>
              <p v-else class="preview-empty">还没有选中策略，无法生成最终提交顺序。</p>
            </div>

            <div class="confirm-actions">
              <input v-model="adjustNote" class="adjust-input" type="text" placeholder="补充说明，例如：增加大模型智能切块用于复杂段落" />
              <div class="strategy-submit-actions">
                <button class="action-button action-button-confirm" type="button" :disabled="confirmLoading || !selectedStrategyTypes.length" @click="submitConfirmStrategy">
                  {{ confirmLoading ? '确认中...' : '确认策略方案' }}
                </button>
                <button class="action-button action-button-build" type="button" :disabled="!canBuildIndex || buildLoading" @click="submitBuildIndex">
                  {{ buildLoading ? '构建中...' : '构建索引' }}
                </button>
              </div>
            </div>
          </template>
        </section>

        <section class="detail-section">
          <div class="section-headline">
            <h4>解析后的 Chunk 列表</h4>
            <span v-if="chunkQuery?.taskId">任务 {{ chunkQuery.taskId }} · {{ chunkQuery.total || 0 }} 条</span>
            <span v-else>当前还没有可展示的 chunk</span>
          </div>

          <div v-if="chunkLoading" class="empty-block compact-empty">正在加载 Chunk 列表...</div>
          <div v-else-if="!chunkRecords.length" class="empty-block compact-empty">
            当前文档还没有 Chunk 数据。请先完成索引构建，或等待构建任务继续执行。
          </div>

          <div v-else class="chunk-table-panel">
            <div class="chunk-toolbar">
              <article class="chunk-stat-card">
                <span>总片段</span>
                <strong>{{ formatCount(chunkTotalCount) }}</strong>
              </article>
              <article class="chunk-stat-card">
                <span>向量可用</span>
                <strong>{{ formatCount(chunkVectorReadyCount) }}</strong>
              </article>
              <article class="chunk-stat-card">
                <span>待处理</span>
                <strong>{{ formatCount(chunkVectorPendingCount) }}</strong>
              </article>
              <article class="chunk-stat-card">
                <span>平均 Token</span>
                <strong>{{ formatCount(chunkAverageTokens) }}</strong>
              </article>
            </div>

            <div class="chunk-table">
              <div class="chunk-table-head">
                <span>Chunk</span>
                <span>章节 / 标识</span>
                <span>来源 / 状态</span>
                <span>页码</span>
                <span>字符</span>
                <span>Token</span>
                <span>内容预览</span>
              </div>

              <article v-for="item in chunkRecords" :key="item.chunkId" class="chunk-row">
                <div class="chunk-cell chunk-cell-index" data-label="Chunk">
                  <strong>#{{ item.chunkNo }}</strong>
                  <span>{{ item.chunkId }}</span>
                </div>
                <div class="chunk-cell chunk-cell-section" data-label="章节 / 标识">
                  <strong>{{ item.sectionPath || '未识别章节' }}</strong>
                  <span>{{ item.sourceTypeName || '未知来源' }}</span>
                </div>
                <div class="chunk-cell chunk-cell-status" data-label="来源 / 状态">
                  <span class="chunk-chip">{{ item.sourceTypeName || '未知来源' }}</span>
                  <span class="chunk-chip" :class="`chunk-chip-${normalizeCode(item.vectorStatus) || '0'}`">
                    {{ item.vectorStatusName || '未知状态' }}
                  </span>
                </div>
                <div class="chunk-cell" data-label="页码">
                  <strong>{{ item.pageNo || '-' }}</strong>
                </div>
                <div class="chunk-cell" data-label="字符">
                  <strong>{{ formatCount(item.charCount) }}</strong>
                </div>
                <div class="chunk-cell" data-label="Token">
                  <strong>{{ formatCount(item.tokenCount) }}</strong>
                </div>
                <div class="chunk-cell chunk-cell-content" data-label="内容预览">
                  <p class="chunk-body">{{ item.chunkText }}</p>
                </div>
              </article>
            </div>
          </div>
        </section>

        <section class="detail-section">
          <div class="section-headline">
            <h4>最近任务摘要</h4>
            <span>{{ taskLogs.length ? `${taskLogs.length} 条日志` : '暂无日志' }}</span>
          </div>

          <div v-if="logLoading" class="empty-block compact-empty">正在加载任务日志...</div>
          <div v-else-if="!taskLogs.length" class="empty-block compact-empty">当前文档还没有可查看的任务日志。</div>

          <div v-else class="summary-log-list">
            <article v-for="log in taskLogs.slice(0, 3)" :key="log.id" class="summary-log-item">
              <div class="summary-log-head">
                <strong>{{ log.stageTypeName }} · {{ log.eventTypeName }}</strong>
                <span>{{ formatDateTime(log.createTime) }}</span>
              </div>
              <p>{{ log.content }}</p>
            </article>
            <button class="ghost-button summary-log-button" type="button" @click="openLogDrawer">
              查看完整任务时间线
            </button>
          </div>
        </section>
      </div>
    </article>

    <div v-else class="empty-block">
      正在加载文档详情...
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeftIcon, ArrowRightIcon, CheckCircleIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import { APIError, manageApi } from '../../api/api'
import AdminStatusBadge from '../../components/admin/AdminStatusBadge.vue'
import { formatCount, formatDateTime, hasCode, normalizeCode } from '../../utils/manageFormat'

const route = useRoute()
const router = useRouter()
const OPERATOR_ID = '10001'

const strategyLibrary = [
  { type: '1', label: '基于文档结构切块', description: '优先保留标题和章节边界' },
  { type: '2', label: '递归分块', description: '对超长内容继续裁剪兜底' },
  { type: '3', label: '语义分块', description: '优化主题边界和段落完整性' },
  { type: '4', label: '大模型智能切块', description: '处理复杂内容和低质量文本' }
]

const BUILD_STAGE_LIBRARY = [
  { code: '5', order: '01', label: '切块执行', description: '按照当前策略链路生成原始 chunk' },
  { code: '6', order: '02', label: '切块后处理', description: '清洗空块并整理最终可入库片段' },
  { code: '7', order: '03', label: '向量化', description: '生成 embedding 并写入 PGVector' },
  { code: '8', order: '04', label: '入库完成', description: '回写状态并将本次索引标记为可用' }
]

const BUILD_STAGE_CODE_SET = new Set(BUILD_STAGE_LIBRARY.map((item) => item.code))

const documentDetail = ref(null)
const strategyPlan = ref(null)
const selectedStrategyTypes = ref([])
const adjustNote = ref('')
const taskLogs = ref([])
const taskLogSnapshot = ref(null)
const buildTaskSnapshot = ref(null)
const chunkQuery = ref(null)
const loading = ref(false)
const planLoading = ref(false)
const confirmLoading = ref(false)
const buildLoading = ref(false)
const logLoading = ref(false)
const chunkLoading = ref(false)
const logDrawerOpen = ref(false)
const planPollTimer = ref(null)
const buildPollTimer = ref(null)
const pageNotice = reactive({
  type: 'info',
  message: ''
})

const documentId = computed(() => String(route.params.documentId || ''))
const canBuildIndex = computed(() => Boolean(documentDetail.value?.currentPlanId) && hasCode(documentDetail.value?.strategyStatus, 3))
const isBuildPolling = computed(() => buildPollTimer.value != null)
const selectedStrategyPreview = computed(() => buildStrategyPreview(selectedStrategyTypes.value))
const selectedStrategyRows = computed(() => buildSequenceRows(selectedStrategyPreview.value))
const chunkRecords = computed(() => Array.isArray(chunkQuery.value?.records) ? chunkQuery.value.records : [])
const chunkTotalCount = computed(() => Number(chunkQuery.value?.total || chunkRecords.value.length || 0))
const chunkVectorReadyCount = computed(() => {
  return chunkRecords.value.filter((item) => normalizeCode(item.vectorStatus) === '3').length
})
const chunkVectorPendingCount = computed(() => {
  return chunkRecords.value.filter((item) => normalizeCode(item.vectorStatus) !== '3').length
})
const chunkAverageTokens = computed(() => {
  if (!chunkRecords.value.length) {
    return 0
  }

  const totalTokens = chunkRecords.value.reduce((sum, item) => sum + Number(item.tokenCount || 0), 0)
  return Math.round(totalTokens / chunkRecords.value.length)
})
const hasBuildTaskSnapshot = computed(() => hasCode(buildTaskSnapshot.value?.taskType, 2))
const activeBuildTaskId = computed(() => {
  if (hasCode(documentDetail.value?.latestTaskType, 2)) {
    return documentDetail.value?.latestTaskId || ''
  }
  return documentDetail.value?.lastIndexTaskId || ''
})

const showBuildTracker = computed(() => {
  return Boolean(activeBuildTaskId.value) || hasBuildTaskSnapshot.value
})

const buildTrackerTitle = computed(() => {
  if (!showBuildTracker.value) {
    return ''
  }
  if (hasBuildTaskSnapshot.value && hasCode(buildTaskSnapshot.value?.taskStatus, 4)) {
    return `最近一次构建在「${buildTaskSnapshot.value?.currentStageName || '未知阶段'}」失败`
  }
  if ((hasBuildTaskSnapshot.value && hasCode(buildTaskSnapshot.value?.taskStatus, 3)) || hasCode(documentDetail.value?.indexStatus, 3)) {
    return '最近一次索引构建已完成'
  }
  return `当前阶段：${hasBuildTaskSnapshot.value ? (buildTaskSnapshot.value?.currentStageName || '索引构建中') : '索引构建中'}`
})

const buildTrackerDescription = computed(() => {
  if (!showBuildTracker.value) {
    return ''
  }
  if (hasBuildTaskSnapshot.value && hasCode(buildTaskSnapshot.value?.taskStatus, 4)) {
    return buildTaskSnapshot.value?.errorMsg || '请展开右侧时间线查看失败阶段和具体报错。'
  }
  if ((hasBuildTaskSnapshot.value && hasCode(buildTaskSnapshot.value?.taskStatus, 3)) || hasCode(documentDetail.value?.indexStatus, 3)) {
    return '即使任务执行很快，这里也会保留完整阶段轨迹，方便复盘和教学演示。'
  }
  return '系统正在自动轮询任务状态，阶段完成后会保留已完成轨迹，不会一闪而过。'
})

const buildStageItems = computed(() => {
  const taskStatus = normalizeCode(buildTaskSnapshot.value?.taskStatus)
  const currentStage = normalizeCode(buildTaskSnapshot.value?.currentStage)
  const logs = Array.isArray(buildTaskSnapshot.value?.logs) ? buildTaskSnapshot.value.logs : []
  const completedStages = new Set()
  const failedStages = new Set()
  const touchedStages = new Set()

  logs.forEach((log) => {
    const stageCode = normalizeCode(log.stageType)
    if (!BUILD_STAGE_CODE_SET.has(stageCode)) {
      return
    }
    touchedStages.add(stageCode)
    if (hasCode(log.eventType, 2)) {
      completedStages.add(stageCode)
    }
    if (hasCode(log.eventType, 3)) {
      failedStages.add(stageCode)
    }
  })

  const currentIndex = BUILD_STAGE_LIBRARY.findIndex((item) => item.code === currentStage)
  return BUILD_STAGE_LIBRARY.map((stage, index) => {
    let status = 'pending'
    let statusLabel = '等待执行'
    if (failedStages.has(stage.code) || (taskStatus === '4' && currentStage === stage.code)) {
      status = 'failed'
      statusLabel = '执行失败'
    }
    else if (taskStatus === '3') {
      status = 'completed'
      statusLabel = '已完成'
    }
    else if ((taskStatus === '1' || taskStatus === '2') && currentStage === stage.code) {
      status = 'current'
      statusLabel = '当前阶段'
    }
    else if (completedStages.has(stage.code) || ((taskStatus === '1' || taskStatus === '2') && currentIndex > index)) {
      status = 'completed'
      statusLabel = '已完成'
    }
    else if (touchedStages.has(stage.code)) {
      status = 'completed'
      statusLabel = '已完成'
    }
    return { ...stage, status, statusLabel }
  })
})

const buildStageRows = computed(() => buildSequenceRows(buildStageItems.value))

function showNotice(message, type = 'info') {
  pageNotice.type = type
  pageNotice.message = message
}

function clearNotice() {
  pageNotice.message = ''
}

function goBack() {
  router.push({ name: 'AdminDocuments' })
}

function buildSequenceRows(items) {
  const sourceList = Array.isArray(items) ? items : []
  const rows = []
  for (let index = 0; index < sourceList.length; index += 2) {
    const pair = sourceList.slice(index, index + 2)
    const rowIndex = rows.length
    const direction = rowIndex % 2 === 0 ? 'ltr' : 'rtl'
    const leftItem = direction === 'ltr' ? pair[0] || null : pair[1] || null
    const rightItem = direction === 'ltr' ? pair[1] || null : pair[0] || null
    rows.push({
      direction,
      leftItem,
      rightItem,
      downColumn: direction === 'ltr' ? 'right' : 'left'
    })
  }
  return rows
}

function normalizeStrategyTypeList(selectedTypes) {
  const seen = new Set()
  const availableTypes = new Set(strategyLibrary.map((item) => item.type))
  const orderedTypes = []

  ;(selectedTypes || []).forEach((item) => {
    const strategyType = normalizeCode(item)
    if (!strategyType || seen.has(strategyType) || !availableTypes.has(strategyType)) {
      return
    }
    seen.add(strategyType)
    orderedTypes.push(strategyType)
  })

  return orderedTypes
}

function buildStrategyPreview(selectedTypes) {
  return normalizeStrategyTypeList(selectedTypes)
    .map((type, index) => {
      const strategy = strategyLibrary.find((item) => item.type === type)
      return strategy ? { ...strategy, index, order: String(index + 1).padStart(2, '0') } : null
    })
    .filter(Boolean)
}

function toggleStrategy(type) {
  const normalizedType = normalizeCode(type)
  if (!normalizedType) {
    return
  }
  if (selectedStrategyTypes.value.includes(normalizedType)) {
    selectedStrategyTypes.value = selectedStrategyTypes.value.filter((item) => item !== normalizedType)
    return
  }
  selectedStrategyTypes.value = [...selectedStrategyTypes.value, normalizedType]
}

function moveStrategy(type, direction) {
  const sourceType = normalizeCode(type)
  const orderedTypes = normalizeStrategyTypeList(selectedStrategyTypes.value)
  const sourceIndex = orderedTypes.indexOf(sourceType)
  if (sourceIndex < 0) {
    return
  }
  const targetIndex = sourceIndex + direction
  if (targetIndex < 0 || targetIndex >= orderedTypes.length) {
    return
  }
  const nextList = [...orderedTypes]
  ;[nextList[sourceIndex], nextList[targetIndex]] = [nextList[targetIndex], nextList[sourceIndex]]
  selectedStrategyTypes.value = normalizeStrategyTypeList(nextList)
}

async function loadDocumentDetail() {
  documentDetail.value = await manageApi.queryDocumentDetail(documentId.value)
}

async function loadStrategyPlan() {
  planLoading.value = true
  try {
    strategyPlan.value = await manageApi.queryStrategyPlan(documentId.value)
    selectedStrategyTypes.value = Array.isArray(strategyPlan.value?.plan?.steps)
      ? normalizeStrategyTypeList(strategyPlan.value.plan.steps.map((item) => item.strategyType))
      : []
    adjustNote.value = ''
  } finally {
    planLoading.value = false
  }
}

async function loadTaskLogs() {
  const latestTaskId = documentDetail.value?.latestTaskId
  if (!latestTaskId) {
    taskLogs.value = []
    taskLogSnapshot.value = null
    return
  }
  logLoading.value = true
  try {
    const data = await manageApi.queryTaskLogs({
      taskId: latestTaskId,
      pageNo: '1',
      pageSize: '30'
    })
    taskLogSnapshot.value = data || null
    taskLogs.value = Array.isArray(data?.logs) ? data.logs : []
  } catch (error) {
    console.error('读取任务日志失败', error)
    taskLogSnapshot.value = null
    taskLogs.value = []
  } finally {
    logLoading.value = false
  }
}

async function loadBuildTaskLogs() {
  const buildTaskId = activeBuildTaskId.value
  if (!buildTaskId) {
    buildTaskSnapshot.value = null
    return
  }
  try {
    const data = await manageApi.queryTaskLogs({
      taskId: buildTaskId,
      pageNo: '1',
      pageSize: '30'
    })
    buildTaskSnapshot.value = data || null
  } catch (error) {
    console.error('读取构建任务日志失败', error)
    buildTaskSnapshot.value = null
  }
}

async function loadDocumentChunks() {
  chunkLoading.value = true
  try {
    chunkQuery.value = await manageApi.queryDocumentChunks({
      documentId: documentId.value,
      pageNo: 1,
      pageSize: 20
    })
  } catch (error) {
    console.error('读取 chunk 列表失败', error)
    chunkQuery.value = null
  } finally {
    chunkLoading.value = false
  }
}

async function loadAll() {
  loading.value = true
  clearNotice()
  try {
    await loadDocumentDetail()
    await Promise.all([
      loadStrategyPlan(),
      loadTaskLogs(),
      loadBuildTaskLogs(),
      loadDocumentChunks()
    ])
  } catch (error) {
    console.error('读取文档详情失败', error)
    showNotice(normalizeError(error, '读取文档详情失败'), 'danger')
  } finally {
    loading.value = false
  }
}

async function submitConfirmStrategy() {
  if (!strategyPlan.value?.plan?.planId) {
    showNotice('当前还没有可确认的策略方案。', 'danger')
    return
  }

  confirmLoading.value = true
  clearNotice()
  try {
    const steps = selectedStrategyPreview.value.map((item, index) => ({
      stepNo: String(index + 1),
      strategyType: item.type
    }))

    await manageApi.confirmStrategy({
      documentId: documentId.value,
      basePlanId: strategyPlan.value.plan.planId,
      adjustNote: adjustNote.value.trim(),
      operatorId: OPERATOR_ID,
      steps
    })
    showNotice('策略方案已确认，接下来可以直接构建索引。', 'success')
    await loadAll()
  } catch (error) {
    console.error('确认策略失败', error)
    showNotice(normalizeError(error, '确认策略失败'), 'danger')
  } finally {
    confirmLoading.value = false
  }
}

async function submitBuildIndex() {
  if (!documentDetail.value?.currentPlanId) {
    showNotice('当前文档没有可用的策略方案，不能构建索引。', 'danger')
    return
  }

  buildLoading.value = true
  clearNotice()
  try {
    const result = await manageApi.buildIndex({
      documentId: documentId.value,
      planId: documentDetail.value.currentPlanId,
      operatorId: OPERATOR_ID
    })
    showNotice(`索引任务 ${result.taskId} 已创建，系统正在异步构建中。`, 'success')
    await loadAll()
    startBuildPolling()
  } catch (error) {
    console.error('构建索引失败', error)
    showNotice(normalizeError(error, '构建索引失败'), 'danger')
  } finally {
    buildLoading.value = false
  }
}

function openLogDrawer() {
  logDrawerOpen.value = true
  loadTaskLogs()
}

function closeLogDrawer() {
  logDrawerOpen.value = false
}

function clearBuildPolling() {
  if (buildPollTimer.value) {
    window.clearInterval(buildPollTimer.value)
    buildPollTimer.value = null
  }
}

function startBuildPolling() {
  clearBuildPolling()
  let pollCount = 0
  buildPollTimer.value = window.setInterval(async () => {
    pollCount += 1
    try {
      await loadAll()
      const building = hasCode(documentDetail.value?.indexStatus, 2)
        || (hasCode(documentDetail.value?.latestTaskType, 2) && ['1', '2'].includes(normalizeCode(documentDetail.value?.latestTaskStatus)))
      if (!building || pollCount >= 30) {
        clearBuildPolling()
      }
    } catch (error) {
      console.error('轮询索引构建状态失败', error)
      clearBuildPolling()
    }
  }, 3000)
}

function startPlanPolling() {
  if (planPollTimer.value) {
    window.clearInterval(planPollTimer.value)
  }
  let pollCount = 0
  planPollTimer.value = window.setInterval(async () => {
    pollCount += 1
    try {
      await loadDocumentDetail()
      await loadStrategyPlan()
      if (strategyPlan.value?.planReady || normalizeCode(strategyPlan.value?.parseStatus) === '4' || pollCount >= 8) {
        window.clearInterval(planPollTimer.value)
        planPollTimer.value = null
      }
    } catch (error) {
      console.error('轮询策略结果失败', error)
      window.clearInterval(planPollTimer.value)
      planPollTimer.value = null
    }
  }, 2500)
}

function formatDuration(value) {
  const millis = Number(value || 0)
  if (!Number.isFinite(millis) || millis <= 0) {
    return '-'
  }
  if (millis < 1000) {
    return `${millis} ms`
  }
  if (millis < 60_000) {
    return `${(millis / 1000).toFixed(1)} s`
  }
  return `${(millis / 60_000).toFixed(1)} min`
}

function normalizeError(error, fallbackMessage) {
  if (error instanceof APIError && error.message) {
    return error.message
  }
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallbackMessage
}

watch(() => route.params.documentId, async (value, oldValue) => {
  if (!value || value === oldValue) {
    return
  }
  await loadAll()
})

watch(documentDetail, (value) => {
  if (!value) {
    clearBuildPolling()
    return
  }
  const building = hasCode(value.indexStatus, 2)
    || (hasCode(value.latestTaskType, 2) && ['1', '2'].includes(normalizeCode(value.latestTaskStatus)))
  if (building && !buildPollTimer.value) {
    startBuildPolling()
    return
  }
  if (!building && buildPollTimer.value) {
    clearBuildPolling()
  }
})

onMounted(async () => {
  await loadAll()
  if (!strategyPlan.value?.planReady && normalizeCode(strategyPlan.value?.parseStatus) !== '4') {
    startPlanPolling()
  }
})

onBeforeUnmount(() => {
  if (planPollTimer.value) {
    window.clearInterval(planPollTimer.value)
    planPollTimer.value = null
  }
  clearBuildPolling()
})
</script>

<style scoped>
.document-detail-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.page-top {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.panel-card {
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: var(--color-admin-panel);
  border-radius: 22px;
  box-shadow: var(--shadow-card);
  padding: 24px 26px;
}

.detail-content {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.section-eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--color-muted);
}

.detail-header,
.detail-statuses,
.build-progress-header,
.detail-secondary-actions,
.section-headline,
.summary-log-head,
.drawer-log-head,
.confirm-actions,
.strategy-chip-top,
.chunk-head,
.chunk-title-group,
.chunk-status-group,
.chunk-meta,
.drawer-summary,
.tracker-footer {
  display: flex;
  align-items: center;
}

.detail-header,
.build-progress-header,
.section-headline,
.chunk-head {
  justify-content: space-between;
  gap: 12px;
}

.detail-statuses,
.chunk-title-group,
.chunk-status-group,
.chunk-meta,
.tracker-footer {
  gap: 10px;
  flex-wrap: wrap;
}

.detail-header h3,
.section-headline h4,
.drawer-header h3 {
  margin: 0;
  color: var(--color-text-strong);
}

.detail-header h3 {
  font-family: var(--font-sans);
  font-size: clamp(34px, 3.2vw, 48px);
  font-weight: 700;
  line-height: 1.02;
  letter-spacing: -0.03em;
}

.section-headline h4,
.drawer-header h3 {
  font-size: 24px;
  letter-spacing: -0.02em;
}

.detail-subtitle,
.section-headline span,
.drawer-subtitle,
.summary-log-head span {
  color: var(--color-muted);
}

.detail-secondary-actions,
.confirm-actions {
  gap: 12px;
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.meta-item,
.reason-card,
.preview-box,
.selected-flow-board {
  padding: 14px 16px;
  border-radius: 16px;
  background: var(--color-admin-panel-muted);
  border: 1px solid rgba(17, 24, 39, 0.06);
}

.meta-item span,
.reason-card span,
.preview-box span,
.selected-flow-label {
  display: block;
  font-size: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--color-muted);
}

.meta-item strong {
  display: block;
  margin-top: 10px;
  color: var(--color-text-strong);
}

.detail-section {
  padding-top: 4px;
}

.timeline-list,
.summary-log-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 16px;
}

.timeline-item,
.summary-log-item {
  display: flex;
  gap: 14px;
  padding: 16px 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(17, 24, 39, 0.08);
}

.timeline-index {
  width: 38px;
  height: 38px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 12px;
  background: rgba(37, 87, 214, 0.1);
  color: var(--color-primary-strong);
  font-weight: 800;
}

.timeline-main strong,
.summary-log-head strong,
.chunk-title-group strong,
.drawer-log-head strong {
  color: var(--color-text-strong);
}

.timeline-main p,
.summary-log-item p,
.chunk-body {
  margin: 8px 0 0;
  color: var(--color-muted-strong);
  line-height: 1.7;
}

.chunk-chip {
  display: inline-flex;
  align-items: center;
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(17, 24, 39, 0.08);
  border: 1px solid rgba(17, 24, 39, 0.08);
  color: var(--color-text);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.chunk-chip-1 {
  background: rgba(17, 24, 39, 0.08);
  color: var(--color-text);
}

.chunk-chip-2 {
  background: rgba(37, 87, 214, 0.1);
  color: var(--color-primary-strong);
}

.chunk-chip-3 {
  background: rgba(21, 115, 91, 0.1);
  color: #12644f;
}

.chunk-chip-4 {
  background: rgba(179, 76, 47, 0.1);
  color: #9f422b;
}

.chunk-body {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.chunk-table-panel {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.chunk-toolbar {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.chunk-stat-card {
  padding: 14px 16px;
  border-radius: 16px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: rgba(255, 255, 255, 0.86);
}

.chunk-stat-card span {
  display: block;
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--color-muted);
}

.chunk-stat-card strong {
  display: block;
  margin-top: 10px;
  color: var(--color-text-strong);
  font-size: 24px;
  line-height: 1.15;
}

.chunk-table {
  border-radius: 18px;
  overflow: hidden;
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: rgba(255, 255, 255, 0.9);
}

.chunk-table-head,
.chunk-row {
  display: grid;
  grid-template-columns: 112px minmax(180px, 1.4fr) 180px 84px 96px 96px minmax(260px, 2.2fr);
  gap: 14px;
  align-items: start;
}

.chunk-table-head {
  padding: 14px 16px;
  background: #f4f7fa;
  border-bottom: 1px solid rgba(17, 24, 39, 0.08);
}

.chunk-table-head span {
  font-size: 11px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--color-muted);
  font-weight: 700;
}

.chunk-row {
  padding: 16px;
  border-bottom: 1px solid rgba(17, 24, 39, 0.08);
}

.chunk-row:last-child {
  border-bottom: none;
}

.chunk-cell {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.chunk-cell strong {
  color: var(--color-text-strong);
  line-height: 1.35;
}

.chunk-cell span {
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
}

.chunk-cell-index strong {
  font-size: 20px;
}

.chunk-cell-status {
  gap: 8px;
}

.chunk-cell-status .chunk-chip {
  width: fit-content;
}

.chunk-cell-content .chunk-body {
  display: -webkit-box;
  overflow: hidden;
  white-space: normal;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
}

.selected-flow-board {
  margin-top: 14px;
}

.sequence-board {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sequence-card-placeholder {
  min-height: 1px;
}

.selected-flow-sequence,
.build-stage-board {
  margin-top: 14px;
}

.sequence-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 56px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
}

.sequence-inline-arrow,
.sequence-down-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-primary);
  font-size: 38px;
  font-weight: 900;
  line-height: 1;
}

.sequence-inline-arrow-empty {
  visibility: hidden;
}

.sequence-down-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 56px minmax(0, 1fr);
  min-height: 44px;
  align-items: center;
}

.sequence-down-row-left .sequence-down-arrow {
  grid-column: 1;
}

.sequence-down-row-right .sequence-down-arrow {
  grid-column: 3;
}

.selected-flow-card {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) auto;
  gap: 14px;
  align-items: center;
  padding: 16px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  border-radius: 18px;
  background: #ffffff;
}

.selected-flow-order {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  border-radius: 16px;
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-strong));
  color: #ffffff;
  font-size: 18px;
  font-weight: 900;
}

.selected-flow-content strong {
  display: block;
  color: var(--color-text-strong);
}

.selected-flow-content span {
  display: block;
  margin-top: 8px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.6;
}

.selected-flow-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.flow-action-button {
  min-width: 58px;
  padding: 8px 10px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  border-radius: 12px;
  background: rgba(244, 246, 249, 0.96);
  color: var(--color-text);
  font-size: 12px;
  font-weight: 700;
}

.flow-action-button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.strategy-picker {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.strategy-chip {
  text-align: left;
  border: 1px solid rgba(17, 24, 39, 0.08);
  border-radius: 18px;
  padding: 16px 18px;
  background: rgba(255, 255, 255, 0.94);
}

.strategy-chip.active {
  border-color: rgba(37, 87, 214, 0.5);
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.15), rgba(37, 87, 214, 0.06));
  box-shadow: 0 4px 16px rgba(37, 87, 214, 0.18);
}

.strategy-chip.active .strategy-chip-state {
  background: rgba(37, 87, 214, 0.16);
  color: #1a3fa0;
}

.strategy-chip strong {
  display: block;
  color: var(--color-text-strong);
}

.strategy-chip span {
  display: block;
  margin-top: 8px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.6;
}

.strategy-chip-state {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(17, 24, 39, 0.08);
  color: var(--color-text);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.strategy-chip-check {
  width: 22px;
  height: 22px;
  color: var(--color-primary-strong);
}

.preview-box {
  margin-top: 16px;
  background: rgba(37, 87, 214, 0.06);
  border: 1px solid rgba(37, 87, 214, 0.15);
  border-radius: 14px;
  padding: 16px 18px;
}

.preview-box > span {
  font-weight: 700;
  color: var(--color-primary-strong);
  font-size: 14px;
}

.reason-card p,
.preview-empty,
.selected-flow-empty {
  margin: 10px 0 0;
  color: var(--color-muted-strong);
  line-height: 1.7;
}

.preview-flow {
  margin-top: 12px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}

.preview-tag {
  padding: 8px 16px;
  border-radius: 999px;
  background: #f0faf0;
  border: 1px solid rgba(34, 139, 34, 0.35);
  color: #1a6b1a;
  font-size: 13px;
  font-weight: 700;
}

.flow-arrow {
  position: relative;
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 52px;
  margin: 4px 0;
}

.flow-arrow::before {
  content: '';
  position: absolute;
  left: 50%;
  top: 2px;
  bottom: 2px;
  width: 2px;
  transform: translateX(-50%);
  background: linear-gradient(180deg, rgba(37, 87, 214, 0.16), rgba(37, 87, 214, 0.42), rgba(37, 87, 214, 0.16));
}

.preview-arrow,
.back-icon,
.drawer-icon {
  width: 18px;
  height: 18px;
  color: var(--color-primary-strong);
}

.flow-arrow-icon {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: auto;
  height: auto;
  color: var(--color-primary-strong);
  background: transparent;
  font-size: 42px;
  font-weight: 900;
  line-height: 1;
  filter: drop-shadow(0 4px 10px rgba(37, 87, 214, 0.16));
}

.confirm-actions {
  margin-top: 16px;
  flex-direction: column;
  align-items: stretch;
}

.adjust-input {
  width: 100%;
  border: 1px solid rgba(17, 24, 39, 0.1);
  border-radius: 14px;
  padding: 12px 14px;
  background: #ffffff;
  outline: none;
  color: var(--color-text);
}

.adjust-input:focus {
  border-color: rgba(37, 87, 214, 0.28);
  box-shadow: 0 0 0 4px rgba(37, 87, 214, 0.08);
}

.strategy-submit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.action-button,
.primary-button,
.ghost-button {
  border: 1px solid transparent;
  border-radius: 14px;
  padding: 12px 16px;
  font-weight: 700;
}

.action-button {
  color: #ffffff;
  font-weight: 800;
}

.action-button-confirm {
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-strong));
  box-shadow: 0 14px 24px rgba(37, 87, 214, 0.18);
}

.action-button-build {
  background: linear-gradient(135deg, #111827, var(--color-accent));
  box-shadow: 0 14px 24px rgba(15, 23, 36, 0.14);
}

.action-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ghost-button {
  color: var(--color-text);
  background: rgba(255, 255, 255, 0.86);
  border-color: rgba(17, 24, 39, 0.08);
}

.ghost-button:hover:not(:disabled),
.action-button:hover:not(:disabled),
.strategy-chip:hover,
.flow-action-button:hover:not(:disabled) {
  transform: translateY(-1px);
}

.inline-notice {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 14px;
}

.inline-notice-danger {
  background: rgba(179, 76, 47, 0.08);
  border: 1px solid rgba(179, 76, 47, 0.12);
  color: #9f422b;
}

.empty-block {
  min-height: 260px;
  display: grid;
  place-items: center;
  text-align: center;
  color: var(--color-muted);
  border-radius: 18px;
  border: 1px dashed rgba(17, 24, 39, 0.16);
  background: rgba(244, 246, 249, 0.72);
}

.compact-empty {
  min-height: 140px;
  margin-top: 14px;
}

.build-progress-card {
  padding: 18px 20px;
  border-radius: 20px;
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.08), rgba(255, 255, 255, 0.98));
  border: 1px solid rgba(37, 87, 214, 0.12);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.build-pulse {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(37, 87, 214, 0.1);
  color: var(--color-primary-strong);
  font-size: 12px;
  font-weight: 800;
}

.build-pulse-static {
  background: rgba(17, 24, 39, 0.08);
  color: var(--color-text);
}

.build-progress-text {
  margin: 10px 0 0;
  color: var(--color-muted);
  line-height: 1.7;
}

.stage-card {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  padding: 16px;
  border-radius: 18px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: rgba(255, 255, 255, 0.94);
}

.stage-order {
  width: 52px;
  height: 52px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  font-size: 16px;
  font-weight: 900;
  letter-spacing: 0.08em;
  background: rgba(17, 24, 39, 0.08);
  color: var(--color-text);
}

.stage-body strong {
  display: block;
  color: var(--color-text-strong);
}

.stage-body span {
  display: block;
  margin-top: 8px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.6;
}

.stage-body em {
  display: inline-flex;
  margin-top: 10px;
  padding: 4px 10px;
  border-radius: 999px;
  font-style: normal;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.stage-current {
  border-color: transparent;
  background: linear-gradient(135deg, #111827, var(--color-primary));
  box-shadow: 0 18px 28px rgba(17, 24, 39, 0.18);
}

.stage-current .stage-order,
.stage-current .stage-body strong,
.stage-current .stage-body span,
.stage-current .stage-body em {
  color: #ffffff;
  background: transparent;
}

.stage-completed .stage-order,
.stage-completed .stage-body em {
  background: rgba(21, 115, 91, 0.1);
  color: #12644f;
}

.stage-failed {
  border-color: rgba(179, 76, 47, 0.14);
  background: rgba(255, 247, 237, 0.96);
}

.stage-failed .stage-order,
.stage-failed .stage-body em {
  background: rgba(179, 76, 47, 0.1);
  color: #9f422b;
}

.tracker-footer span {
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(17, 24, 39, 0.06);
  color: var(--color-muted-strong);
  font-size: 12px;
  font-weight: 700;
}

.summary-log-button {
  align-self: flex-start;
}

.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(10, 22, 35, 0.42);
  z-index: 18;
}

.log-drawer {
  position: fixed;
  top: 18px;
  right: 18px;
  bottom: 18px;
  width: min(560px, calc(100vw - 36px));
  z-index: 19;
  border: 1px solid rgba(17, 24, 39, 0.08);
  border-radius: 24px;
  background: rgba(250, 251, 253, 0.98);
  box-shadow: var(--shadow-panel);
  display: flex;
  flex-direction: column;
  padding: 24px;
}

.drawer-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
}

.icon-button {
  width: 40px;
  height: 40px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  border-radius: 14px;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.86);
}

.summary-chip {
  padding: 10px 12px;
  border-radius: 14px;
  background: var(--color-admin-panel-muted);
  border: 1px solid rgba(17, 24, 39, 0.06);
  display: flex;
  align-items: center;
  gap: 10px;
}

.drawer-empty {
  margin-top: 20px;
  min-height: 180px;
  display: grid;
  place-items: center;
  text-align: center;
  color: #6e849c;
}

.drawer-timeline {
  margin-top: 20px;
  padding-right: 6px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.drawer-log-item {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 12px;
}

.drawer-log-node {
  position: relative;
  width: 18px;
}

.drawer-log-node::before {
  content: '';
  position: absolute;
  top: 6px;
  left: 6px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary);
  box-shadow: 0 0 0 4px rgba(37, 87, 214, 0.12);
}

.drawer-log-node::after {
  content: '';
  position: absolute;
  top: 18px;
  left: 8px;
  bottom: -18px;
  width: 2px;
  background: linear-gradient(180deg, rgba(37, 87, 214, 0.2), rgba(17, 24, 39, 0.08));
}

.drawer-log-item:last-child .drawer-log-node::after {
  display: none;
}

.drawer-log-body {
  padding: 14px 16px;
  border-radius: 18px;
  background: rgba(244, 246, 249, 0.96);
  border: 1px solid rgba(17, 24, 39, 0.06);
}

.drawer-log-body p {
  margin: 10px 0 0;
}

.drawer-log-detail {
  margin: 12px 0 0;
  padding: 12px 14px;
  border-radius: 14px;
  background: #0f1724;
  color: #dbe5f5;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.6;
}

.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 0.22s ease;
}

.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}

.drawer-slide-enter-active,
.drawer-slide-leave-active {
  transition: transform 0.26s ease, opacity 0.26s ease;
}

.drawer-slide-enter-from,
.drawer-slide-leave-to {
  opacity: 0;
  transform: translateX(24px);
}

@media (max-width: 960px) {
  .page-top,
  .detail-header,
  .build-progress-header,
  .section-headline,
  .detail-secondary-actions,
  .confirm-actions,
  .drawer-log-head,
  .tracker-footer,
  .chunk-head {
    flex-direction: column;
    align-items: stretch;
  }

  .meta-grid,
  .strategy-picker {
    grid-template-columns: 1fr;
  }

  .chunk-toolbar {
    grid-template-columns: 1fr 1fr;
  }

  .sequence-row {
    grid-template-columns: 1fr;
  }

  .selected-flow-card {
    grid-template-columns: 56px minmax(0, 1fr);
  }

  .selected-flow-actions {
    grid-column: span 2;
    flex-direction: row;
  }

  .sequence-inline-arrow,
  .sequence-down-row {
    justify-content: center;
  }

  .sequence-down-row {
    grid-template-columns: 1fr;
  }

  .sequence-down-row-left .sequence-down-arrow,
  .sequence-down-row-right .sequence-down-arrow {
    grid-column: 1;
  }

  .strategy-submit-actions {
    flex-direction: column;
  }

  .chunk-table-head {
    display: none;
  }

  .chunk-row {
    grid-template-columns: 1fr 1fr;
  }

  .chunk-cell {
    padding-top: 2px;
  }

  .chunk-cell::before {
    content: attr(data-label);
    font-size: 11px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--color-muted);
  }

  .chunk-cell-content {
    grid-column: 1 / -1;
  }
}
</style>
