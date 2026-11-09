package com.bankingax.task.c02;

import java.util.*;

/** Pure, non-persistent C-02 inheritance power-of-attorney comparison boundary.
 * A future CLOVA adapter maps its provider response to OcrDocument; this class
 * never calls OCR and never represents a legal/original-document verification. */
public final class C02PowerOfAttorneyOcrComparison {
    private C02PowerOfAttorneyOcrComparison() {}
    public record OcrDocument(String depositorName, String depositorBirthDate, List<Account> accounts, Person agent, List<Person> delegators, String extractionStatus) {
        public OcrDocument { accounts=accounts==null?List.of():List.copyOf(accounts); delegators=delegators==null?List.of():List.copyOf(delegators); }
        public OcrDocument(String depositorName, List<Account> accounts, Person agent, List<Person> delegators, String extractionStatus) { this(depositorName,null,accounts,agent,delegators,extractionStatus); }
    }
    public record Account(String accountNo) {}
    public record Person(String name, String birthDate) { public Person(String name) { this(name,null); } }
    /** heirBirthDates is keyed by the normalized name; an unknown or repeated name has no entry to compare against. */
    public record CaseFacts(long caseId,int revision,long documentFileId,String decedentName,String decedentBirthDate,List<String> attendingNames,List<String> absentNames,Map<String,String> heirBirthDates) {
        public CaseFacts { attendingNames=List.copyOf(attendingNames); absentNames=List.copyOf(absentNames); heirBirthDates=Map.copyOf(heirBirthDates); }
        public CaseFacts(long caseId,int revision,long documentFileId,String decedentName,List<String> attendingNames,List<String> absentNames) { this(caseId,revision,documentFileId,decedentName,null,attendingNames,absentNames,Map.of()); }
    }
    public enum Outcome { AUTO_MATCHED, MISMATCH, STAFF_REQUIRED, OCR_UNSUPPORTED }
    public record FieldResult(String field, Outcome outcome, String extractedValue, String expectedValue, String reason) {}
    public record Comparison(long caseId,int revision,long documentFileId,List<FieldResult> fields) {}

    public static Comparison compare(CaseFacts facts,OcrDocument ocr) {
        if (ocr==null || !"SUCCEEDED".equals(ocr.extractionStatus())) return unsupported(facts, "OCR extraction is unavailable or failed.");
        List<FieldResult> out=new ArrayList<>();
        out.add(compareOne("depositorName",ocr.depositorName(),facts.decedentName()));
        out.add(compareSet("agent.name",ocr.agent()==null?null:ocr.agent().name(),facts.attendingNames()));
        out.add(compareSet("delegators[].name",ocr.delegators().stream().map(Person::name).toList(),facts.absentNames()));
        for (Account account:ocr.accounts()) out.add(new FieldResult("accounts[].accountNo",Outcome.STAFF_REQUIRED,account.accountNo(),null,"No Case account-number comparison source exists."));
        if (ocr.accounts().isEmpty()) out.add(new FieldResult("accounts[].accountNo",Outcome.STAFF_REQUIRED,null,null,"OCR account number is missing and no comparison source exists."));
        out.add(compareBirthDate("depositorBirthDate",ocr.depositorBirthDate(),facts.decedentBirthDate()));
        out.add(compareBirthDate("agent.birthDate",ocr.agent()==null?null:ocr.agent().birthDate(),heirBirthDate(facts,ocr.agent())));
        for (int i=0;i<ocr.delegators().size();i++) { Person delegator=ocr.delegators().get(i); out.add(compareBirthDate("delegators["+i+"].birthDate",delegator.birthDate(),heirBirthDate(facts,delegator))); }
        return new Comparison(facts.caseId(),facts.revision(),facts.documentFileId(),List.copyOf(out));
    }
    private static Comparison unsupported(CaseFacts f,String reason) { return new Comparison(f.caseId(),f.revision(),f.documentFileId(),List.of(
        new FieldResult("depositorName",Outcome.OCR_UNSUPPORTED,null,null,reason),new FieldResult("agent.name",Outcome.OCR_UNSUPPORTED,null,null,reason),new FieldResult("delegators[].name",Outcome.OCR_UNSUPPORTED,null,null,reason),new FieldResult("accounts[].accountNo",Outcome.OCR_UNSUPPORTED,null,null,reason))); }
    private static String heirBirthDate(CaseFacts facts,Person person) { return person==null||blank(person.name())?null:facts.heirBirthDates().get(normalizedName(person.name())); }
    public static String normalizedName(String name) { return norm(name); }
    /** The form may hold the six resident-number digits; they prove the day and year digits, never the century. */
    private static FieldResult compareBirthDate(String field,String actual,String expected) {
        if(blank(actual)||blank(expected)) return new FieldResult(field,Outcome.STAFF_REQUIRED,actual,expected,"OCR or Case value is missing.");
        String ocr=actual.replaceAll("[^0-9]",""), caseDate=expected.replaceAll("[^0-9]","");
        boolean same=ocr.length()==6&&caseDate.length()==8?caseDate.endsWith(ocr):ocr.equals(caseDate);
        return new FieldResult(field,same?Outcome.AUTO_MATCHED:Outcome.MISMATCH,actual,expected,same?"Normalized date match.":"Normalized dates differ.");
    }
    private static FieldResult compareOne(String field,String actual,String expected) { if(blank(actual)||blank(expected))return new FieldResult(field,Outcome.STAFF_REQUIRED,actual,expected,"OCR or Case value is missing."); return new FieldResult(field,equal(actual,expected)?Outcome.AUTO_MATCHED:Outcome.MISMATCH,actual,expected,equal(actual,expected)?"Normalized exact match.":"Normalized values differ."); }
    private static FieldResult compareSet(String field,String actual,List<String> expected) { return compareSet(field,actual==null?List.of():List.of(actual),expected); }
    private static FieldResult compareSet(String field,List<String> actual,List<String> expected) { if(actual.isEmpty()||expected.isEmpty()||actual.stream().anyMatch(C02PowerOfAttorneyOcrComparison::blank))return new FieldResult(field,Outcome.STAFF_REQUIRED,String.join(",",actual),String.join(",",expected),"OCR or Case value is missing."); Set<String>a=actual.stream().map(C02PowerOfAttorneyOcrComparison::norm).collect(java.util.stream.Collectors.toSet()),e=expected.stream().map(C02PowerOfAttorneyOcrComparison::norm).collect(java.util.stream.Collectors.toSet()); return new FieldResult(field,a.equals(e)?Outcome.AUTO_MATCHED:Outcome.MISMATCH,String.join(",",actual),String.join(",",expected),a.equals(e)?"Normalized exact set match.":"Normalized name sets differ."); }
    private static boolean blank(String v){return v==null||v.trim().isEmpty();} private static String norm(String v){return v.replaceAll("\\s+","").trim();} private static boolean equal(String a,String b){return norm(a).equals(norm(b));}
}
