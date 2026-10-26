package com.bankingax.core.caseport.jpa;

import com.bankingax.core.caseport.CaseStatus;
import com.bankingax.core.caseport.ReviewStatus;
import com.bankingax.core.workflow.TaskCode;
import jakarta.persistence.*;

@Entity
@Table(name = "service_case")
class ServiceCaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name="work_item_id", nullable=false, unique=true) long workItemId;
    @Column(name="customer_id", nullable=false) long customerId;
    @Enumerated(EnumType.STRING) @Column(name="case_type", nullable=false) TaskCode taskCode;
    @Enumerated(EnumType.STRING) @Column(name="case_status", nullable=false) CaseStatus caseStatus = CaseStatus.DRAFT;
    @Enumerated(EnumType.STRING) @Column(name="review_status") ReviewStatus reviewStatus;
    @Column(nullable=false) int revision;
    @Column(name="loan_id") Long loanId;
    @Column(name="decedent_id") Long decedentId;
    @Column(name="visit_required") Boolean visitRequired;
    @Column(columnDefinition="json", nullable=false) String application;
    @Column(name="submit_request_id", unique=true) String submitRequestId;
    @Version @Column(nullable=false) int version;
}
