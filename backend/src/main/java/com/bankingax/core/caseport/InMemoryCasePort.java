package com.bankingax.core.caseport;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.workflow.TaskCode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Fake adapter for contract tests; it is intentionally not an employee-decision engine. */
@Service
@ConditionalOnProperty(name = "app.case-port", havingValue = "inmemory", matchIfMissing = true)
public class InMemoryCasePort implements CasePort {
    private final AtomicLong ids = new AtomicLong(2000);
    private final Map<Long, MutableCase> cases = new HashMap<>();
    @Override public synchronized ServiceCaseView create(CreateCaseCommand command) {
        if (command.taskCode() != TaskCode.DEBT_ADJUSTMENT && command.taskCode() != TaskCode.INHERITANCE_DEPOSIT)
            throw new DomainException("INVALID_TASK", "Only C tasks create a service case.");
        long id=ids.incrementAndGet(); MutableCase c=new MutableCase(id, command.actorId(), command.workItemId(), command.taskCode(), command.decedentId(), command.visitRequired(), command.application());
        cases.put(id,c); return c.view();
    }
    @Override public synchronized ServiceCaseView submit(long actorId, long caseId, int revision, String requestId) {
        MutableCase c=owned(actorId,caseId); if(c.revision!=revision) throw stale();
        if(c.status!=CaseStatus.DRAFT) throw new DomainException("INVALID_STATE","Only a draft case can be submitted.");
        c.status=CaseStatus.SUBMITTED; c.review=ReviewStatus.PENDING; c.version++; return c.view();
    }
    @Override public synchronized ServiceCaseView revise(long actorId,long caseId,int revision,Map<String,Object> application,String reason) {
        MutableCase c=owned(actorId,caseId); if(c.revision!=revision) throw stale();
        if(c.status!=CaseStatus.DRAFT && c.status!=CaseStatus.SUBMITTED) throw new DomainException("INVALID_STATE","Case cannot be revised.");
        c.revision++; c.version++; c.application=Map.copyOf(application); c.review=c.status==CaseStatus.SUBMITTED?ReviewStatus.PENDING:null; return c.view();
    }
    @Override public synchronized ServiceCaseView cancel(long actorId,long caseId,int version) {
        MutableCase c=owned(actorId,caseId); if(c.version!=version) throw stale();
        if(c.status!=CaseStatus.DRAFT && c.status!=CaseStatus.SUBMITTED) throw new DomainException("CANCEL_NOT_ALLOWED","Case review has already started.");
        c.status=CaseStatus.CANCELED; c.version++; return c.view();
    }
    @Override public synchronized ServiceCaseView recordCheckResult(long caseId,int revision,ReviewStatus review) {
        MutableCase c=find(caseId); if(c.revision!=revision || c.status==CaseStatus.CANCELED) throw stale();
        c.review=review; c.version++; return c.view();
    }
    @Override public synchronized ServiceCaseView get(long actorId,long caseId) { return owned(actorId,caseId).view(); }
    private MutableCase owned(long actorId,long id) { MutableCase c=find(id); if(c.customerId!=actorId) throw new DomainException("FORBIDDEN","Case ownership does not match."); return c; }
    private MutableCase find(long id) { MutableCase c=cases.get(id); if(c==null) throw new DomainException("NOT_FOUND","Service case was not found."); return c; }
    private DomainException stale() { return new DomainException("VERSION_CONFLICT","Case revision or version is stale.",true); }
    private static final class MutableCase { long id,customerId,workItemId; TaskCode taskCode; CaseStatus status=CaseStatus.DRAFT; ReviewStatus review; int revision=0,version=0; boolean visitRequired; Long decedentId; Map<String,Object> application;
        MutableCase(long id,long customerId,long workItemId,TaskCode taskCode,Long decedentId,boolean visitRequired,Map<String,Object> application){this.id=id;this.customerId=customerId;this.workItemId=workItemId;this.taskCode=taskCode;this.decedentId=decedentId;this.visitRequired=visitRequired;this.application=Map.copyOf(application);}
        ServiceCaseView view(){return new ServiceCaseView(id,workItemId,taskCode,status,review,revision,version,visitRequired,decedentId,application);}
    }
}
