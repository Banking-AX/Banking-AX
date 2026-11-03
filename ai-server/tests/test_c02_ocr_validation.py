import unittest

from app.core.staff_engine.models import CheckRequest
from app.tasks.c02.checks import c02_staff_precheck


class C02OcrValidationTest(unittest.TestCase):
    def request(self, extractions: list[dict]) -> CheckRequest:
        return CheckRequest(
            request_id='ocr-validation-test', case_id=7, revision=3,
            task_code='INHERITANCE_DEPOSIT',
            case_snapshot={
                'decedent': {'deathDate': '2020-01-01'},
                'heirs': [{'personRef': 'h1', 'visitStatus': 'ATTENDING'}],
                'requiredDocuments': [], 'missingDocuments': [],
                'ocrExtractions': extractions,
            },
        )

    def test_mismatch_and_unverifiable_flags_are_staff_items_without_personal_values(self):
        items = c02_staff_precheck(self.request([{
            'documentFileId': 91, 'caseRevision': 3, 'method': 'MOCK',
            'fieldValidations': [
                {'fieldName': 'grantor_names', 'status': 'MISMATCH_CANDIDATE', 'reasonCode': 'TEXT_MISMATCH_CANDIDATE', 'confidence': 0.99},
                {'fieldName': 'agent_name', 'status': 'UNVERIFIABLE', 'reasonCode': 'NO_EXPLICIT_AGENT_REFERENCE', 'confidence': 0.99},
            ],
        }]))
        ocr_items = [item for item in items if item.category == 'OCR_FIELD_REVIEW']
        self.assertEqual(2, len(ocr_items))
        self.assertTrue(all(item.source == 'SPRING_RULE' and item.result == 'NEEDS_STAFF_REVIEW' for item in ocr_items))
        serialized = str([item.as_dict() for item in ocr_items])
        self.assertNotIn('Synthetic Absent Heir', serialized)
        self.assertNotIn('Synthetic Agent Fixture', serialized)
        self.assertIn('NO_EXPLICIT_AGENT_REFERENCE', serialized)

    def test_match_candidate_does_not_create_a_finding_and_non_actionable_status_is_ignored(self):
        items = c02_staff_precheck(self.request([{
            'documentFileId': 92, 'caseRevision': 3,
            'fieldValidations': [
                {'fieldName': 'grantor_names', 'status': 'MATCH_CANDIDATE', 'reasonCode': 'TEXT_MATCH_CANDIDATE', 'confidence': 0.99},
                {'fieldName': 'grantor_names', 'status': 'UNVERIFIABLE', 'reasonCode': 'STALE_DOCUMENT_REVISION'},
            ],
        }]))
        self.assertEqual(1, len([item for item in items if item.category == 'OCR_FIELD_REVIEW']))


if __name__ == '__main__':
    unittest.main()
