from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import cv2
import fitz
import numpy as np


CheckboxState = Literal["CHECKED", "UNCHECKED", "UNCERTAIN"]
SelectionState = Literal["PRINCIPAL_FIRST", "INTEREST_FIRST", "NONE", "MULTIPLE", "UNCERTAIN"]
SignatureState = Literal["TRACE_PRESENT", "TRACE_ABSENT", "UNCERTAIN"]

RENDER_DPI = 150
REFERENCE_WIDTH = 1241
REFERENCE_HEIGHT = 1754

# Coordinates are tied to the reviewed 2019.03 form (5-202-0557) rendered at 150 DPI.
# Checkbox ROIs include the surrounding handwriting area, while signature ROI excludes printed labels and '(인)'.
CHECKBOX_FIRST_ROI = (244, 510, 302, 552)
CHECKBOX_SECOND_ROI = (244, 548, 302, 590)
DEBTOR_SIGNATURE_ROI = (444, 1427, 1018, 1478)

CHECK_UNCERTAIN_PIXELS = 24
# Calibration at 150 DPI: blank=0, synthetic faint=109, reviewed filled sample=145,
# synthetic strong=263..269. The narrow real/faint margin is intentionally fail-closed.
CHECK_MARKED_PIXELS = 130
SIGNATURE_UNCERTAIN_PIXELS = 80
SIGNATURE_PRESENT_PIXELS = 260


@dataclass(frozen=True)
class AlignmentResult:
    status: Literal["ALIGNED", "ALIGNMENT_FAILED", "TEMPLATE_MISMATCH"]
    aligned: np.ndarray | None
    matches: int
    inlier_ratio: float


@dataclass(frozen=True)
class B01ImageAnalysis:
    alignment_status: str
    alignment_matches: int
    alignment_inlier_ratio: float
    first_checkbox: CheckboxState
    second_checkbox: CheckboxState
    selected_order: SelectionState
    first_score: int
    second_score: int
    debtor_signature: SignatureState
    signature_score: int


def render_document(path: str | Path, dpi: int = RENDER_DPI) -> np.ndarray:
    path = Path(path)
    if path.suffix.lower() == ".pdf":
        with fitz.open(path) as document:
            if document.page_count != 1:
                raise ValueError("B-01 image analysis accepts exactly one page.")
            pixmap = document[0].get_pixmap(dpi=dpi, colorspace=fitz.csGRAY, alpha=False)
            return np.frombuffer(pixmap.samples, dtype=np.uint8).reshape(pixmap.height, pixmap.width).copy()
    image = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if image is None:
        raise ValueError("Document image could not be decoded.")
    return image


def align_to_reference(reference: np.ndarray, candidate: np.ndarray) -> AlignmentResult:
    if reference.shape != (REFERENCE_HEIGHT, REFERENCE_WIDTH):
        raise ValueError("Reference form must be rendered at the reviewed 150 DPI dimensions.")
    orb = cv2.ORB_create(nfeatures=5000, fastThreshold=10)
    reference_keypoints, reference_descriptors = orb.detectAndCompute(reference, None)
    candidate_keypoints, candidate_descriptors = orb.detectAndCompute(candidate, None)
    if reference_descriptors is None or candidate_descriptors is None:
        return AlignmentResult("ALIGNMENT_FAILED", None, 0, 0.0)

    pairs = cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(candidate_descriptors, reference_descriptors, k=2)
    good = [first for first, second in pairs if first.distance < 0.72 * second.distance]
    if len(good) < 40:
        return AlignmentResult("TEMPLATE_MISMATCH", None, len(good), 0.0)

    source = np.float32([candidate_keypoints[item.queryIdx].pt for item in good]).reshape(-1, 1, 2)
    target = np.float32([reference_keypoints[item.trainIdx].pt for item in good]).reshape(-1, 1, 2)
    homography, mask = cv2.findHomography(source, target, cv2.RANSAC, 3.0)
    if homography is None or mask is None:
        return AlignmentResult("ALIGNMENT_FAILED", None, len(good), 0.0)
    inlier_ratio = float(mask.ravel().mean())
    if inlier_ratio < 0.55:
        return AlignmentResult("TEMPLATE_MISMATCH", None, len(good), inlier_ratio)
    aligned = cv2.warpPerspective(candidate, homography, (REFERENCE_WIDTH, REFERENCE_HEIGHT),
                                  flags=cv2.INTER_LINEAR, borderValue=255)
    return AlignmentResult("ALIGNED", aligned, len(good), inlier_ratio)


def _new_ink_score(reference: np.ndarray, candidate: np.ndarray, roi: tuple[int, int, int, int]) -> int:
    x1, y1, x2, y2 = roi
    reference_ink = cv2.threshold(reference[y1:y2, x1:x2], 190, 255, cv2.THRESH_BINARY_INV)[1]
    candidate_ink = cv2.threshold(candidate[y1:y2, x1:x2], 190, 255, cv2.THRESH_BINARY_INV)[1]
    # Printed borders and text may move by a pixel after alignment. Dilating the reference prevents them
    # from being treated as handwriting while preserving strokes added inside or across the field.
    reference_guard = cv2.dilate(reference_ink, np.ones((3, 3), np.uint8), iterations=1)
    added = cv2.bitwise_and(candidate_ink, cv2.bitwise_not(reference_guard))
    added = cv2.morphologyEx(added, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    return int(cv2.countNonZero(added))


def _checkbox(score: int) -> CheckboxState:
    if score >= CHECK_MARKED_PIXELS:
        return "CHECKED"
    if score >= CHECK_UNCERTAIN_PIXELS:
        return "UNCERTAIN"
    return "UNCHECKED"


def _selection(first: CheckboxState, second: CheckboxState) -> SelectionState:
    if "UNCERTAIN" in (first, second):
        return "UNCERTAIN"
    if first == "CHECKED" and second == "CHECKED":
        return "MULTIPLE"
    if first == "CHECKED":
        return "PRINCIPAL_FIRST"
    if second == "CHECKED":
        return "INTEREST_FIRST"
    return "NONE"


def _signature(score: int) -> SignatureState:
    if score >= SIGNATURE_PRESENT_PIXELS:
        return "TRACE_PRESENT"
    if score >= SIGNATURE_UNCERTAIN_PIXELS:
        return "UNCERTAIN"
    return "TRACE_ABSENT"


def analyze(reference: np.ndarray, candidate: np.ndarray) -> B01ImageAnalysis:
    alignment = align_to_reference(reference, candidate)
    if alignment.status != "ALIGNED" or alignment.aligned is None:
        return B01ImageAnalysis(alignment.status, alignment.matches, alignment.inlier_ratio,
                                "UNCERTAIN", "UNCERTAIN", "UNCERTAIN", 0, 0,
                                "UNCERTAIN", 0)
    first_score = _new_ink_score(reference, alignment.aligned, CHECKBOX_FIRST_ROI)
    second_score = _new_ink_score(reference, alignment.aligned, CHECKBOX_SECOND_ROI)
    signature_score = _new_ink_score(reference, alignment.aligned, DEBTOR_SIGNATURE_ROI)
    first = _checkbox(first_score)
    second = _checkbox(second_score)
    return B01ImageAnalysis("ALIGNED", alignment.matches, alignment.inlier_ratio,
                            first, second, _selection(first, second), first_score, second_score,
                            _signature(signature_score), signature_score)
