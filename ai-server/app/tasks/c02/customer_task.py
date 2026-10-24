"""C-02 customer node; registration remains T1 TaskRegistry's responsibility."""
from __future__ import annotations
from typing import Any, Protocol

class C02ValidationGateway(Protocol):
    def validate_c02_intake(self, request: dict[str, Any]) -> dict[str, Any]: ...

def build_intake_payload(request: dict[str, Any]) -> dict[str, Any]:
    return {'requestId': request['requestId'], 'workItemId': request['workItemId'], 'customerId': request['customerId'],
            'decedent': request.get('decedent', {}), 'heirs': request.get('heirs', []), 'submissionConfirmed': bool(request.get('submissionConfirmed'))}

class InheritanceDepositTask:
    task_code = 'INHERITANCE_DEPOSIT'
    def __init__(self, gateway: C02ValidationGateway) -> None: self.gateway = gateway
    def run(self, request: dict[str, Any]) -> dict[str, Any]:
        for key in ('requestId', 'workItemId', 'customerId'):
            if request.get(key) in (None, ''): raise ValueError(f'{key} is required')
        payload = build_intake_payload(request)
        result = self.gateway.validate_c02_intake(payload)
        return {'taskCode': self.task_code, 'intake': payload, 'validation': result, 'submitted': False}
