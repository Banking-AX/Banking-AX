"""C-02 customer node. Spring remains the only C-02 rule decision maker."""
from __future__ import annotations
from typing import Any, Protocol
from app.customer_agent.contracts import TaskDefinition

class C02ValidationGateway(Protocol):
    def validate_c02_intake(self, request: dict[str, Any]) -> dict[str, Any]: ...

def build_intake_payload(request: dict[str, Any]) -> dict[str, Any]:
    return {'requestId': request['requestId'], 'workItemId': request['workItemId'], 'customerId': request['customerId'],
            'decedent': request.get('decedent', {}), 'heirs': request.get('heirs', []),
            'documentStatuses': request.get('documentStatuses', {}), 'submissionConfirmed': bool(request.get('submissionConfirmed'))}

class InheritanceDepositTask:
    definition = TaskDefinition('INHERITANCE_DEPOSIT', '상속예금 사전접수', ('inheritance_deposit_prepare',))
    task_code = definition.task_code
    def __init__(self, gateway: C02ValidationGateway) -> None: self.gateway = gateway
    def run(self, request: dict[str, Any]) -> dict[str, Any]:
        for key in ('requestId', 'workItemId', 'customerId'):
            if request.get(key) in (None, ''): raise ValueError(f'{key} is required')
        payload = build_intake_payload(request)
        result = self.gateway.validate_c02_intake(payload)
        return {'taskCode': self.task_code, 'intake': payload, 'validation': result, 'submitted': False}


class SpringC02ValidationGateway:
    """Adapter preserving the older C02ValidationGateway boundary for the registered task."""
    def __init__(self, gateway: Any) -> None: self._gateway = gateway
    def validate_c02_intake(self, request: dict[str, Any]) -> dict[str, Any]:
        return self._gateway.inheritance_deposit_prepare(request)
