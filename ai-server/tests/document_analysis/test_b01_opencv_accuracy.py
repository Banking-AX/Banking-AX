from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[3]
AI_SERVER = ROOT / "ai-server"
sys.path.insert(0, str(AI_SERVER))

from app.document_analysis.b01_opencv import analyze, render_document  # noqa: E402

FIXTURES = AI_SERVER / "tests/fixtures/b01_opencv"
BLANK = ROOT / "backend/src/main/resources/b01/mock-ocr/b01_blank_201903.pdf"
FILLED = ROOT / "backend/src/main/resources/b01/mock-ocr/b01-t01-application.pdf"


@pytest.fixture(scope="module")
def reference():
    return render_document(BLANK)


def fixture(name: str):
    image = cv2.imread(str(FIXTURES / f"{name}.png"), cv2.IMREAD_GRAYSCALE)
    assert image is not None
    return image


def test_blank_and_filled_forms_have_alignable_structure(reference):
    result = analyze(reference, render_document(FILLED))
    assert result.alignment_status == "ALIGNED"
    assert result.alignment_matches >= 40
    assert result.alignment_inlier_ratio >= 0.55
    assert result.selected_order == "PRINCIPAL_FIRST"
    assert result.debtor_signature == "TRACE_PRESENT"


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("first_checked", "PRINCIPAL_FIRST"),
        ("second_checked", "INTEREST_FIRST"),
        ("unchecked", "NONE"),
        ("both_checked", "MULTIPLE"),
        ("faint_checked", "UNCERTAIN"),
    ],
)
def test_checkbox_labels(reference, name, expected):
    assert analyze(reference, fixture(name)).selected_order == expected


@pytest.mark.parametrize(
    ("name", "expected"),
    [("signature_present", "TRACE_PRESENT"), ("signature_absent", "TRACE_ABSENT")],
)
def test_signature_trace_labels(reference, name, expected):
    assert analyze(reference, fixture(name)).debtor_signature == expected


@pytest.mark.parametrize("angle", [-2.5, 2.5])
def test_rotation_is_aligned_before_classification(reference, angle):
    image = fixture("first_checked")
    matrix = cv2.getRotationMatrix2D((image.shape[1] / 2, image.shape[0] / 2), angle, 1.0)
    rotated = cv2.warpAffine(image, matrix, (image.shape[1], image.shape[0]), borderValue=255)
    result = analyze(reference, rotated)
    assert result.alignment_status == "ALIGNED"
    assert result.selected_order == "PRINCIPAL_FIRST"


@pytest.mark.parametrize("scale", [0.75, 1.25])
def test_resolution_changes_are_aligned_before_classification(reference, scale):
    image = fixture("second_checked")
    resized = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    result = analyze(reference, resized)
    assert result.alignment_status == "ALIGNED"
    assert result.selected_order == "INTEREST_FIRST"


def test_fixture_manifest_matches_rendering_and_labels(reference):
    manifest = json.loads((FIXTURES / "manifest.json").read_text())
    assert manifest["formCode"] == "5-202-0557"
    assert manifest["renderDpi"] == 150
    assert manifest["canvas"] == {"width": reference.shape[1], "height": reference.shape[0]}
    for name, labels in manifest["cases"].items():
        result = analyze(reference, fixture(name))
        assert result.selected_order == labels["selectedOrder"]
        assert result.debtor_signature == labels["debtorSignature"]
