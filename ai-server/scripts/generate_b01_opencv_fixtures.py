from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
AI_SERVER = ROOT / "ai-server"
sys.path.insert(0, str(AI_SERVER))

from app.document_analysis.b01_opencv import (  # noqa: E402
    CHECKBOX_FIRST_ROI,
    CHECKBOX_SECOND_ROI,
    DEBTOR_SIGNATURE_ROI,
    RENDER_DPI,
    render_document,
)

SOURCE = ROOT / "backend/src/main/resources/b01/mock-ocr/b01_blank_201903.pdf"
OUTPUT = AI_SERVER / "tests/fixtures/b01_opencv"


def check(image, roi, color=25, thickness=5):
    x1, y1, x2, y2 = roi
    width, height = x2 - x1, y2 - y1
    points = [
        (x1 + int(width * 0.28), y1 + int(height * 0.52)),
        (x1 + int(width * 0.43), y1 + int(height * 0.70)),
        (x1 + int(width * 0.76), y1 + int(height * 0.18)),
    ]
    cv2.polylines(image, [np.array(points)], False, color, thickness, cv2.LINE_AA)


def signature(image):
    x1, y1, x2, y2 = DEBTOR_SIGNATURE_ROI
    points = np.array([
        (x1 + 30, y1 + 34), (x1 + 70, y1 + 10), (x1 + 96, y1 + 38),
        (x1 + 130, y1 + 8), (x1 + 166, y1 + 38), (x1 + 215, y1 + 14),
        (x1 + 270, y1 + 35),
    ])
    cv2.polylines(image, [points], False, 25, 5, cv2.LINE_AA)
    cv2.line(image, (x1 + 45, y1 + 43), (x1 + 285, y1 + 43), 35, 3, cv2.LINE_AA)


def save(name, image):
    path = OUTPUT / f"{name}.png"
    if not cv2.imwrite(str(path), image):
        raise RuntimeError(f"Could not write {path}")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    blank = render_document(SOURCE, RENDER_DPI)
    cases = {}

    variants = {
        "unchecked": (None, "NONE", "TRACE_ABSENT"),
        "first_checked": ("first", "PRINCIPAL_FIRST", "TRACE_ABSENT"),
        "second_checked": ("second", "INTEREST_FIRST", "TRACE_ABSENT"),
        "both_checked": ("both", "MULTIPLE", "TRACE_ABSENT"),
        "faint_checked": ("faint", "UNCERTAIN", "TRACE_ABSENT"),
        "signature_present": (None, "NONE", "TRACE_PRESENT"),
        "signature_absent": (None, "NONE", "TRACE_ABSENT"),
    }
    for name, (mark, selection, signature_state) in variants.items():
        image = blank.copy()
        if mark in ("first", "both"):
            check(image, CHECKBOX_FIRST_ROI)
        if mark in ("second", "both"):
            check(image, CHECKBOX_SECOND_ROI)
        if mark == "faint":
            check(image, CHECKBOX_FIRST_ROI, color=168, thickness=2)
        if name == "signature_present":
            signature(image)
        save(name, image)
        cases[name] = {"selectedOrder": selection, "debtorSignature": signature_state}

    manifest = {
        "source": str(SOURCE.relative_to(ROOT)),
        "formCode": "5-202-0557",
        "revision": "2019.03",
        "renderDpi": RENDER_DPI,
        "canvas": {"width": int(blank.shape[1]), "height": int(blank.shape[0])},
        "rois": {
            "firstCheckbox": CHECKBOX_FIRST_ROI,
            "secondCheckbox": CHECKBOX_SECOND_ROI,
            "debtorSignature": DEBTOR_SIGNATURE_ROI,
        },
        "cases": cases,
        "limitations": [
            "Synthetic strokes only; not evidence of camera or real-handwriting accuracy.",
            "Signature result means trace presence only, never identity or authenticity.",
        ],
    }
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
