"""Repeatable, offline C-02 pre-check and staff-agent benchmark.

This intentionally calls no HTTP endpoint, database, OCR vendor, or LLM provider.
It measures the current C-02 deterministic check and the staff brief graph using
synthetic metadata only.  It is safe to run beside the shared demo environment.

Run from repository root:
  ai-server/.venv/Scripts/python.exe scripts/benchmark/c02_precheck_benchmark.py
"""
from __future__ import annotations

import json
import statistics
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ai-server"))

from app.core.staff_engine.models import CheckRequest  # noqa: E402
from app.staff_agent.case_brief import DECISION_TERMS, write_brief  # noqa: E402
from app.tasks.c02.checks import c02_staff_precheck  # noqa: E402

OUT = ROOT / "docs" / "benchmark" / "results"


def percentile(values: list[float], p: float) -> float:
    """Nearest-rank percentile, reported in milliseconds."""
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, int((len(ordered) - 1) * p)))]


def base_snapshot() -> dict:
    return {
        "decedent": {"deathDate": "2024-01-02"},
        "heirs": [{"personRef": "H-1", "visitStatus": "ATTENDING", "delegationMethod": "NONE"}],
        "requiredDocuments": [{"slot": "FAMILY_CERT", "requirement": "REQUIRED", "status": "UPLOADED", "documentFileId": 100, "documentVersion": 1}],
    }


def fixtures() -> list[dict]:
    rows: list[dict] = []
    # Six instances of every specified scenario means 30 cases without personal data.
    for number in range(1, 7):
        rows.append({"id": f"normal-{number}", "kind": "NORMAL", "snapshot": base_snapshot(), "expected": set()})
        missing = base_snapshot(); missing["requiredDocuments"][0]["status"] = "NOT_SUBMITTED"
        rows.append({"id": f"missing-{number}", "kind": "DOCUMENT_MISSING", "snapshot": missing, "expected": {"DOCUMENT_MISSING"}})
        mismatch = base_snapshot(); mismatch["requiredDocuments"][0]["status"] = "VALIDATION_FAILED"
        rows.append({"id": f"ocr-mismatch-{number}", "kind": "OCR_MISMATCH", "snapshot": mismatch, "expected": {"DOCUMENT_NEEDS_SUPPLEMENT"}})
        unreadable = base_snapshot(); unreadable["requiredDocuments"][0]["status"] = "NEEDS_SUPPLEMENT"
        rows.append({"id": f"unreadable-{number}", "kind": "UNREADABLE", "snapshot": unreadable, "expected": {"DOCUMENT_NEEDS_SUPPLEMENT"}})
        staff = base_snapshot(); staff["heirs"][0]["visitStatus"] = "UNCONFIRMED_ABSENT"
        rows.append({"id": f"staff-only-{number}", "kind": "STAFF_CONFIRMATION", "snapshot": staff, "expected": {"VISIT_ATTENDANCE"}})
    return rows


def brief_facts() -> dict:
    return {"people": {"heirCount": 2, "absentCount": 1, "delegation": {"DOCUMENT": True}},
            "documents": [{"name": "위임장", "submitted": True}],
            "verification": {"matched": ["위임 목적"], "mismatched": [], "staffRequired": ["원본·진위"], "ocrStatus": "SUCCEEDED"},
            "staffMustConfirm": ["상속권·원본·진위"], "staffDocumentChecks": [{"document": "위임장", "staffCheck": "NOT_REVIEWED"}]}


class GoodFakeLlm:
    calls = 0
    def write(self, _: str) -> str:
        self.calls += 1
        return json.dumps({"summary": "상속예금 사전접수의 제출 현황을 정리합니다.", "confirmed": ["위임 목적의 요청 정보 일치"],
                           "staffChecks": ["원본 확인 필요"], "basis": [], "customerDraft": "직원 확인 결과에 따라 필요한 사항을 안내합니다."}, ensure_ascii=False)


class UnsafeFakeLlm:
    def write(self, _: str) -> str:
        return json.dumps({"summary": "모든 확인이 완료되어 지급 가능합니다.", "confirmed": ["상속권 확인 완료"],
                           "staffChecks": [], "basis": [], "customerDraft": "승인되었습니다."}, ensure_ascii=False)


class FailingFakeLlm:
    def write(self, _: str) -> str:
        raise TimeoutError("injected provider timeout")


def has_ungrounded_decision(result: dict) -> bool:
    text = " ".join(result.get(key, "") if isinstance(result.get(key), str) else " ".join(result.get(key, [])) for key in ("summary", "confirmed", "staffChecks", "customerDraft"))
    return any(term in text for term in DECISION_TERMS)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    cases = fixtures(); per_case = []; expected_total = actual_total = fp = fn = 0
    auto_items = staff_items = 0; precheck_times = []
    for index, row in enumerate(cases, 1):
        request = CheckRequest(request_id=f"bench-{index}", case_id=10000 + index, revision=1, task_code="INHERITANCE_DEPOSIT", case_snapshot=row["snapshot"])
        started = time.perf_counter(); items = c02_staff_precheck(request); elapsed = (time.perf_counter() - started) * 1000
        precheck_times.append(elapsed)
        detected = {item.category for item in items if item.category != "DOCUMENT_POLICY"}
        expected = row["expected"]
        expected_total += len(expected); actual_total += len(detected)
        fp += len(detected - expected); fn += len(expected - detected)
        auto_items += sum(item.source == "SPRING_RULE" for item in items)
        staff_items += sum(item.result == "NEEDS_STAFF_REVIEW" for item in items)
        per_case.append({"id": row["id"], "scenario": row["kind"], "expectedCategories": sorted(expected), "actualCategories": sorted(detected), "elapsedMs": round(elapsed, 4)})

    llm = GoodFakeLlm(); llm_times = []
    for _ in range(30):
        started = time.perf_counter(); result = write_brief(brief_facts(), search=lambda *_: [], llm=llm); llm_times.append((time.perf_counter() - started) * 1000)
        assert result["method"] == "LLM"
    unsafe_result = write_brief(brief_facts(), search=lambda *_: [], llm=UnsafeFakeLlm())
    failure_result = write_brief(brief_facts(), search=lambda *_: [], llm=FailingFakeLlm())
    output = {
        "scope": "offline synthetic benchmark; no DB, HTTP, OCR, or provider calls",
        "precheck": {"caseCount": len(cases), "scenarioCounts": {kind: 6 for kind in ("NORMAL", "DOCUMENT_MISSING", "OCR_MISMATCH", "UNREADABLE", "STAFF_CONFIRMATION")},
                     "expectedSignalCount": expected_total, "actualSignalCount": actual_total, "falsePositiveSignals": fp, "falseNegativeSignals": fn,
                     "deterministicRuleItems": auto_items, "staffReviewItems": staff_items,
                     "latencyMs": {"p50": round(percentile(precheck_times, .50), 3), "p95": round(percentile(precheck_times, .95), 3)}, "cases": per_case},
        "staffAgent": {"mode": "Fake LLM only", "calls": llm.calls, "latencyMs": {"p50": round(percentile(llm_times, .50), 3), "p95": round(percentile(llm_times, .95), 3)},
                       "structuredOutputRate": "30/30", "unsafeFakeDecisionTermsInFinalOutput": has_ungrounded_decision(unsafe_result),
                       "unsafeFakeMethod": unsafe_result["method"], "failureInjectedMethod": failure_result["method"], "failureInjectedModel": failure_result["model"]},
    }
    target = OUT / "c02_offline_benchmark.json"; target.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(target)
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
