package com.bankingax.task.c01;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** C1-R1..R5 Java classification. This never approves or rejects a debt adjustment. */
@Service
public class DebtAdjustmentRuleValidator {
    public C01RuleEvaluation evaluate(C01LoanProfile loan, LocalDate today) {
        List<C01RuleItem> items = new ArrayList<>();
        boolean eligible = true;
        if (!loan.overdue()) { eligible = false; items.add(C01RuleItem.blocked("C1-R1", "NOT_OVERDUE", "연체 상태가 아니라 채무조정 접수 대상이 아닙니다.")); }
        // The DDL's principal_amount is used as the mock source. Whether the policy means original or outstanding
        // principal remains a documented decision pending; this tool does not infer another basis.
        if (loan.principalAmount() >= 30_000_000L) { eligible = false; items.add(C01RuleItem.blocked("C1-R2", "PRINCIPAL_OVER_LIMIT", "원금 금액이 시연 기준을 초과합니다.")); }
        if (loan.debtAdjustmentTerminatedAt() != null && loan.debtAdjustmentTerminatedAt().isAfter(today.minusMonths(3))) {
            eligible = false; items.add(C01RuleItem.blocked("C1-R3", "RECENT_TERMINATION", "최근 3개월 내 채무조정 종료 이력이 있습니다."));
        }
        if (loan.inLitigation()) { eligible = false; items.add(C01RuleItem.blocked("C1-R4", "IN_LITIGATION", "소송 진행 여부는 접수 전 제한됩니다.")); }
        if (loan.otherProcedure()) items.add(C01RuleItem.staff("C1-R5", "OTHER_PROCEDURE", "다른 채무조정 절차 진행 여부를 직원이 확인해야 합니다."));
        if (loan.repeatedSupplement()) items.add(C01RuleItem.staff("C1-W1", "REPEATED_SUPPLEMENT", "반복 보완 이력은 직원이 확인해야 합니다."));
        if (!loan.materialAbilityChangeConfirmed()) items.add(C01RuleItem.staff("C1-W2", "ABILITY_CHANGE", "변제능력의 실질적 변경 여부를 직원이 확인해야 합니다."));
        if (loan.prescriptionConcern()) items.add(C01RuleItem.staff("C1-W3", "PRESCRIPTION", "소멸시효 완성 가능성은 직원이 확인해야 합니다."));
        return new C01RuleEvaluation(eligible, List.copyOf(items));
    }
}
