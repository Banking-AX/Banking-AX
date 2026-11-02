import type { RepaymentOrder } from './b01'

// Values the server may not provide are optional; the screen hides an item when its value is missing.

export type WorkTaskType = 'A' | 'B' | 'C'
export type WorkTab = 'progress' | 'review' | 'done' | 'closed'
/** Design statuses plus manual ("영업점 상담 필요") and pending ("처리 대기"), which are new. */
export type WorkStatus = 'hold' | 'plan' | 'recv' | 'ok' | 'done' | 'lack' | 'cancel' | 'manual' | 'pending'
export type WorkSubTone = 'default' | 'muted' | 'warning' | 'danger'

/** requestedAt and updatedAt are ISO timestamps. */
export type WorkRecordItem = {
  recordId: string
  tab: WorkTab
  taskType: WorkTaskType
  name: string
  no?: string
  chatTitle?: string
  chatExpired?: boolean
  requestedAt: string
  updatedAt: string
  status: WorkStatus
  sub?: string
  subTone?: WorkSubTone
  detailPath?: string
}

export type WorkRecordList = { asOf: string; items: WorkRecordItem[]; retentionDays?: number; draftResetDays?: number }

export type TimelineTone = 'gray' | 'green' | 'blue' | 'amber'
/** time is an ISO timestamp. */
export type TimelineEvent = { time: string; text: string; tone: TimelineTone }

/** sentAt is an ISO timestamp. */
export type EmailNotice = { sentAt?: string; maskedEmail?: string }

type RecordHeader = {
  recordId: string
  title: string
  code?: string
  chatTitle?: string
  timeline: TimelineEvent[]
  basis?: string
}

/** PENDING: signature and SMS are done but the change has not been processed (no processedAt or email). */
export type B01RecordDetail = RecordHeader & {
  kind: 'b01'
  status: 'DONE' | 'PENDING'
  beforeOrder: RepaymentOrder
  afterOrder: RepaymentOrder
  loanLabel: string
  targetLoan?: string
  excludedLoan?: string
  ruling?: string
  consent?: { at?: string; itemCount?: number }
  processedAt?: string
  email?: EmailNotice
  thirdPartyProvider?: boolean
}

export type B02ManualRecordDetail = RecordHeader & {
  kind: 'b02manual'
  explanation?: string
  account?: string
  stopReason?: string
  decidedAt?: string
  email?: EmailNotice
  documents?: string[]
}

export type WorkRecordDetail = B01RecordDetail | B02ManualRecordDetail

/** Rejects with ApiError; an unknown record is ApiError('WORK_RECORD_NOT_FOUND', 404). */
export interface WorkRecordsAdapter {
  // 대응하는 서버 API 없음, 추후 연결.
  list(): Promise<WorkRecordList>
  // 대응하는 서버 API 없음, 추후 연결.
  getDetail(recordId: string): Promise<WorkRecordDetail>
}
