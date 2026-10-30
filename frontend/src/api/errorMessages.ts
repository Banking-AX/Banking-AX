import { ApiError } from './client'

const DEFAULT_MESSAGE = '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.'

const MESSAGES: Record<string, string> = {
  // Client
  NETWORK_ERROR: '네트워크 연결을 확인하고 다시 시도해 주세요.',
  INVALID_RESPONSE: '서버 응답을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.',

  // Auth (contract agreed ahead of the backend implementation)
  AUTH_INVALID_CREDENTIALS: '아이디 또는 비밀번호를 다시 확인해 주세요.',
  AUTH_EXPIRED: '로그인 시간이 만료되어 자동으로 로그아웃되었어요.',

  // Spring common
  FORBIDDEN: '이 정보에 접근할 권한이 없어요. 다시 로그인해 주세요.',
  NOT_FOUND: '요청한 정보를 찾을 수 없어요.',
  INVALID_REQUEST: '요청 내용을 다시 확인해 주세요.',
  INVALID_STATE: '현재 업무 상태에서는 진행할 수 없어요. 화면을 새로고침해 주세요.',
  STATE_CHANGED: '업무 상태가 바뀌었어요. 화면을 새로고침한 뒤 다시 시도해 주세요.',
  VERSION_CONFLICT: '다른 곳에서 내용이 바뀌었어요. 화면을 새로고침한 뒤 다시 시도해 주세요.',
  DUPLICATE_REQUEST_CONFLICT: '같은 요청이 이미 처리 중이에요. 잠시 후 다시 확인해 주세요.',
  CUSTOMER_NOT_FOUND: '고객 정보를 확인할 수 없어요.',
  CHAT_ROOM_NOT_FOUND: '대화방을 찾을 수 없어요. 새 대화를 시작해 주세요.',

  // Workflow
  INVALID_TASK: '지원하지 않는 업무예요.',
  INVALID_TASK_CODE: '지원하지 않는 업무예요.',
  WORKFLOW_ACTIVE_EXISTS: '진행 중인 업무가 있어요. 진행 중인 업무를 마치거나 보류한 뒤 다시 요청해 주세요.',
  WORK_ITEM_NOT_AVAILABLE: '지금 진행할 수 있는 업무가 아니에요.',
  TASK_RESULT_INVALID: '조회 결과를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.',
  TASK_RESULT_SERIALIZATION_FAILED: '조회 결과를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.',
  TASK_RESULT_NOT_AVAILABLE: '조회 결과가 아직 없어요.',

  // A-01
  INVALID_DATE_RANGE: '조회 기간을 다시 확인해 주세요. 시작일은 종료일보다 늦을 수 없어요.',
  INVALID_AMOUNT_RANGE: '금액 조건을 다시 확인해 주세요.',
  INVALID_PAGINATION: '조회 조건을 다시 확인해 주세요.',

  // Case · verification
  CASE_NOT_FOUND: '접수 정보를 찾을 수 없어요.',
  CASE_WORKITEM_MISMATCH: '접수 정보가 업무와 일치하지 않아요. 화면을 새로고침해 주세요.',
  CANCEL_NOT_ALLOWED: '직원 검토가 시작되어 취소할 수 없어요.',
  INTAKE_REQUIRED: '필수 정보를 먼저 입력해 주세요.',
  APPLICATION_INVALID: '신청 내용을 다시 확인해 주세요.',
  CONSENT_OR_SIGNATURE_REQUIRED: '동의와 서명을 먼저 완료해 주세요.',
  MANUAL_REVIEW_REQUIRED: '이 요청은 직원 확인이 필요해요.',
  OTP_INVALID: '인증번호가 올바르지 않아요. 다시 입력해 주세요.',
  OTP_ATTEMPTS_EXCEEDED: '인증번호 입력 가능 횟수를 넘었어요. 인증번호를 다시 받아 주세요.',
  VERIFICATION_BINDING_INVALID: '인증이 만료되었거나 올바르지 않아요. 다시 인증해 주세요.',
  ONE_APPLICANT_REQUIRED: '신청인은 한 명만 지정해 주세요.',
  HEIR_PERSON_REF_INVALID: '상속인 정보를 다시 확인해 주세요.',
  HEIR_RETRY_CONFLICT: '상속인 정보가 바뀌었어요. 화면을 새로고침해 주세요.',
  DELEGATION_RECEIPT_INVALID: '위임장 접수번호를 다시 확인해 주세요.',

  // Documents
  REQUIRED_DOCUMENT_MISSING: '필수 서류가 아직 제출되지 않았어요.',
  DOCUMENT_NOT_FOUND: '서류를 찾을 수 없어요.',
  DOCUMENT_SLOT_UNKNOWN: '제출할 서류 항목을 확인할 수 없어요.',
  DOCUMENT_SLOT_AMBIGUOUS: '제출할 서류 항목을 확인할 수 없어요.',
  DOCUMENT_TYPE_UNAVAILABLE: '제출할 수 없는 서류 종류예요.',
  DOCUMENT_TYPE_SCOPE_MISMATCH: '이 업무에 제출할 수 없는 서류예요.',
  DOCUMENT_VERSION_CONFLICT: '서류 정보가 바뀌었어요. 화면을 새로고침해 주세요.',
  DOCUMENT_REQUIREMENT_RETRY_CONFLICT: '서류 정보가 바뀌었어요. 화면을 새로고침해 주세요.',
  DOCUMENT_STATUS_INCONSISTENT: '서류 상태를 확인할 수 없어요. 화면을 새로고침해 주세요.',
  INVALID_DOCUMENT_STATUS: '서류 상태를 확인할 수 없어요. 화면을 새로고침해 주세요.',
  INVALID_DOCUMENT_METADATA: '서류 정보를 다시 확인해 주세요.',
  DOCUMENT_STORAGE_FAILED: '서류를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.',
  DOCUMENT_STORAGE_UNAVAILABLE: '서류를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.',
  DOCUMENT_METADATA_STORE_FAILED: '서류 정보를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.',
  INVALID_STORAGE_KEY: '서류를 불러오지 못했어요.',
  FILE_TOO_LARGE: '파일 크기는 10MB 이하로 올려 주세요.',
  EMPTY_FILE: '빈 파일은 올릴 수 없어요.',
  UNSUPPORTED_MIME: 'PDF, JPG, PNG 파일만 올릴 수 있어요.',
  UNSUPPORTED_FILE_TYPE: 'PDF, JPG, PNG 파일만 올릴 수 있어요.',
  UNSUPPORTED_FILE_CONTENT: '파일 내용을 확인할 수 없어요. 다른 파일로 올려 주세요.',
  FILE_CONTENT_TYPE_MISMATCH: '파일 형식이 확장자와 맞지 않아요. 다른 파일로 올려 주세요.',

  // Staff review
  STAFF_PRECHECK_REQUIRED: 'AI 사전점검이 아직 완료되지 않았어요.',
  STAFF_DECISION_ALREADY_RECORDED: '이미 검토 결과가 등록되었어요.',
  STAFF_REVIEW_STALE_OR_INVALID_CASE: '접수 내용이 바뀌었어요. 화면을 새로고침해 주세요.',
  STAFF_AGENT_UNAVAILABLE: 'AI 사전점검을 지금 실행할 수 없어요. 잠시 후 다시 시도해 주세요.',
  STAFF_AGENT_INVALID_RESPONSE: 'AI 사전점검 결과를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.',
  STAFF_AGENT_DOCUMENT_MISMATCH: 'AI 사전점검 결과가 제출 서류와 맞지 않아요. 다시 점검해 주세요.',
  STAFF_AGENT_GATEWAY_FORBIDDEN: 'AI 사전점검을 요청할 권한이 없어요.',

  // FastAPI customer agent
  INTENT_LLM_OUTPUT_INVALID: '답변을 만드는 중에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.',
  INTENT_CLASSIFICATION_UNAVAILABLE: '답변을 만드는 중에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.',
  INTENT_LLM_NOT_CONFIGURED: '지금은 AI 금융 도우미를 이용할 수 없어요.',
  INTENT_LLM_REQUEST_FAILED: '답변을 만드는 중에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.',
  INTENT_LLM_EMPTY_OUTPUT: '답변을 만드는 중에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.',
  WORKFLOW_START_UNAVAILABLE: '업무를 시작하지 못했어요. 잠시 후 다시 시도해 주세요.',
  WORKFLOW_START_INVALID_RESPONSE: '업무를 시작하지 못했어요. 잠시 후 다시 시도해 주세요.',
  LOAN_INQUIRY_UNAVAILABLE: '대출계좌 정보를 조회하지 못했어요. 잠시 후 다시 시도해 주세요.',
  LOAN_INQUIRY_INVALID_RESPONSE: '대출계좌 정보를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.',
  SPRING_TOOL_UNAVAILABLE: '금융정보 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.',
  SPRING_TOOL_GATEWAY_TOKEN_MISSING: '지금은 금융정보를 조회할 수 없어요.',

  // FastAPI RAG
  RETRIEVAL_TIMEOUT: '안내 문서를 찾는 데 시간이 오래 걸리고 있어요. 다시 시도해 주세요.',
  VECTOR_DB_UNAVAILABLE: '지금은 안내 문서를 찾을 수 없어요. 잠시 후 다시 시도해 주세요.',
  EMBEDDING_MODEL_ERROR: '지금은 안내 문서를 찾을 수 없어요. 잠시 후 다시 시도해 주세요.',
  INTERNAL_ERROR: DEFAULT_MESSAGE,
}

export function toUserMessage(error: unknown): string {
  if (error instanceof ApiError) return MESSAGES[error.code] ?? DEFAULT_MESSAGE
  return DEFAULT_MESSAGE
}
