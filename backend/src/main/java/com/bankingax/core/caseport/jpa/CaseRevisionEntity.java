package com.bankingax.core.caseport.jpa;

import jakarta.persistence.*;

@Entity
@Table(name="case_revision", uniqueConstraints=@UniqueConstraint(name="uk_case_revision", columnNames={"case_id","revision"}))
class CaseRevisionEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="case_id", nullable=false) long caseId;
    @Column(nullable=false) int revision;
    @Column(name="application_snapshot", columnDefinition="json", nullable=false) String applicationSnapshot;
    @Column(name="change_reason", nullable=false) String changeReason;
    @Column(name="created_by_user_id") Long createdByUserId;
    protected CaseRevisionEntity() { }
    CaseRevisionEntity(long caseId,int revision,String applicationSnapshot,String changeReason,Long createdByUserId) {
        this.caseId=caseId; this.revision=revision; this.applicationSnapshot=applicationSnapshot; this.changeReason=changeReason; this.createdByUserId=createdByUserId;
    }
}
