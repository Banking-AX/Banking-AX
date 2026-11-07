package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Produces an unsigned, non-persisted draft only. It never creates document_file metadata. */
@Service public class C02PowerOfAttorneyDraftService {
  private final C02IntakeService intake; private final String fontPath;
  public C02PowerOfAttorneyDraftService(C02IntakeService intake,@Value("${app.c02.pdf-font-path:C:/Windows/Fonts/malgun.ttf}")String fontPath){this.intake=intake;this.fontPath=fontPath;}
  public byte[] create(long customerId,long caseId,Request extra){
    var snapshot=intake.customerCaseSnapshot(customerId,caseId); if(!"DRAFT".equals(snapshot.caseStatus()))throw new DomainException("INVALID_STATE","Only a draft C-02 case can create an unsigned power-of-attorney draft.");
    var heir=snapshot.intake().heirs().stream().filter(h->h.personRef().equals(extra.delegatorPersonRef())&&"ABSENT".equals(h.visitStatus())&&"DOCUMENT".equals(h.delegationMethod())).findFirst().orElseThrow(()->new DomainException("DOCUMENT_SLOT_UNKNOWN","Written-delegation heir was not found."));
    var agent=snapshot.intake().heirs().stream().filter(h->"ATTENDING".equals(h.visitStatus())).findFirst().orElseThrow(()->new DomainException("CASE_SNAPSHOT_INVALID","Attending agent is required."));
    if(extra.accountNumbers()==null||extra.accountNumbers().isEmpty()||extra.accountNumbers().size()>3)throw new DomainException("POA_ACCOUNT_REQUIRED","Enter one to three account numbers.");
    File fontFile=new File(fontPath); if(!fontFile.isFile())throw new DomainException("POA_DRAFT_FONT_UNAVAILABLE","A Korean PDF font is not configured for this server.");
    try(InputStream template=new ClassPathResource("c02/templates/inheritance-poa-blank.pdf").getInputStream(); PDDocument doc=Loader.loadPDF(template.readAllBytes()); InputStream fontIn=new java.io.FileInputStream(fontFile);){
      var font=PDType0Font.load(doc,fontIn); var page=doc.getPage(0); try(PDPageContentStream out=new PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true)){
        // Coordinates are the blank cells in the supplied 5-101-0337 page, not the nearby printed labels.
        writeRequiredFit(out,font,150,535,105,snapshot.intake().decedentName(),"decedent name"); writeDate(out,font,420,535,snapshot.intake().decedentBirthDate());
        for(int i=0;i<extra.accountNumbers().size();i++){ float y=493-i*20; writeFit(out,font,80,y,75,blank(extra.depositProduct()),"deposit product"); writeRequiredFit(out,font,170,y,95,accountNumber(extra.accountNumbers().get(i)),"account number"); }
        writeRequiredFit(out,font,170,409,95,agent.name(),"agent name"); writeDate(out,font,420,409,agent.birthDate()); writeFit(out,font,170,387,355,blank(extra.agentAddress()),"agent address");
        writeRequiredFit(out,font,80,298,75,heir.name(),"delegator name"); writeDate(out,font,170,298,heir.birthDate()); writeFit(out,font,280,298,185,blank(extra.delegatorAddress()),"delegator address");
        writeDate(out,font,260,187,extra.writtenDate());
      } ByteArrayOutputStream bytes=new ByteArrayOutputStream();doc.save(bytes);doc.close();return bytes.toByteArray();
    }catch(DomainException e){throw e;}catch(Exception e){throw new DomainException("POA_DRAFT_UNAVAILABLE","Power-of-attorney draft could not be generated.",true);}
  }
  private static void writeRequiredFit(PDPageContentStream out,PDType0Font font,float x,float y,float width,String text,String field)throws java.io.IOException{String value=blank(text);if(value.isEmpty())throw new DomainException("POA_FIELD_REQUIRED",field+" is required for the draft.");writeFit(out,font,x,y,width,value,field);}
  private static void writeFit(PDPageContentStream out,PDType0Font font,float x,float y,float width,String text,String field)throws java.io.IOException{String value=blank(text); if(value.isEmpty())return; float size=10; while(size>7&&font.getStringWidth(value)/1000*size>width)size-=1; if(font.getStringWidth(value)/1000*size>width)throw new DomainException("POA_FIELD_TOO_LONG",field+" does not fit the official form. Shorten the input without omitting identifying information."); out.beginText();out.setFont(font,size);out.newLineAtOffset(x,y);out.showText(value);out.endText();}
  private static void writeDate(PDPageContentStream out,PDType0Font font,float x,float y,LocalDate value)throws java.io.IOException{if(value==null)return; writeFit(out,font,x,y,28,String.valueOf(value.getYear()),"date");writeFit(out,font,x+40,y,18,String.format("%02d",value.getMonthValue()),"date");writeFit(out,font,x+70,y,18,String.format("%02d",value.getDayOfMonth()),"date");}
  private static String accountNumber(String value){String raw=blank(value);if(raw.isEmpty())throw new DomainException("POA_ACCOUNT_REQUIRED","An account number is required.");if(!raw.matches("[0-9\\s-]+"))throw new DomainException("POA_ACCOUNT_INVALID","Account numbers may contain only digits, spaces, or hyphens.");return raw.replaceAll("[\\s-]","");}
  private static String blank(String s){return s==null?"":s.trim();}
  public record Request(String delegatorPersonRef,List<String> accountNumbers,String depositProduct,String agentAddress,String delegatorAddress,String delegatorRelationship,LocalDate writtenDate){}
}
