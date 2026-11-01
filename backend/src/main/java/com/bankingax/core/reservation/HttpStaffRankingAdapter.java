package com.bankingax.core.reservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI; import java.net.http.*; import java.time.Duration; import java.util.*;
import org.springframework.beans.factory.annotation.Value; import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import org.springframework.stereotype.Component;
/** Agent only ranks Spring-filtered IDs. Any bad/failed response falls back to deterministic ordering. */
@Component @ConditionalOnProperty(name="app.reservation.ranking-provider",havingValue="http") public class HttpStaffRankingAdapter implements StaffRankingPort {
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(); private final ObjectMapper json; private final String url;
 public HttpStaffRankingAdapter(ObjectMapper j,@Value("${app.staff-agent-base-url:}")String u){json=j;url=u;}
 public List<Long> rank(List<Long> ids,String task){try{if(url.isBlank())return fallback(ids);var body=json.writeValueAsString(Map.of("candidateIds",ids,"taskCode",task));var r=http.send(HttpRequest.newBuilder(URI.create(url+"/api/v1/staff-ranking")).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()/100!=2)return fallback(ids);var out=json.readTree(r.body()).path("staffIds");List<Long> ranked=new ArrayList<>();for(var n:out)if(n.canConvertToLong()&&ids.contains(n.asLong()))ranked.add(n.asLong());return ranked.isEmpty()?fallback(ids):ranked;}catch(Exception ignored){return fallback(ids);}}
 private static List<Long> fallback(List<Long> ids){return ids.stream().sorted().toList();}
}
