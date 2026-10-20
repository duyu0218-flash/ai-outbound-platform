package com.duozhi.outbound;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Source record semantics backed by fixed, per-table MyBatis statements. No SQL is accepted here. */
@Repository
public class Store {
    private final SqlSession sql;
    private final javax.sql.DataSource dataSource;
    private final Map<String,Map<String,Map<String,Object>>> tables;
    private final Map<String,String> mappers=new HashMap<>();
    public Store(SqlSession sql,ObjectMapper json,javax.sql.DataSource dataSource)throws IOException{
        this.dataSource=dataSource;
        this.sql=sql;
        try(var in=new ClassPathResource("contracts/tables.json").getInputStream()){
            tables=json.readValue(in,new TypeReference<Map<String,Map<String,Map<String,Object>>>>(){});
        }
        for(String name:tables.keySet()){
            String entity=Map.ofEntries(Map.entry("user","SysUser"),Map.entry("scripttemplate","ScriptTemplate"),Map.entry("scriptflowversion","ScriptFlowVersion"),Map.entry("knowledgeitem","KnowledgeItem"),Map.entry("phonesuppression","PhoneSuppression"),Map.entry("callsession","CallSession"),Map.entry("campaigncontact","CampaignContact"),Map.entry("callevent","CallEvent"),Map.entry("speechturn","SpeechTurn"),Map.entry("conversationstate","ConversationState"),Map.entry("taskoutbox","TaskOutbox"),Map.entry("taskreceipt","TaskReceipt"),Map.entry("callusage","CallUsage"),Map.entry("realtimesession","RealtimeSession"),Map.entry("callanalysis","CallAnalysis"),Map.entry("adminsetting","AdminSetting"),Map.entry("auditlog","AuditLog"),Map.entry("contactimportjob","ContactImportJob"),Map.entry("gatewaynode","GatewayNode"),Map.entry("telephonyline","TelephonyLine"),Map.entry("callbackinbox","CallbackInbox"),Map.entry("callbackinboxpartition","CallbackInboxPartition"),Map.entry("callbackinboxworker","CallbackInboxWorker"),Map.entry("webhookeventingest","WebhookEventIngest"),Map.entry("handoffrequest","HandoffRequest"),Map.entry("recordingasset","RecordingAsset"),Map.entry("callmetric","CallMetric"),Map.entry("smslog","SmsLog"),Map.entry("scenarioversion","ScenarioVersion"),Map.entry("productworkitem","ProductWorkItem"),Map.entry("callbackappointment","CallbackAppointment")).getOrDefault(name,Character.toUpperCase(name.charAt(0))+name.substring(1));
            mappers.put(name,"com.duozhi.outbound.mapper."+entity+"Mapper");
        }
    }
    public static LocalDateTime now(){return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);}
    private String namespace(String table){String value=mappers.get(table);if(value==null)throw new IllegalArgumentException("unknown table");return value;}
    public Object encode(String table,String column,Object value){
        var info=tables.get(table).get(column);if(info==null)throw new IllegalArgumentException("unknown column");if(value==null)return null;
        if(info.containsKey("enum")){
            @SuppressWarnings("unchecked") var values=(Map<String,String>)info.get("enum");
            String candidate=value.toString();return values.entrySet().stream().filter(e->e.getKey().equals(candidate)||e.getValue().equals(candidate)).map(Map.Entry::getKey).findFirst().orElseThrow(()->new ApiError(422,"invalid "+column));
        }
        String type=info.get("type").toString();
        if(type.equals("UUID")||type.equals("CHAR(32)"))return value instanceof UUID?value:UUID.fromString(value.toString());
        if(type.startsWith("DATETIME")||type.startsWith("TIMESTAMP")){
            if(value instanceof String text){text=text.replace(' ','T');try{return LocalDateTime.parse(text);}catch(java.time.format.DateTimeParseException e){try{return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}catch(java.time.format.DateTimeParseException invalid){throw new ApiError(422,"invalid "+column);}}}
        }return value;
    }
    public Map<String,Object> defaults(String table){namespace(table);var result=new LinkedHashMap<String,Object>();tables.get(table).forEach((column,info)->{if(info.containsKey("default"))result.put(column,info.get("default"));else if("now".equals(info.get("factory")))result.put(column,now());else if("uuid".equals(info.get("factory")))result.put(column,UUID.randomUUID());});return result;}
    public Map<String,Object> record(String table,Object entity){
        if(entity==null)return null;var result=new LinkedHashMap<String,Object>();var bean=new BeanWrapperImpl(entity);
        tables.get(table).forEach((column,info)->{Object value=entity instanceof Map?((Map<?,?>)entity).get(column):bean.getPropertyValue(property(column));if(value instanceof Enum<?>)value=value.toString().toLowerCase(Locale.ROOT);else if(value instanceof UUID)value=value.toString();else if(value instanceof java.sql.Timestamp t)value=t.toLocalDateTime().toString();else if(value instanceof LocalDateTime t)value=t.toString();result.put(column,value);});return result;
    }
    private String property(String column){StringBuilder out=new StringBuilder();boolean upper=false;for(char c:column.toCharArray()){if(c=='_'){upper=true;continue;}out.append(upper?Character.toUpperCase(c):c);upper=false;}return out.toString();}
    public Map<String,Object> insert(String table,Map<String,Object> input){return insert(table,input,true);}
    /** Append-only metrics do not need a read-back; generated keys and defaults are still written. */
    public void appendMetric(Map<String,Object> input){insert("callmetric",input,false);}
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> insert(String table,Map<String,Object> input,boolean reload){
        var values=defaults(table);values.putAll(input);Object entity;
        try{entity=Class.forName(namespace(table).replace(".mapper.",".entity.").replaceAll("Mapper$","")).getConstructor().newInstance();}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        var bean=new BeanWrapperImpl(entity);
        values.forEach((column,raw)->{Object value=encode(table,column,raw);Class<?> type=bean.getPropertyType(property(column));if(value!=null&&type.isEnum())value=Enum.valueOf((Class<? extends Enum>)type,value.toString());bean.setPropertyValue(property(column),value);});
        String explicit=namespace(table)+".insertExplicitId";sql.insert(input.get("id")!=null&&sql.getConfiguration().hasStatement(explicit)?explicit:namespace(table)+".insert",Map.of("row",entity));
        long tenant=values.get("tenant_id") instanceof Number n?n.longValue():0;
        if(!reload||Set.of("campaigncontact","callevent","webhookeventingest","taskoutbox","speechturn").contains(table))return record(table,entity);
        return record(table,sql.selectOne(namespace(table)+".findById",Map.of("id",bean.getPropertyValue("id"),"tenantId",tenant)));
    }
    public Map<String,Object> require(String table,long tenant,Object id){Object row=sql.selectOne(namespace(table)+".findById",Map.of("id",encode(table,"id",id),"tenantId",tenant));if(row==null)throw new ApiError(404,table+" not found");return record(table,row);}
    public Map<String,Object> update(String table,long tenant,Object id,Map<String,Object> input){
        Map<String,Object> previous=require(table,tenant,id);var changes=new LinkedHashMap<String,Object>();input.forEach((column,value)->{if(!Set.of("id","tenant_id","created_at").contains(column))changes.put(column,encode(table,column,value));});
        if(tables.get(table).containsKey("updated_at"))changes.put("updated_at",now());
        if(!changes.isEmpty())sql.update(namespace(table)+".patchFields",Map.of("id",encode(table,"id",id),"tenantId",tenant,"changes",changes));return require(table,tenant,id);
    }
    /** The caller already holds the call/platform lock and has loaded this row in
     * the same transaction. Keep the original patch SQL, tenant predicate and error
     * semantics, without the two read-backs whose results these callers discard. */
    public void writeLocked(String table,long tenant,Object id,Map<String,Object> input){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("known-row write requires transaction");
        var changes=new LinkedHashMap<String,Object>();input.forEach((column,value)->{
            if(!Set.of("id","tenant_id","created_at").contains(column))changes.put(column,encode(table,column,value));
        });
        if(tables.get(table).containsKey("updated_at"))changes.put("updated_at",now());
        if(changes.isEmpty()||sql.update(namespace(table)+".patchFields",Map.of("id",encode(table,"id",id),"tenantId",tenant,"changes",changes))==0)
            require(table,tenant,id);
    }
    /** Patch a row already read under the caller's transaction lock. */
    public Map<String,Object> patchLoaded(String table,Map<String,Object> row,Map<String,Object> input){
        writeLocked(table,CallLifecycle.tenant(row),row.get("id"),input);
        var result=new LinkedHashMap<>(row);input.forEach((key,value)->{if(!Set.of("id","tenant_id","created_at").contains(key))result.put(key,value instanceof LocalDateTime||value instanceof UUID?value.toString():value);});
        if(tables.get(table).containsKey("updated_at"))result.put("updated_at",now().toString());return result;
    }
    public void delete(String table,long tenant,Object id){require(table,tenant,id);sql.delete(namespace(table)+".deleteById",Map.of("id",encode(table,"id",id),"tenantId",tenant));}
    public List<Map<String,Object>> query(String table,String method,Map<String,Object> params){return sql.selectList(namespace(table)+"."+method,params).stream().map(row->record(table,row)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));}
    public Map<String,Object> one(String table,String method,Map<String,Object> params){var rows=query(table,method,params);return rows.isEmpty()?null:rows.get(0);}
    public int execute(String table,String method,Map<String,Object> params){return sql.update(namespace(table)+"."+method,params);}
    public long scalar(String table,String method,Map<String,Object> params){Number value=sql.selectOne(namespace(table)+"."+method,params);return value==null?0:value.longValue();}

    public static Map<String,Object> queryParameters(int tenantPosition,Object... values){
        var result=new LinkedHashMap<String,Object>();for(int index=0;index<values.length;index++)result.put("p"+index,values[index]);if(tenantPosition>=0)result.put("tenantId",values[tenantPosition]);return result;
    }
    public String lock(){return " FOR UPDATE";}
    public Map<String,Object> locate(String table,Object id,boolean locked){
        if(!tables.get(table).containsKey("tenant_id"))throw new IllegalArgumentException("only tenant business records can be located");
        for(var tenant:query("tenant","allForMaintenance",Map.of())){
            var result=one(table,locked?"lockById":"findById",Map.of("id",encode(table,"id",id),"tenantId",tenant.get("id")));if(result!=null)return result;
        }return null;
    }
    public void updateInternal(String table,Object id,Map<String,Object> input){
        if(!Set.of("tenant","gatewaynode","callbackinbox","callbackinboxpartition","callbackinboxworker").contains(table))throw new IllegalArgumentException("explicit tenant update required");
        var changes=new LinkedHashMap<String,Object>();input.forEach((column,value)->changes.put(column,encode(table,column,value)));if(!changes.isEmpty())execute(table,"patchFields",Map.of("id",encode(table,"id",id),"tenantId",0,"changes",changes));
    }

    public static Map<String,Object> scopedParameters(long tenant,Object... values){var result=queryParameters(-1,values);result.put("tenantId",tenant);return result;}
    public List<Map<String,Object>> across(String table,String method,Map<String,Object> input){
        var rows=new ArrayList<Map<String,Object>>();for(var tenant:query("tenant","allForMaintenance",Map.of())){var parameters=new LinkedHashMap<>(input);parameters.put("tenantId",tenant.get("id"));rows.addAll(query(table,method,parameters));}return rows;
    }
    public Map<String,Object> firstAcross(String table,String method,Map<String,Object> input){var rows=across(table,method,input);return rows.isEmpty()?null:rows.get(0);}
    public long sumAcross(String table,String method,Map<String,Object> input){long result=0;for(var tenant:query("tenant","allForMaintenance",Map.of())){var parameters=new LinkedHashMap<>(input);parameters.put("tenantId",tenant.get("id"));result+=scalar(table,method,parameters);}return result;}

    @SuppressWarnings({"unchecked","rawtypes"})
    public List<Map<String,Object>> orderedAcross(String table,String method,Map<String,Object> input,List<String> order,int limit){
        var rows=across(table,method,input);Comparator<Map<String,Object>> comparator=(left,right)->0;
        for(String item:order){String[] part=item.split(":");String column=part[0];boolean desc=part.length>1&&part[1].equals("DESC");if(!tables.get(table).containsKey(column))throw new IllegalArgumentException("unknown order column");
            Comparator<Map<String,Object>> next=(left,right)->{Object a=left.get(column),b=right.get(column);int comparison;if(a==b)comparison=0;else if(a==null)comparison=1;else if(b==null)comparison=-1;else comparison=((Comparable)a).compareTo(b);return desc?-comparison:comparison;};comparator=comparator.thenComparing(next);
        }rows.sort(comparator);return rows.subList(0,Math.min(limit,rows.size()));
    }
    public Map<String,Object> firstAcrossOrdered(String table,String method,Map<String,Object> input,List<String> order){var rows=orderedAcross(table,method,input,order,1);return rows.isEmpty()?null:rows.get(0);}

    public <T> T value(String table,String method,Map<String,Object> input,Class<T> type){return sql.selectOne(namespace(table)+"."+method,input);}
    public List<Map<String,Object>> projection(String table,String method,Map<String,Object> input){return sql.selectList(namespace(table)+"."+method,input);}
    public Map<String,Object> projectOne(String table,String method,Map<String,Object> input){return sql.selectOne(namespace(table)+"."+method,input);}
    public int ping(){try(var connection=dataSource.getConnection()){if(!connection.isValid(3))throw new java.sql.SQLException("database validation failed");return 1;}catch(java.sql.SQLException e){throw new org.springframework.dao.DataAccessResourceFailureException("database unavailable",e);}}

    public List<Map<String,Object>> groupedAcross(String table,String method,Map<String,Object> input){
        var totals=new LinkedHashMap<Object,Long>();for(var tenant:query("tenant","allForMaintenance",Map.of())){var scoped=new LinkedHashMap<>(input);scoped.put("tenantId",tenant.get("id"));for(var row:projection(table,method,scoped))totals.merge(row.get("value"),((Number)row.get("total")).longValue(),Long::sum);}
        var result=new ArrayList<Map<String,Object>>();totals.forEach((value,total)->result.add(JsonSupport.map("value",value,"total",total)));return result;
    }
}
