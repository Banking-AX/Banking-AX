package com.bankingax.task.b02;

import static org.junit.jupiter.api.Assertions.*;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.verification.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class B02ServiceTest {
  @Test void prepare_applies_b2_r2_r3_r5_and_r6() {
    var s = service();
    assertEquals("DOCUMENT_REQUIRED", s.prepare(1,3001,false,null).rate()); // B2-R6
    assertEquals("BASE_RATE", s.prepare(1,3001,true,null).rate());  // B2-R3
    assertTrue(s.prepare(1,3001,false,true).manual()); // B2-R5
  }
  @Test void consent_signature_binding_and_idempotency_are_server_enforced() {
    var v=new FakeVerification(); var s=new B02Service(v, accountId -> {});
    assertThrows(DomainException.class,()->s.issue(1,3001,0,false,true,"010"));
    assertThrows(DomainException.class,()->s.issue(1,3001,0,true,false,"010"));
    var binding=bind(s,v,0); var command=new B02Service.C("one",1,3001,0,true,null,binding);
    var closed=s.execute(command); assertEquals("CLOSED",closed.status()); assertSame(closed,s.execute(command));
    assertThrows(DomainException.class,()->s.execute(new B02Service.C("one",1,3001,0,false,null,binding)));
    assertThrows(DomainException.class,()->s.execute(new B02Service.C("two",1,3001,0,true,null,binding)));
  }
  @Test void b2_r5_and_r6_never_execute_automatically() {
    var v=new FakeVerification(); var s=new B02Service(v, accountId -> {}); String binding=bind(s,v,0);
    assertThrows(DomainException.class,()->s.execute(new B02Service.C("r5",1,3001,0,false,true,binding)));
    assertThrows(DomainException.class,()->s.execute(new B02Service.C("r6",1,3001,0,false,null,binding)));
  }
  @Test void rejects_foreign_stale_and_missing_or_reused_binding() {
    var v=new FakeVerification(); var s=new B02Service(v, accountId -> {});
    assertThrows(DomainException.class,()->s.prepare(2,3001,true,null));
    assertThrows(DomainException.class,()->s.issue(1,3001,1,true,true,"010"));
    assertThrows(DomainException.class,()->s.execute(new B02Service.C("missing",1,3001,0,true,null,"bad")));
    var binding=bind(s,v,0); s.execute(new B02Service.C("closed",1,3001,0,true,null,binding));
    assertThrows(DomainException.class,()->s.issue(1,3001,0,true,true,"010"));
  }
  private static B02Service service(){return new B02Service(new FakeVerification(), accountId -> {});}
  private static String bind(B02Service s,FakeVerification v,int version){s.issue(1,3001,version,true,true,"010");return s.verify(1,"c","000000").bindingId();}
  static class FakeVerification implements VerificationPort { boolean consumed; public VerificationIssue issue(VerificationIssueCommand c){return new VerificationIssue("c","***",Instant.now());} public VerificationBinding verify(VerificationVerifyCommand c){return new VerificationBinding("b",1,3001,VerificationAction.B02_EXECUTE,0,Instant.now());} public void consume(VerificationConsumeCommand c){if(consumed||!"b".equals(c.bindingId()))throw new DomainException("VERIFICATION_BINDING_INVALID","bad");consumed=true;} }
}
