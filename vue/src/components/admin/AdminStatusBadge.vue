<template>
  <span class="status-badge" :class="badgeClass">
    {{ label || '未设置' }}
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { normalizeCode } from '../../utils/manageFormat'

const props = defineProps({
  label: {
    type: String,
    default: ''
  },
  type: {
    type: String,
    default: 'default'
  },
  code: {
    type: [String, Number],
    default: ''
  }
})

const badgeClass = computed(() => {
  if (props.type === 'parse') {
    return mapParseClass(normalizeCode(props.code))
  }
  if (props.type === 'strategy') {
    return mapStrategyClass(normalizeCode(props.code))
  }
  if (props.type === 'index') {
    return mapIndexClass(normalizeCode(props.code))
  }
  if (props.type === 'task') {
    return mapTaskClass(normalizeCode(props.code))
  }
  return 'status-default'
})

function mapParseClass(code) {
  if (code === '3') return 'status-success'
  if (code === '2') return 'status-processing'
  if (code === '4') return 'status-danger'
  return 'status-waiting'
}

function mapStrategyClass(code) {
  if (code === '3') return 'status-success'
  if (code === '2') return 'status-processing'
  return 'status-waiting'
}

function mapIndexClass(code) {
  if (code === '3') return 'status-success'
  if (code === '2') return 'status-processing'
  if (code === '4') return 'status-danger'
  return 'status-waiting'
}

function mapTaskClass(code) {
  if (code === '3') return 'status-success'
  if (code === '2' || code === '1') return 'status-processing'
  if (code === '4') return 'status-danger'
  return 'status-default'
}
</script>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 6px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  border: 1px solid transparent;
}

.status-default {
  background: rgba(148, 163, 184, 0.12);
  color: #475569;
  border-color: rgba(148, 163, 184, 0.22);
}

.status-waiting {
  background: rgba(217, 119, 6, 0.12);
  color: #a16207;
  border-color: rgba(217, 119, 6, 0.22);
}

.status-processing {
  background: rgba(13, 124, 124, 0.12);
  color: #0f766e;
  border-color: rgba(13, 124, 124, 0.2);
}

.status-success {
  background: rgba(15, 118, 110, 0.1);
  color: #0f766e;
  border-color: rgba(15, 118, 110, 0.22);
}

.status-danger {
  background: rgba(194, 65, 12, 0.12);
  color: #c2410c;
  border-color: rgba(194, 65, 12, 0.22);
}
</style>
