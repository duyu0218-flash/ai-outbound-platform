package com.duozhi.outbound;

import java.util.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.apache.commons.csv.*;

@RestController
@RequestMapping("/api/v1/contacts")
public class ContactFiles {
    private final Store store;private final Transactions tx;private final Config config;private final JsonSupport json;private final Audit audit;
    public ContactFiles(Store store,Transactions tx,Config config,JsonSupport json,Audit audit){this.store=store;this.tx=tx;this.config=config;this.json=json;this.audit=audit;}
    private Identity actor(HttpServletRequest r){Identity i=Identity.from(r);i.admin();return i;}
    @PostMapping("/import") public Map<String,Object> importCsv(@RequestParam MultipartFile file,@RequestParam(defaultValue="true") boolean upsert,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request)throws IOException {
        Identity i=actor(request);if(config.production()&&key==null)throw new ApiError(400,"Idempotency-Key is required in production");String requestKey=key==null?UUID.randomUUID().toString():key.trim();
        if(!requestKey.matches("[\\p{L}\\p{N}._:-]{1,128}"))throw new ApiError(400,"invalid Idempotency-Key");
        if(file.getSize()>Math.max(1,config.number("CONTACT_IMPORT_MAX_BYTES", 20971520)))throw new ApiError(413,"csv file is too large");
        String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString();}catch(CharacterCodingException e){throw new ApiError(400,"csv must be UTF-8 encoded");}if(text.startsWith("\ufeff"))text=text.substring(1);
        final String content=text;
        return tx.run(()->{
            tx.platform();tx.tenant(i.tenant());Map<String,Object> job=store.one("contactimportjob","WHERE tenant_id=? AND request_key=?",i.tenant(),requestKey);
            if(job!=null&&"completed".equals(job.get("state")))return json.object(job.get("result_json").toString());if(job!=null&&"processing".equals(job.get("state")))throw new ApiError(409,"contact import is already processing");
            if(job==null)job=store.insert("contactimportjob",JsonSupport.map("tenant_id",i.tenant(),"request_key",requestKey));
            int total=0,created=0,updated=0,skipped=0,failed=0;List<String> errors=new ArrayList<>();Set<String> seen=new HashSet<>();
            try(CSVParser parser=CSVFormat.DEFAULT.parse(new StringReader(content))){
                Iterator<CSVRecord> records=parser.iterator();List<String> columns=Arrays.asList("phone","name","tags","consent_state","dnc","dnc_reason","timezone");boolean first=true;
                while(records.hasNext()){
                    CSVRecord row=records.next();List<String> fields=new ArrayList<>();for(String value:row)fields.add(value.trim());if(fields.stream().allMatch(String::isEmpty))continue;
                    if(first){first=false;List<String> header=new ArrayList<>();for(String field:fields)header.add(field.toLowerCase(Locale.ROOT));if(header.contains("phone")){if(header.get(0).isEmpty())throw new ApiError(400,"invalid CSV header");columns=header;continue;}}
                    total++;if(total>Math.max(1,config.number("CONTACT_IMPORT_MAX_ROWS", 200000)))throw new ApiError(413,"csv has too many rows");Map<String,String> values=new HashMap<>();for(int n=0;n<Math.min(columns.size(),fields.size());n++)values.put(columns.get(n),fields.get(n));
                    String phone,consent=values.getOrDefault("consent_state","").toLowerCase(Locale.ROOT),zone=values.getOrDefault("timezone","");if(consent.isEmpty())consent="unknown";if(consent.startsWith("consentstate."))consent=consent.substring(13);if(zone.isEmpty())zone="Asia/Shanghai";
                    try{phone=Contacts.phone(values.get("phone"));store.encode("contact","consent_state",consent);Contacts.timezone(zone);}catch(ApiError e){failed++;if(errors.size()<config.number("CONTACT_IMPORT_MAX_ERRORS", 1000))errors.add("row "+row.getRecordNumber()+": "+(e.getMessage().startsWith("phone must")?"invalid phone":e.getMessage()));continue;}
                    if(!seen.add(phone)){skipped++;if(errors.size()<config.number("CONTACT_IMPORT_MAX_ERRORS", 1000))errors.add("row "+row.getRecordNumber()+": duplicate phone in uploaded file");continue;}
                    Map<String,Object> old=store.one("contact","WHERE tenant_id=? AND phone=?",i.tenant(),phone);if(old!=null&&!upsert){skipped++;continue;}
                    String name=values.getOrDefault("name",""),reason=values.getOrDefault("dnc_reason","");boolean yes="consented".equals(consent);
                    Map<String,Object> changes=JsonSupport.map("phone",phone,"name",name.isEmpty()?(old==null?null:old.get("name")):name,"tags",values.getOrDefault("tags",""),"consent_state",consent,"dnc",Values.set("1","true","t","yes","y","on").contains(values.getOrDefault("dnc","").toLowerCase(Locale.ROOT)),"dnc_reason",reason.isEmpty()?null:reason,"timezone",zone,"consented_at",yes?Store.now():null,"consented_by",yes?i.actor():null);
                    if(old==null){changes.put("tenant_id",i.tenant());store.insert("contact",changes);created++;}else{store.update("contact",i.tenant(),old.get("id"),changes);updated++;}
                }
            }catch(IOException|UncheckedIOException e){throw new ApiError(400,"invalid CSV format");}
            Map<String,Object> result=JsonSupport.map("total",total,"created",created,"updated",updated,"skipped",skipped,"failed",failed,"errors",errors);
            store.update("contactimportjob",i.tenant(),job.get("id"),JsonSupport.map("state","completed","result_json",json.json(result)));audit.record(i,"import","contact",null,"rows="+total+", created="+created+", updated="+updated);return result;
        });
    }
    static String safe(Object value){String text=Objects.toString(value,"");return (!text.trim().isEmpty()&&"=+@-".indexOf(text.trim().charAt(0))>=0||text.startsWith("\t")||text.startsWith("\r"))?"'"+text:text;}
    @GetMapping("/export") public ResponseEntity<StreamingResponseBody> export(@RequestParam(required=false) Boolean dnc,@RequestParam(required=false) String consent,@RequestParam(required=false) String keyword,HttpServletRequest request){
        Identity i=actor(request);List<Object> args=new ArrayList<>(Collections.singletonList(i.tenant()));String where="WHERE tenant_id=?";
        if(keyword!=null&&!keyword.isEmpty()){where+=" AND (phone LIKE ? OR name LIKE ?)";args.add("%"+keyword+"%");args.add("%"+keyword+"%");}if(dnc!=null){where+=" AND dnc=?";args.add(dnc);}if(consent!=null&&!consent.isEmpty()){where+=" AND consent_state=?";args.add(store.encode("contact","consent_state",consent));}
        final String filters=where;audit.record(i,"export","contact",null,"contacts CSV");
        StreamingResponseBody stream=output->{Writer writer=new OutputStreamWriter(output,StandardCharsets.UTF_8);writer.write('\ufeff');CSVPrinter csv=new CSVPrinter(writer,CSVFormat.DEFAULT);String[] columns={"phone","name","tags","consent_state","dnc","dnc_reason","timezone","created_at","updated_at"};csv.printRecord((Object[])columns);Long last=null;
            while(true){List<Object> params=new ArrayList<>(args);String query=filters;if(last!=null){query+=" AND id<?";params.add(last);}List<Map<String,Object>> rows=store.list("contact",query+" ORDER BY id DESC LIMIT 500",params.toArray());if(rows.isEmpty())break;for(Map<String,Object> row:rows){List<String> cells=new ArrayList<>();for(String column:columns)cells.add(safe("consent_state".equals(column)?"ConsentState."+row.get(column).toString().toUpperCase(Locale.ROOT):row.get(column)));csv.printRecord(cells);}last=((Number)rows.get(rows.size()-1).get("id")).longValue();csv.flush();}csv.flush();};
        return ResponseEntity.ok().header("Content-Type","text/csv;charset=UTF-8").header("Content-Disposition","attachment; filename=contacts-"+i.tenant()+".csv").body(stream);
    }
}
