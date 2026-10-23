"""DTOs aligned to agent_review_run, review_item, and related DDL columns.

They deliberately model an Agent report, not a Spring state mutation or a
staff_review decision.  Spring remains the system of record for those values.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Literal


TaskCode = Literal['DEBT_ADJUSTMENT', 'INHERITANCE_DEPOSIT']
ReviewSource = Literal['SPRING_RULE', 'AGENT_SEMANTIC', 'PRE_SUBMISSION']
RunStatus = Literal['SUCCEEDED', 'FAILED']
ReviewStatus = Literal['WAITING_CUSTOMER', 'READY_FOR_STAFF', 'FAILED_NEEDS_STAFF']


@dataclass(frozen=True)
class CheckRequest:
    request_id: str
    case_id: int
    revision: int
    task_code: TaskCode
    check_type: str = 'STAFF_PRECHECK'
    case_snapshot: dict[str, Any] = field(default_factory=dict)
    rag_query: str | None = None
    audience: Literal['customer', 'staff'] = 'staff'

    def __post_init__(self) -> None:
        if not self.request_id:
            raise ValueError('request_id is required')
        if self.case_id <= 0 or self.revision < 0:
            raise ValueError('case_id and revision are invalid')
        if self.check_type != 'STAFF_PRECHECK':
            raise ValueError('unsupported check_type')

    def fingerprint(self) -> tuple[object, ...]:
        # The snapshot is not persisted here; its repr only guards accidental
        # request-id reuse in this in-memory Phase 0 executor.
        return (self.case_id, self.revision, self.task_code, self.check_type, repr(sorted(self.case_snapshot.items())), self.rag_query)


@dataclass(frozen=True)
class PolicyEvidence:
    doc_id: str
    title: str
    section: str
    source_pages: list[int]
    verified: bool
    text: str


@dataclass(frozen=True)
class ReviewItem:
    source: ReviewSource
    category: str
    result: str
    message: str
    basis_doc_id: str | None = None
    document_file_id: int | None = None
    heir_id: int | None = None
    evidence: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {
            'source': self.source,
            'category': self.category,
            'result': self.result,
            'message': self.message,
            'basisDocId': self.basis_doc_id,
            'documentFileId': self.document_file_id,
            'heirId': self.heir_id,
            'evidence': self.evidence,
        }


@dataclass(frozen=True)
class CheckResponse:
    request_id: str
    case_id: int
    revision: int
    task_code: TaskCode
    check_type: str
    status: RunStatus
    review_status: ReviewStatus
    review_items: list[ReviewItem]
    error: dict[str, str] | None = None
    policy_evidence: list[PolicyEvidence] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return {
            'requestId': self.request_id,
            'caseId': self.case_id,
            'revision': self.revision,
            'taskCode': self.task_code,
            'checkType': self.check_type,
            'status': self.status,
            'reviewStatus': self.review_status,
            'reviewItems': [item.as_dict() for item in self.review_items],
            'error': self.error,
            'policyEvidence': [asdict(item) for item in self.policy_evidence],
        }
