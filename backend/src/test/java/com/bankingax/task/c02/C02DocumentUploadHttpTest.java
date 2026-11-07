package com.bankingax.task.c02;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.ApiExceptionHandler;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.caseport.CasePort;
import com.bankingax.core.caseport.CreateCaseCommand;
import com.bankingax.core.caseport.InMemoryCasePort;
import com.bankingax.core.caseport.ReviewStatus;
import com.bankingax.core.caseport.ServiceCaseView;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import com.bankingax.core.verification.VerificationBinding;
import com.bankingax.core.verification.VerificationIssue;
import com.bankingax.core.verification.VerificationIssueCommand;
import com.bankingax.core.verification.VerificationPort;
import com.bankingax.core.verification.VerificationVerifyCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class C02DocumentUploadHttpTest {
    @TempDir java.nio.file.Path temp;

    @Test void customer_uploads_downloads_and_retries_a_synthetic_pdf_without_exposing_storage_path() throws Exception {
        C02FileStorage storage=new C02FileStorage(temp.toString(),1024);
        LocalFileDocumentPort documents=new LocalFileDocumentPort(temp.toString(),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        C02IntakeService service=new C02IntakeService(new InMemoryCasePort(),verification(),staff(),documents,storage);
        long caseId=prepare(service);
        MockMvc http=MockMvcBuilders.standaloneSetup(new C02Controller(service,new InternalGatewayAuthenticator("upload-token"))).setControllerAdvice(new ApiExceptionHandler()).build();
        byte[] bytes="%PDF-1.4\nsynthetic inheritance fixture".getBytes();
        MockMultipartFile upload=new MockMultipartFile("file","../inheritance.pdf","application/pdf",bytes);
        var first=http.perform(multipart("/api/v1/c02/{caseId}/documents/upload",caseId).file(upload).param("customerId","1").param("revision","0").param("requestId","upload-1").param("documentSlotKey","HEIR_IDENTITY_CARD:h1").header("X-Actor-Id",1).header("X-Actor-Role","CUSTOMER").header("X-Tool-Gateway-Token","upload-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.data.document.checksum").isString()).andReturn();
        long documentId=new com.fasterxml.jackson.databind.ObjectMapper().readTree(first.getResponse().getContentAsString()).at("/data/document/documentFileId").asLong();
        http.perform(get("/api/v1/c02/{caseId}/documents/{documentId}/content",caseId,documentId).param("customerId","1").header("X-Actor-Id",1).header("X-Actor-Role","CUSTOMER").header("X-Tool-Gateway-Token","upload-token"))
            .andExpect(status().isOk()).andExpect(content().bytes(bytes));
        http.perform(multipart("/api/v1/c02/{caseId}/documents/upload",caseId).file(upload).param("customerId","1").param("revision","0").param("requestId","upload-1").param("documentSlotKey","HEIR_IDENTITY_CARD:h1").header("X-Actor-Id",1).header("X-Actor-Role","CUSTOMER").header("X-Tool-Gateway-Token","upload-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        http.perform(get("/api/v1/c02/{caseId}/documents/{documentId}/content",caseId,documentId).param("customerId","1").header("X-Actor-Id",2).header("X-Actor-Role","CUSTOMER").header("X-Tool-Gateway-Token","upload-token"))
            .andExpect(status().isForbidden());

        long differentCase=prepare(service,7502);
        DomainException otherCaseDocument=assertThrows(DomainException.class,()->service.staffDocument(differentCase,documentId));
        assertEquals("FORBIDDEN",otherCaseDocument.code());

        // Metadata alone must never make an unavailable original look downloadable.
        storage.deleteQuietly(documents.findById(documentId).orElseThrow().storageReference());
        http.perform(get("/api/v1/c02/{caseId}/documents/{documentId}/content",caseId,documentId).param("customerId","1").header("X-Actor-Id",1).header("X-Actor-Role","CUSTOMER").header("X-Tool-Gateway-Token","upload-token"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test void removes_file_and_metadata_when_case_revision_cannot_be_saved() throws Exception {
        InMemoryCasePort delegate=new InMemoryCasePort();
        CasePort failingRevisionPort=new CasePort() {
            public ServiceCaseView create(CreateCaseCommand command) { return delegate.create(command); }
            public ServiceCaseView submit(long actorId,long caseId,int revision,String requestId) { return delegate.submit(actorId,caseId,revision,requestId); }
            public ServiceCaseView revise(long actorId,long caseId,int revision,Map<String,Object> application,String reason) { throw new DomainException("CASE_STORE_FAILURE","synthetic failure",true); }
            public ServiceCaseView cancel(long actorId,long caseId,int revision) { return delegate.cancel(actorId,caseId,revision); }
            public ServiceCaseView recordCheckResult(long caseId,int revision,ReviewStatus status) { return delegate.recordCheckResult(caseId,revision,status); }
            public ServiceCaseView get(long actorId,long caseId) { return delegate.get(actorId,caseId); }
            public java.util.Optional<ServiceCaseView> findByWorkItem(long actorId,long workItemId,com.bankingax.core.workflow.TaskCode taskCode) { return delegate.findByWorkItem(actorId,workItemId,taskCode); }
        };
        InMemoryDocumentPort documents=new InMemoryDocumentPort();
        C02IntakeService service=new C02IntakeService(failingRevisionPort,verification(),staff(),documents,new C02FileStorage(temp.toString(),1024));
        long caseId=prepare(service);
        assertThrows(DomainException.class,()->service.uploadDocument(1,caseId,0,"compensate","HEIR_IDENTITY_CARD:h1",new MockMultipartFile("file","fixture.pdf","application/pdf","%PDF-1.4".getBytes())));
        assertEquals(0,documents.findByCase(caseId).size());
        try(var saved=java.nio.file.Files.list(temp.resolve("files"))) { assertEquals(0,saved.count()); }
    }

    @Test void submit_rejects_forged_uploaded_status_and_accepts_only_server_recorded_files() {
        C02FileStorage storage=new C02FileStorage(temp.toString(),1024);
        LocalFileDocumentPort documents=new LocalFileDocumentPort(temp.toString(),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        C02IntakeService service=new C02IntakeService(new InMemoryCasePort(),verification(),staff(),documents,storage);
        var intake=new C02IntakeService.Intake(new C02IntakeService.DecedentInput("synthetic",null,LocalDate.of(2020,1,1),6_000_000L),List.of(
            new C02IntakeService.HeirInput("attending","applicant",LocalDate.of(1980,1,1),"child",true,false,C02IntakeService.VisitStatus.ATTENDING,C02IntakeService.DelegationMethod.NONE,null),
            new C02IntakeService.HeirInput("absent","delegator",LocalDate.of(1982,1,1),"child",false,false,C02IntakeService.VisitStatus.ABSENT,C02IntakeService.DelegationMethod.DOCUMENT,null)));
        java.util.Map<String,C02IntakeService.DocumentStatus> forged=new java.util.HashMap<>();
        for(var slot:C02IntakeService.requiredDocuments(intake)) if(slot.requirement()==C02IntakeService.Requirement.REQUIRED) forged.put(slot.key(),C02IntakeService.DocumentStatus.UPLOADED);
        var prepared=service.prepare(1,7601,"forged-prepare",intake,forged,"010"); long caseId=prepared.caseView().caseId();
        service.recordConsentAndMockSignature(1,caseId,0,true,true);
        assertEquals("C02_SUBMISSION_BLOCKED",assertThrows(DomainException.class,()->service.submit(new C02IntakeService.C02SubmitCommand(1,caseId,0,"forged-submit","binding"))).code());
        int revision=0;
        for(var slot:C02IntakeService.requiredDocuments(intake)) if(slot.requirement()==C02IntakeService.Requirement.REQUIRED)
            revision=service.uploadDocument(1,caseId,revision,"upload-"+slot.key(),slot.key(),new MockMultipartFile("file",slot.key().replace(':','-')+".pdf","application/pdf","%PDF-1.4\nsynthetic".getBytes())).revision();
        service.recordConsentAndMockSignature(1,caseId,revision,true,true);
        assertEquals("SUBMITTED",service.submit(new C02IntakeService.C02SubmitCommand(1,caseId,revision,"real-submit","binding")).caseStatus());
    }

    private static long prepare(C02IntakeService service) { return prepare(service,7501); }
    private static long prepare(C02IntakeService service,long workItemId) {
        var intake=new C02IntakeService.Intake(new C02IntakeService.DecedentInput("synthetic",null,LocalDate.of(2020,1,1),0),List.of(new C02IntakeService.HeirInput("h1","synthetic heir",LocalDate.of(1980,1,1),"child",true,false,C02IntakeService.VisitStatus.ATTENDING,C02IntakeService.DelegationMethod.NONE,null)));
        return service.prepare(1,workItemId,"upload-prepare-"+workItemId,intake,Map.of(),"010").caseView().caseId();
    }
    private static VerificationPort verification() { return new VerificationPort() {
        public VerificationIssue issue(VerificationIssueCommand command) { return new VerificationIssue("x","010",Instant.now()); }
        public VerificationBinding verify(VerificationVerifyCommand command) { return new VerificationBinding("binding",command.customerId(),0,com.bankingax.core.verification.VerificationAction.C02_SUBMIT,0,Instant.now()); }
        public void consume(com.bankingax.core.verification.VerificationConsumeCommand command) { }
    }; }
    private static StaffAgentPort staff() { return (requestId,caseId,revision,snapshot)->new StaffAgentPort.StaffAgentResponse("SUCCEEDED","READY_FOR_STAFF",List.of(),null); }
}
