package com.bankingax.core.notification;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;

/** Customer-scoped notification API reusable by both C workflows. */
@RestController @RequestMapping("/api/v1/notifications") @ConditionalOnBean(JdbcTemplate.class)
public class NotificationController {
 private final JdbcTemplate jdbc; private final InternalGatewayAuthenticator auth;
 public NotificationController(JdbcTemplate jdbc,InternalGatewayAuthenticator auth){this.jdbc=jdbc;this.auth=auth;}
 @GetMapping public ApiEnvelope<List<Item>> list(@RequestHeader("X-Actor-Id") long actor,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader("X-Tool-Gateway-Token") String token,@RequestParam String requestId){customer(actor,role,token);var data=jdbc.query("select id,case_id,type,title,content,read_at,created_at from notification where customer_id=? order by created_at desc,id desc",(r,n)->new Item(r.getLong(1),r.getObject(2,Long.class),r.getString(3),r.getString(4),r.getString(5),r.getObject(6,java.time.LocalDateTime.class),r.getObject(7,java.time.LocalDateTime.class)),actor);return ApiEnvelope.success(requestId,"NOTIFICATION",null,null,null,data);}
 @PostMapping("/{id}/read") public ApiEnvelope<Void> read(@PathVariable long id,@RequestHeader("X-Actor-Id") long actor,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader("X-Tool-Gateway-Token") String token,@RequestParam String requestId){customer(actor,role,token);int changed=jdbc.update("update notification set read_at=coalesce(read_at,UTC_TIMESTAMP(6)) where id=? and customer_id=?",id,actor);if(changed==0)throw new DomainException("NOT_FOUND","Notification was not found.");return ApiEnvelope.success(requestId,"NOTIFICATION",null,null,null,null);}
 private void customer(long actor,ActorRole role,String token){auth.verify(token);if(role!=ActorRole.CUSTOMER)throw new DomainException("FORBIDDEN","Customer access is required.");}
 public record Item(long id,Long caseId,String type,String title,String content,java.time.LocalDateTime readAt,java.time.LocalDateTime createdAt){}
}
