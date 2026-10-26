package com.bankingax.core.caseport.jpa;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.caseport.*;
import com.bankingax.core.workflow.TaskCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL-backed CasePort. It intentionally does not dispatch Agents or consume verification bindings. */
@Service
@ConditionalOnProperty(name="app.case-port", havingValue="jpa")
public class JpaCasePort implements CasePort {
    private final ServiceCaseRepository cases; private final CaseRevisionRepository revisions; private final ObjectMapper json;
    public JpaCasePort(ServiceCaseRepository cases,CaseRevisionRepository revisions,ObjectMapper json){this.cases=cases;this.revisions=revisions;this.json=json;}
    @Override @Transactional public ServiceCaseView create(CreateCaseCommand command) {
        validateTask(command.taskCode());
        var existing=cases.findByWorkItemId(command.workItemId());
        if(existing.isPresent()) return sameCreate(existing.get(),command);
        ServiceCaseEntity entity=new ServiceCaseEntity(); entity.workItemId=command.workItemId(); entity.customerId=command.actorId(); entity.taskCode=command.taskCode(); entity.loanId=command.loanId(); entity.decedentId=command.decedentId(); entity.visitRequired=command.visitRequired(); entity.application=write(command.application());
        try { entity=cases.saveAndFlush(entity); }
        catch(DataIntegrityViolationException race) { return sameCreate(cases.findByWorkItemId(command.workItemId()).orElseThrow(()->race),command); }
        revisions.save(new CaseRevisionEntity(entity.id,0,entity.application,"CREATED",command.actorId())); return view(entity);
    }
    @Override @Transactional public ServiceCaseView submit(long actorId,long caseId,int expectedRevision,String requestId) {
        ServiceCaseEntity c=ownedForUpdate(actorId,caseId);
        if(c.submitRequestId!=null && Objects.equals(c.submitRequestId,requestId)) return view(c);
        revision(c,expectedRevision);
        if(c.caseStatus!=CaseStatus.DRAFT) throw new DomainException("INVALID_STATE","Only a draft case can be submitted.");
        c.submitRequestId=requestId; c.caseStatus=CaseStatus.SUBMITTED; c.reviewStatus=ReviewStatus.PENDING; return flushedView(c);
    }
    @Override @Transactional public ServiceCaseView revise(long actorId,long caseId,int expectedRevision,Map<String,Object> application,String reason) {
        ServiceCaseEntity c=ownedForUpdate(actorId,caseId); revision(c,expectedRevision); if(c.caseStatus!=CaseStatus.DRAFT&&c.caseStatus!=CaseStatus.SUBMITTED) throw new DomainException("INVALID_STATE","Case cannot be revised.");
        c.revision++; c.application=write(application); c.reviewStatus=c.caseStatus==CaseStatus.SUBMITTED?ReviewStatus.PENDING:null; revisions.save(new CaseRevisionEntity(c.id,c.revision,c.application,reason,actorId)); return flushedView(c);
    }
    @Override @Transactional public ServiceCaseView cancel(long actorId,long caseId,int expectedVersion) {
        ServiceCaseEntity c=ownedForUpdate(actorId,caseId); if(c.version!=expectedVersion) throw stale(); if(c.caseStatus!=CaseStatus.DRAFT&&c.caseStatus!=CaseStatus.SUBMITTED) throw new DomainException("CANCEL_NOT_ALLOWED","Case review has already started."); c.caseStatus=CaseStatus.CANCELED; return flushedView(c);
    }
    @Override @Transactional public ServiceCaseView recordCheckResult(long caseId,int revision,ReviewStatus status) {
        ServiceCaseEntity c=cases.findForUpdate(caseId).orElseThrow(()->new DomainException("NOT_FOUND","Service case was not found.")); revision(c,revision); if(c.caseStatus==CaseStatus.CANCELED) throw stale(); c.reviewStatus=status; return flushedView(c);
    }
    @Override @Transactional(readOnly=true) public ServiceCaseView get(long actorId,long caseId) { return view(owned(actorId,caseId)); }
    private ServiceCaseView sameCreate(ServiceCaseEntity c,CreateCaseCommand command) {
        if(c.customerId!=command.actorId()||c.taskCode!=command.taskCode()||!Objects.equals(c.loanId,command.loanId())||!Objects.equals(c.decedentId,command.decedentId())||!Objects.equals(c.visitRequired,command.visitRequired())||!sameApplication(c.application,command.application()))
            throw new DomainException("DUPLICATE_REQUEST_CONFLICT","workItem already belongs to a Case created with a different request.");
        return view(c);
    }
    private ServiceCaseEntity owned(long actor,long id){ServiceCaseEntity c=cases.findById(id).orElseThrow(()->new DomainException("NOT_FOUND","Service case was not found."));if(c.customerId!=actor)throw new DomainException("FORBIDDEN","Case ownership does not match.");return c;}
    private ServiceCaseEntity ownedForUpdate(long actor,long id){ServiceCaseEntity c=cases.findForUpdate(id).orElseThrow(()->new DomainException("NOT_FOUND","Service case was not found."));if(c.customerId!=actor)throw new DomainException("FORBIDDEN","Case ownership does not match.");return c;}
    private static void validateTask(TaskCode code){if(code!=TaskCode.DEBT_ADJUSTMENT&&code!=TaskCode.INHERITANCE_DEPOSIT)throw new DomainException("INVALID_TASK","Only C tasks create a service case.");}
    private static void revision(ServiceCaseEntity c,int expected){if(c.revision!=expected)throw stale();}
    private static DomainException stale(){return new DomainException("VERSION_CONFLICT","Case revision or version is stale.",true);}
    private String write(Map<String,Object> map){try{return json.writeValueAsString(map);}catch(Exception e){throw new DomainException("APPLICATION_INVALID","Application snapshot cannot be serialized.");}}
    private boolean sameApplication(String persisted,Map<String,Object> supplied){try{return json.readTree(persisted).equals(json.valueToTree(supplied));}catch(Exception e){throw new IllegalStateException("Persisted application JSON is invalid.",e);}}
    private Map<String,Object> read(String value){try{return json.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Persisted application JSON is invalid.",e);}}
    private ServiceCaseView flushedView(ServiceCaseEntity c){cases.flush(); return view(c);}
    private ServiceCaseView view(ServiceCaseEntity c){return new ServiceCaseView(c.id,c.workItemId,c.taskCode,c.caseStatus,c.reviewStatus,c.revision,c.version,Boolean.TRUE.equals(c.visitRequired),c.decedentId,read(c.application));}
}
