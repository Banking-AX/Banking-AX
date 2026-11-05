package com.bankingax.core.notification;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Customer-scoped notification API (NOTI-03/04/05/14/15/18). Every query is bound to the authenticated customer. */
@RestController @RequestMapping("/api/v1/notifications") @com.bankingax.core.persistence.ConditionalOnDataSource
public class NotificationController {
 static final int DEFAULT_LIMIT=20, MAX_LIMIT=50;
 private final JdbcTemplate jdbc; private final InternalGatewayAuthenticator auth;
 public NotificationController(JdbcTemplate jdbc,InternalGatewayAuthenticator auth){this.jdbc=jdbc;this.auth=auth;}
 /** Newest first by id; beforeId is the keyset cursor for "이전 알림 더보기". */
 @GetMapping public ApiEnvelope<Page> list(@RequestHeader("X-Actor-Id") long actor,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader("X-Tool-Gateway-Token") String token,
   @RequestParam(defaultValue="false") boolean unreadOnly,@RequestParam(required=false) Long beforeId,@RequestParam(defaultValue="20") int limit,@RequestParam String requestId){
  customer(actor,role,token); int size=Math.max(1,Math.min(MAX_LIMIT,limit));
  StringBuilder sql=new StringBuilder("select n.id,n.case_id,n.work_item_id,wi.task_code,n.type,n.title,n.content,n.read_at,n.created_at,n.email_status from notification n left join work_item wi on wi.id=n.work_item_id where n.customer_id=?");
  List<Object> args=new ArrayList<>(List.of(actor));
  if(unreadOnly) sql.append(" and n.read_at is null");
  if(beforeId!=null){ sql.append(" and n.id<?"); args.add(beforeId); }
  sql.append(" order by n.id desc limit ?"); args.add(size+1);
  List<Item> rows=jdbc.query(sql.toString(),(r,n)->new Item(r.getLong(1),r.getObject(2,Long.class),r.getObject(3,Long.class),r.getString(4),r.getString(5),r.getString(6),r.getString(7),
   utc(r.getObject(8,LocalDateTime.class)),utc(r.getObject(9,LocalDateTime.class)),r.getString(10)),args.toArray());
  boolean hasMore=rows.size()>size;
  return ApiEnvelope.success(requestId,"NOTIFICATION",null,null,null,new Page(hasMore?rows.subList(0,size):rows,hasMore,summaryOf(actor)));
 }
 /** Lightweight badge source (NOTI-04/16). */
 @GetMapping("/summary") public ApiEnvelope<Summary> summary(@RequestHeader("X-Actor-Id") long actor,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader("X-Tool-Gateway-Token") String token,@RequestParam String requestId){
  customer(actor,role,token); return ApiEnvelope.success(requestId,"NOTIFICATION",null,null,null,summaryOf(actor));
 }
 @PostMapping("/{id}/read") public ApiEnvelope<Void> read(@PathVariable long id,@RequestHeader("X-Actor-Id") long actor,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader("X-Tool-Gateway-Token") String token,@RequestParam String requestId){customer(actor,role,token);int changed=jdbc.update("update notification set read_at=coalesce(read_at,UTC_TIMESTAMP(6)) where id=? and customer_id=?",id,actor);if(changed==0)throw new DomainException("NOT_FOUND","Notification was not found.");return ApiEnvelope.success(requestId,"NOTIFICATION",null,null,null,null);}
 /** Marks only this customer's unread rows; repeating it is harmless (NOTI-15). */
 @PostMapping("/read-all") public ApiEnvelope<ReadAll> readAll(@RequestHeader("X-Actor-Id") long actor,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader("X-Tool-Gateway-Token") String token,@RequestParam String requestId){
  customer(actor,role,token); int changed=jdbc.update("update notification set read_at=UTC_TIMESTAMP(6) where customer_id=? and read_at is null",actor);
  return ApiEnvelope.success(requestId,"NOTIFICATION",null,null,null,new ReadAll(changed,summaryOf(actor)));
 }
 private Summary summaryOf(long customer){return jdbc.queryForObject("select count(*),coalesce(sum(read_at is null),0) from notification where customer_id=?",(r,n)->new Summary(r.getLong(1),r.getLong(2)),customer);}
 private static Instant utc(LocalDateTime value){return value==null?null:value.toInstant(ZoneOffset.UTC);}
 private void customer(long actor,ActorRole role,String token){auth.verify(token);if(role!=ActorRole.CUSTOMER)throw new DomainException("FORBIDDEN","Customer access is required.");}
 /** Stored times are UTC (session time zone is UTC), so they are exposed as instants. emailStatus is reported as stored; no SMTP sender exists yet. */
 public record Item(long id,Long caseId,Long workItemId,String taskCode,String type,String title,String content,Instant readAt,Instant createdAt,String emailStatus){}
 public record Summary(long total,long unread){}
 public record Page(List<Item> items,boolean hasMore,Summary summary){}
 public record ReadAll(int updated,Summary summary){}
}
