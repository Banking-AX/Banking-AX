package com.bankingax.task.b01;

import com.bankingax.core.api.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class B01LoanListService {
    private final B01ValidationQueryPort queries;

    B01LoanListService(ObjectProvider<B01ValidationQueryPort> queries) {
        this.queries = queries.getIfAvailable();
    }

    @Transactional(readOnly = true)
    public List<LoanListItem> findOwnedLoans(long customerId, long workItemId) {
        return findOwnedLoans(customerId, workItemId, false);
    }

    @Transactional(readOnly = true)
    public List<LoanListItem> findOwnedLoansForTool(long customerId, long workItemId) {
        return findOwnedLoans(customerId, workItemId, true);
    }

    private List<LoanListItem> findOwnedLoans(long customerId, long workItemId, boolean distinguishOnHold) {
        requirePort();
        String status = queries.ownedWorkItemStatus(customerId, workItemId)
                .orElseThrow(B01LoanListService::unavailable);
        if (distinguishOnHold && "ON_HOLD".equals(status)) {
            throw new DomainException(
                    "WORK_ITEM_RESUME_REQUIRED", "The B-01 work item must be resumed before loan lookup.");
        }
        if (!"ACTIVE".equals(status)) {
            throw new DomainException("WORK_ITEM_NOT_AVAILABLE", "The B-01 work item is not available.");
        }
        return queries.ownedLoans(customerId).stream()
                .map(loan -> new LoanListItem(loan.id(), mask(loan.loanNo()), loan.loanName(), loan.balance(),
                        loan.overdueDays(), loan.accelerationDeclared(), loan.accelerationDate(),
                        loan.repaymentOrder()))
                .toList();
    }

    private void requirePort() {
        if (queries == null) {
            throw new DomainException("B01_PERSISTENCE_UNAVAILABLE",
                    "B-01 loan persistence is not configured.", true);
        }
    }

    private static DomainException unavailable() {
        return new DomainException("WORK_ITEM_NOT_AVAILABLE", "The B-01 work item is not available.");
    }

    static String mask(String loanNo) {
        if (loanNo == null || loanNo.isBlank()) return "***";
        String[] groups = loanNo.split("-", -1);
        if (groups.length >= 3 && !groups[0].isBlank() && !groups[groups.length - 1].isBlank()) {
            return groups[0] + "-***-" + groups[groups.length - 1];
        }
        if (loanNo.length() <= 4) return "***";
        int prefixLength = Math.min(3, loanNo.length() - 4);
        return loanNo.substring(0, prefixLength) + "-***-" + loanNo.substring(loanNo.length() - 4);
    }

    public record LoanListItem(long loanId, String maskedAccountNumber, String loanName, BigDecimal balance,
                               int overdueDays, boolean accelerationDeclared, LocalDate accelerationDate,
                               String currentRepaymentOrder) { }
}
