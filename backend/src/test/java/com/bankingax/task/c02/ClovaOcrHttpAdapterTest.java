package com.bankingax.task.c02;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClovaOcrHttpAdapterTest {
    private HttpServer server;
    @BeforeEach void start() throws Exception { server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start(); }
    @AfterEach void stop(){server.stop(0);}

    @Test void mapsConfiguredTemplateFieldsAndDropsEmptyDelegatorRows() {
        respond(200,"""
          {"images":[{"inferResult":"SUCCESS","matchedTemplate":{"id":43868},"fields":[
          {"name":"depositor_name","inferText":" 김영준 ","inferConfidence":0.99},
          {"name":"account_1_number","inferText":"9990-0000-000001"},
          {"name":"agent_name","inferText":"김민수"},
          {"name":"delegator_1_name","inferText":"김하나"},
          {"name":"delegator_2_name","inferText":""},
          {"name":"delegator_2_birth6","inferText":"년월일"},
          {"name":"1_생년월일","inferText":"1955년 01월 01일"}]}]}
          """);
        var result=adapter().extract(request());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        Map<?,?> standard=(Map<?,?>)result.fields().get("standard");
        assertThat(standard.get("depositorName")).isEqualTo("김영준");
        assertThat(standard.get("accounts")).hasToString("[{accountNo=9990-0000-000001}]");
        assertThat(standard.get("delegators")).hasToString("[{name=김하나}]");
        assertThat(((Map<?,?>)standard.get("reference")).get("depositorBirthDate")).isEqualTo("19550101");
    }
    @Test void printedFormLabelsAndBlanksAreNeverBirthDates() {
        respond(200,fields("""
          {"name":"delegator_1_name","inferText":"김하나"},
          {"name":"delegator_1_birth6","inferText":"1994년01월03일"},
          {"name":"delegator_2_name","inferText":"이름만"},
          {"name":"delegator_2_birth6","inferText":"년월일"},
          {"name":"delegator_3_name","inferText":"공백생일"},
          {"name":"delegator_3_birth6","inferText":"   "},
          {"name":"delegator_4_birth6","inferText":"년월일"},
          {"name":"delegator_5_birth6","inferText":"1990년01월01일"},
          {"name":"1_생년월일","inferText":"년월일"},
          {"name":"제출일","inferText":"년 월 일"}
          """));
        var result=adapter().extract(request());
        Map<?,?> standard=(Map<?,?>)result.fields().get("standard");
        assertThat(standard.get("delegators")).hasToString("[{name=김하나, birthDate=19940103}, {name=이름만}, {name=공백생일}]");
        Map<?,?> reference=(Map<?,?>)standard.get("reference");
        assertThat(reference.get("depositorBirthDate")).isEqualTo("");
        assertThat(reference.get("submittedDate")).isEqualTo("");
    }
    @Test void rawOcrTextStaysBesideTheNormalizedValue() {
        respond(200,fields("""
          {"name":"delegator_1_name","inferText":"김하나"},
          {"name":"delegator_1_birth6","inferText":"1994년01월03일"},
          {"name":"delegator_2_birth6","inferText":"년월일"}
          """));
        var result=adapter().extract(request());
        Map<?,?> ocrText=(Map<?,?>)result.fields().get("fields"); assertThat(ocrText.get("delegator_1_birth6")).isEqualTo("1994년01월03일"); assertThat(ocrText.get("delegator_2_birth6")).isEqualTo("년월일");
        assertThat(((Map<?,?>)result.fields().get("standard")).get("delegators")).hasToString("[{name=김하나, birthDate=19940103}]");
    }
    @Test void acceptsTheSixDigitFormOfTheFormAndRejectsImpossibleDates() {
        respond(200,fields("""
          {"name":"delegator_1_name","inferText":"여섯자리"},
          {"name":"delegator_1_birth6","inferText":"940103"},
          {"name":"delegator_2_name","inferText":"없는날"},
          {"name":"delegator_2_birth6","inferText":"1994년13월45일"},
          {"name":"delegator_3_name","inferText":"없는짧은날"},
          {"name":"delegator_3_birth6","inferText":"941345"},
          {"name":"delegator_4_name","inferText":"자리수"},
          {"name":"delegator_4_birth6","inferText":"1994"},
          {"name":"제출일","inferText":"2026년02월30일"}
          """));
        Map<?,?> standard=(Map<?,?>)adapter().extract(request()).fields().get("standard");
        assertThat(standard.get("delegators")).hasToString("[{name=여섯자리, birthDate=940103}, {name=없는날}, {name=없는짧은날}, {name=자리수}]");
        assertThat(((Map<?,?>)standard.get("reference")).get("submittedDate")).isEqualTo("");
    }
    @Test void rejectsOtherTemplateAndNeverTreatsItAsSuccess(){respond(200,"""
        {"images":[{"inferResult":"SUCCESS","matchedTemplate":{"id":1},"fields":[]}]}
        """);assertThat(adapter().extract(request()).failureReason()).isEqualTo("CLOVA_TEMPLATE_MISMATCH");}
    @Test void mapsTimeoutToManualReview(){server.createContext("/slow",e->{try{Thread.sleep(200);}catch(InterruptedException x){Thread.currentThread().interrupt();}write(e,200,"{}");});var adapter=new ClovaOcrHttpAdapter(url("/slow"),"secret", "43868",Duration.ofSeconds(1),Duration.ofMillis(10),new ObjectMapper(),null);assertThat(adapter.extract(request()).failureReason()).isEqualTo("CLOVA_TIMEOUT");}
    private static String fields(String json){return "{\"images\":[{\"inferResult\":\"SUCCESS\",\"matchedTemplate\":{\"id\":43868},\"fields\":["+json+"]}]}";}
    private ClovaOcrHttpAdapter adapter(){return new ClovaOcrHttpAdapter(url("/infer"),"secret","43868",Duration.ofSeconds(1),Duration.ofSeconds(1),new ObjectMapper(),null);}
    private OcrPort.ExtractRequest request(){return new OcrPort.ExtractRequest(9,"ABSENT_HEIR_POWER_OF_ATTORNEY","opaque.pdf","delegation.pdf","application/pdf","%PDF-test".getBytes(StandardCharsets.UTF_8));}
    private void respond(int status,String body){server.createContext("/infer",e->write(e,status,body));}
    private static void write(com.sun.net.httpserver.HttpExchange e,int status,String body)throws java.io.IOException{byte[] b=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,b.length);try(var out=e.getResponseBody()){out.write(b);}}
    private String url(String path){return "http://127.0.0.1:"+server.getAddress().getPort()+path;}
}
