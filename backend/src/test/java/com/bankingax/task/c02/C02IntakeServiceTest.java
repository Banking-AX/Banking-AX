package com.bankingax.task.c02;
import static org.junit.jupiter.api.Assertions.*;
import com.bankingax.core.caseport.InMemoryCasePort;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
class C02IntakeServiceTest {
 private C02IntakeService service(){ return new C02IntakeService(new InMemoryCasePort()); }
 private C02IntakeService.Intake intake(LocalDate death, C02IntakeService.VisitStatus visit, C02IntakeService.DelegationMethod delegation){ return new C02IntakeService.Intake(new C02IntakeService.DecedentInput("고인",null,death,6800000),List.of(new C02IntakeService.HeirInput("h1","신청인",LocalDate.of(1980,1,1),"자녀",true,false,visit,delegation,delegation==C02IntakeService.DelegationMethod.RECEIPT?"R-1":null))); }
 @Test void rejects_out_of_scope_death_and_minor(){ assertTrue(service().validate(intake(LocalDate.of(2007,12,31), C02IntakeService.VisitStatus.ATTENDING, C02IntakeService.DelegationMethod.NONE)).outOfScope().contains("DEATH_DATE_BEFORE_2008")); var minor=new C02IntakeService.Intake(new C02IntakeService.DecedentInput("고인",null,LocalDate.of(2020,1,1),1),List.of(new C02IntakeService.HeirInput("h","미성년",LocalDate.now().minusYears(10),"자녀",true,false,C02IntakeService.VisitStatus.ATTENDING,C02IntakeService.DelegationMethod.NONE,null))); assertTrue(service().validate(minor).outOfScope().contains("MINOR_HEIR")); }
 @Test void derives_document_delegation_slots_and_blocks_missing(){ var i=intake(LocalDate.of(2020,1,1),C02IntakeService.VisitStatus.ABSENT,C02IntakeService.DelegationMethod.DOCUMENT); var slots=service().requiredDocuments(i); assertTrue(slots.stream().anyMatch(s->s.documentTypeCode().equals("ABSENT_HEIR_POWER_OF_ATTORNEY"))); assertFalse(service().assessSubmission(i,Map.of()).submittable()); }
 @Test void creates_c02_case_through_existing_case_port(){ var view=service().createDraft(1,1001,intake(LocalDate.of(2020,1,1),C02IntakeService.VisitStatus.ATTENDING,C02IntakeService.DelegationMethod.NONE)); assertEquals("INHERITANCE_DEPOSIT",view.taskCode().name()); assertTrue(view.visitRequired()); assertTrue(view.application().containsKey("c02")); }
 @Test void prepare_is_idempotent_and_out_of_scope_does_not_create_case(){ var s=service(); var valid=intake(LocalDate.of(2020,1,1),C02IntakeService.VisitStatus.ATTENDING,C02IntakeService.DelegationMethod.NONE); var first=s.prepare(1,1001,"prepare-1",valid,Map.of()); assertEquals(first,s.prepare(1,1001,"prepare-1",valid,Map.of())); assertThrows(com.bankingax.core.api.DomainException.class,()->s.prepare(2,1001,"prepare-1",valid,Map.of())); assertNull(s.prepare(1,1002,"scope-1",intake(LocalDate.of(2007,1,1),C02IntakeService.VisitStatus.ATTENDING,C02IntakeService.DelegationMethod.NONE),Map.of()).caseView()); }
}
