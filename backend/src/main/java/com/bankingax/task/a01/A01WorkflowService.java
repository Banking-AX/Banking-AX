package com.bankingax.task.a01;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ToolActor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists an A-01 query result and completes its already-active WorkItem. */
@Service
@ConditionalOnProperty(name="app.case-port",havingValue="jpa")
public class A01WorkflowService {
 private final NamedParameterJdbcTemplate jdbc; private final TransactionSearchService search; private final ObjectMapper json;
 public A01WorkflowService(NamedParameterJdbcTemplate jdbc,TransactionSearchService search,ObjectMapper json){this.jdbc=jdbc;this.search=search;this.json=json;}
 @Transactional public TransactionSearchResponse execute(ToolActor actor,TransactionSearchRequest request){
  if(request.workItemId()==null)throw unavailable();
  var rows=jdbc.query("select wi.workflow_id,wi.status,wi.task_code,w.customer_id from work_item wi join workflow w on w.id=wi.workflow_id where wi.id=:id for update",Map.of("id",request.workItemId()),(rs,n)->new Row(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4)));
  if(rows.isEmpty()||rows.get(0).customer!=request.customerId()||!"TRANSACTION_SEARCH".equals(rows.get(0).code))throw unavailable();
  Row row=rows.get(0); if("DONE".equals(row.status))return replay(request); if(!"ACTIVE".equals(row.status))throw unavailable();
  TransactionSearchResponse result=search.search(actor,request); String detail=write(Map.of("requestId",request.requestId(),"accountId",request.accountId(),"response",result));
  jdbc.update("insert into task_result(work_item_id,result_type,detail) values(:w,'TRANSACTION_SEARCH',cast(:d as json))",Map.of("w",request.workItemId(),"d",detail));
  jdbc.update("update work_item set status='DONE',last_activity_at=UTC_TIMESTAMP(6),version=version+1 where id=:w and status='ACTIVE'",Map.of("w",request.workItemId()));
  jdbc.update("update workflow set status=case when exists(select 1 from work_item where workflow_id=:f and status in ('PLANNED','ACTIVE','ON_HOLD')) then 'WAITING_CONTINUE' else 'COMPLETED' end,updated_at=UTC_TIMESTAMP(6),version=version+1 where id=:f",Map.of("f",row.workflow)); return result;
 }
 private TransactionSearchResponse replay(TransactionSearchRequest r){String d=jdbc.queryForObject("select detail from task_result where work_item_id=:w",Map.of("w",r.workItemId()),String.class);try{var n=json.readTree(d);if(!r.requestId().equals(n.path("requestId").asText())||r.accountId()!=n.path("accountId").asLong())throw new DomainException("DUPLICATE_REQUEST_CONFLICT","requestId was reused.");return json.treeToValue(n.path("response"),TransactionSearchResponse.class);}catch(Exception e){if(e instanceof DomainException x)throw x;throw new DomainException("TASK_RESULT_INVALID","Stored A-01 result is invalid.");}}
 private String write(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new DomainException("TASK_RESULT_SERIALIZATION_FAILED","A-01 result could not be stored.");}}
 private static DomainException unavailable(){return new DomainException("WORK_ITEM_NOT_AVAILABLE","An active A-01 work item is required.");} private record Row(long workflow,String status,String code,long customer){}
}
