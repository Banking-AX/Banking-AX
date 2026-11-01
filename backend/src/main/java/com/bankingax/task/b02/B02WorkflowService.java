package com.bankingax.task.b02;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.verification.VerificationIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** B-02-only bridge: financial close, task result and workflow completion share one DB transaction. */
@Service
@ConditionalOnProperty(name="app.case-port",havingValue="jpa")
public class B02WorkflowService {
 private final NamedParameterJdbcTemplate jdbc; private final B02Service b02; private final ObjectMapper json;
 public B02WorkflowService(NamedParameterJdbcTemplate jdbc,B02Service b02,ObjectMapper json){this.jdbc=jdbc;this.b02=b02;this.json=json;}
 @Transactional public VerificationIssue issue(long customer,long workItem,long account,int version,boolean consent,boolean signed,String phone){ active(customer,workItem); if(!consent||!signed) throw new DomainException("CONSENT_OR_SIGNATURE_REQUIRED","Consent and mock signature are required."); jdbc.update("insert into consent_record(work_item_id,consent_type,notice_version,agreed) values(:w,'B02_CLOSE','1.7.1',true)",Map.of("w",workItem)); return b02.issue(customer,workItem,account,version,true,true,phone); }
 @Transactional public B02Service.R execute(B02Service.C c){ Row row=active(c.customer(),c.workItemId()); if(row.status.equals("DONE")) return replay(c); B02Service.R result=b02.execute(c); String before=write(Map.of("accountId",c.account(),"status","ACTIVE","version",c.version())); String after=write(Map.of("accountId",c.account(),"status",result.status(),"rate",result.rate(),"version",c.version()+1)); String detail=write(Map.of("requestId",c.requestId(),"accountId",c.account(),"waive",c.waive(),"taxed",c.taxed()==null?"UNKNOWN":c.taxed(),"result",result.status())); jdbc.update("insert into task_result(work_item_id,result_type,decision_code,rule_version,before_value,after_value,detail) values(:w,'HOUSING_SAVINGS_CLOSE',:d,'1.7.1',cast(:b as json),cast(:a as json),cast(:x as json))",Map.of("w",c.workItemId(),"d",result.rule(),"b",before,"a",after,"x",detail)); jdbc.update("update work_item set status='DONE',last_activity_at=UTC_TIMESTAMP(6),version=version+1 where id=:w and status='ACTIVE'",Map.of("w",c.workItemId())); jdbc.update("update workflow set status=case when exists(select 1 from work_item where workflow_id=:f and status in ('PLANNED','ACTIVE','ON_HOLD')) then 'WAITING_CONTINUE' else 'COMPLETED' end,updated_at=UTC_TIMESTAMP(6),version=version+1 where id=:f",Map.of("f",row.workflow)); return result; }
 private B02Service.R replay(B02Service.C c){ String detail=jdbc.queryForObject("select detail from task_result where work_item_id=:w",Map.of("w",c.workItemId()),String.class); try{var n=json.readTree(detail); if(!c.requestId().equals(n.path("requestId").asText())||c.account()!=n.path("accountId").asLong()||c.waive()!=n.path("waive").asBoolean())throw new DomainException("DUPLICATE_REQUEST_CONFLICT","requestId was reused."); return new B02Service.R(c.requestId(),c.customer(),c.account(),n.path("result").asText(),"REPLAY", "REPLAY",c);}catch(JsonProcessingException e){throw new DomainException("TASK_RESULT_INVALID","Stored B-02 result is invalid.");}}
 private Row active(long customer,long id){var rows=jdbc.query("select wi.workflow_id,wi.status,wi.task_code,w.customer_id from work_item wi join workflow w on w.id=wi.workflow_id where wi.id=:w for update",Map.of("w",id),(rs,n)->new Row(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4)));if(rows.isEmpty()||rows.get(0).customer!=customer||!"HOUSING_SAVINGS_CLOSE".equals(rows.get(0).code))throw new DomainException("WORK_ITEM_NOT_AVAILABLE","A B-02 work item is not available.");if(!"ACTIVE".equals(rows.get(0).status)&&!"DONE".equals(rows.get(0).status))throw new DomainException("WORK_ITEM_NOT_AVAILABLE","A B-02 work item is not active.");return rows.get(0);}
 private String write(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new DomainException("TASK_RESULT_SERIALIZATION_FAILED","B-02 result could not be stored.");}}
 private record Row(long workflow,String status,String code,long customer){}
}
