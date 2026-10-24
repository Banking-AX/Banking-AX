from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from .registry import TaskRegistry
from .tasks.loan_inquiry import LoanInquiryTask
from .tasks.debt_adjustment import DebtAdjustmentTask
from .tool_gateway import SpringHttpToolGateway


class CustomerTaskRequest(BaseModel):
    taskCode: str
    requestId: str = Field(min_length=1, max_length=90)
    workItemId: int | None = None
    customerId: int
    loanId: int | None = None
    workflowId: int | None = None
    expectedWorkflowVersion: int = 0
    registeredPhone: str | None = None


def build_registry(gateway: SpringHttpToolGateway | None = None) -> TaskRegistry:
    selected = gateway or SpringHttpToolGateway()
    return TaskRegistry([LoanInquiryTask(selected), DebtAdjustmentTask(selected)])


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
