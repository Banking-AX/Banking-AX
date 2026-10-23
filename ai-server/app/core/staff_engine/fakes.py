"""In-memory Phase 0 ports.  They never contact storage, SMTP, Spring, or SMS."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any


class PortError(RuntimeError):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class FakeCase:
    case_id: int
    revision: int
    task_code: str
    case_status: str = 'SUBMITTED'
    review_status: str = 'PENDING'


class FakeCasePort:
    def __init__(self, cases: list[FakeCase] | None = None) -> None:
        self._cases = {case.case_id: case for case in cases or []}

    def get_case(self, case_id: int, revision: int, task_code: str) -> FakeCase:
        case = self._cases.get(case_id)
        if case is None:
            raise PortError('CASE_NOT_FOUND')
        if case.revision != revision or case.task_code != task_code:
            raise PortError('STALE_REVISION')
        if case.case_status != 'SUBMITTED':
            raise PortError('CASE_NOT_SUBMITTED')
        return case


class FakeDocumentStore:
    def __init__(self) -> None:
        self._files: dict[int, list[dict[str, Any]]] = {}

    def register(self, required_document_id: int, mime_type: str, size_bytes: int) -> dict[str, Any]:
        if size_bytes > 10 * 1024 * 1024:
            raise PortError('FILE_TOO_LARGE')
        if mime_type not in {'application/pdf', 'image/jpeg', 'image/png'}:
            raise PortError('UNSUPPORTED_MIME')
        versions = self._files.setdefault(required_document_id, [])
        item = {
            'requiredDocumentId': required_document_id,
            'documentFileId': required_document_id * 1000 + len(versions) + 1,
            'version': len(versions) + 1,
            'mimeType': mime_type,
            'sizeBytes': size_bytes,
            'status': 'UPLOADED',
        }
        versions.append(item)
        return dict(item)

    def latest(self, required_document_id: int) -> dict[str, Any]:
        try:
            return dict(self._files[required_document_id][-1])
        except (KeyError, IndexError) as exc:
            raise PortError('DOCUMENT_NOT_FOUND') from exc


class FakeNotificationPort:
    def __init__(self) -> None:
        self.events: dict[str, dict[str, Any]] = {}

    def publish(self, event: dict[str, Any]) -> dict[str, Any]:
        key = event['dedupKey']
        if key not in self.events:
            self.events[key] = {**event, 'emailStatus': event.get('emailStatus', 'NOT_REQUIRED')}
        return dict(self.events[key])


class FakeVisitPort:
    def request(self, *, case_review_passed: bool, result_notified: bool, case_id: int, branch_slot_id: int, request_id: str) -> dict[str, Any]:
        if not case_review_passed or not result_notified:
            raise PortError('RESERVATION_NOT_ALLOWED')
        return {'caseId': case_id, 'branchSlotId': branch_slot_id, 'requestId': request_id, 'status': 'SELECTED'}


class FakeToolGateway:
    def __init__(self, response: dict[str, Any] | None = None) -> None:
        self.response = response or {'ruleResults': []}

    def validate(self, _: dict[str, Any]) -> dict[str, Any]:
        return dict(self.response)


class FakeVerificationPort:
    def __init__(self, accepted: bool = True) -> None:
        self.accepted = accepted

    def verify_submission(self, _: int, __: str) -> bool:
        return self.accepted
