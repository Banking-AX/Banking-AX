import unittest

from app.main import StaffRankingRequest, staff_ranking


class StaffRankingTests(unittest.TestCase):
    def test_lowest_workload_first_then_id(self) -> None:
        result = staff_ranking(StaffRankingRequest(candidateIds=[103, 101, 102], taskCode='INHERITANCE_DEPOSIT', candidates=[
            {'staffId': 101, 'activeAssignments': 3}, {'staffId': 102, 'activeAssignments': 0}, {'staffId': 103, 'activeAssignments': 0}]))
        self.assertEqual(result['staffIds'], [102, 103, 101])
        self.assertEqual(result['basis'], 'TASK_CODE_AND_WORKLOAD')

    def test_never_returns_a_staff_member_spring_did_not_offer(self) -> None:
        result = staff_ranking(StaffRankingRequest(candidateIds=[101], taskCode='INHERITANCE_DEPOSIT', candidates=[
            {'staffId': 999, 'activeAssignments': 0}, {'staffId': 101, 'activeAssignments': 5}]))
        self.assertEqual(result['staffIds'], [101])

    def test_missing_workload_keeps_the_previous_id_order(self) -> None:
        self.assertEqual(staff_ranking(StaffRankingRequest(candidateIds=[5, 2], taskCode='X'))['staffIds'], [2, 5])


if __name__ == '__main__':
    unittest.main()
