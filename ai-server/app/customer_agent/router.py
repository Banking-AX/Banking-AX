from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from .registry import TaskRegistry
from .tasks.loan_inquiry import LoanInquiryTask
from .tasks.transaction_search import TransactionSearchTask
from .tasks.debt_adjustment import DebtAdjustmentTask
from app.tasks.c02 import InheritanceDepositTask, SpringC02ValidationGateway
from .tool_gateway import SpringHttpToolGateway


class CustomerTaskRequest(BaseModel):
    taskCode: str
    requestId: str = Field(min_length=1, max_length=90)
    workItemId: int | None = None
    customerId: int
    loanId: int | None = None
    accountId: int | None = None
    startDate: str | None = None
    endDate: str | None = None
    transactionType: str | None = None
    page: int | None = None
    size: int | None = None
    workflowId: int | None = None
    expectedWorkflowVersion: int = 0
    registeredPhone: str | None = None
    decedent: dict[str, object] | None = None
    heirs: list[dict[str, object]] = Field(default_factory=list)
    documentStatuses: dict[str, str] = Field(default_factory=dict)
    submissionConfirmed: bool = False
    additionalInput: dict[str, object] = Field(default_factory=dict)


def build_registry(gateway: SpringHttpToolGateway | None = None) -> TaskRegistry:
    selected = gateway or SpringHttpToolGateway()
    return TaskRegistry([TransactionSearchTask(selected), LoanInquiryTask(selected), DebtAdjustmentTask(selected), InheritanceDepositTask(SpringC02ValidationGateway(selected))])


def create_router(registry: TaskRegistry | None = None) -> APIRouter:
    router = APIRouter(tags=["customer-agent"])
    selected_registry = registry or build_registry()

    @router.get("/customer-agent/tasks")
    def registered_tasks() -> list[dict[str, object]]:
        return selected_registry.definitions()

    @router.post("/customer-agent/execute")
    def execute(request: CustomerTaskRequest) -> dict:
        try:
            return selected_registry.get(request.taskCode).run(request.model_dump())
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        except RuntimeError as exc:
            raise HTTPException(503, str(exc)) from exc

    return router
