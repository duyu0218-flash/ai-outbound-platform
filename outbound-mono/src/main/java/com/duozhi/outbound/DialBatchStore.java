package com.duozhi.outbound;

import java.time.*;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.springframework.stereotype.Repository;

@Repository
public class DialBatchStore {
    @org.springframework.beans.factory.annotation.Autowired private DialCommandJournal journal;
    @org.springframework.beans.factory.annotation.Autowired private DialDailyCounters daily;
    private final SqlSession sql;private final Store store;private final Settings settings;
    public DialBatchStore(SqlSession sql,Store store,Settings settings){this.sql=sql;this.store=store;this.settings=settings;}
    private String statement(String name){return "com.duozhi.outbound.mapper.DialBatchMapper."+name;}
    List<Map<String,Object>> rows(String method,Map<String,Object> params){return sql.selectList(statement(method),params);}
    int write(String method,Map<String,Object> params){return sql.update(statement(method),params);}
    long count(String method,Map<String,Object> params){Number result=sql.selectOne(statement(method),params);return result==null?0:result.longValue();}
    List<Map<String,Object>> calls(long tenant,List<String> ids){return sql.selectList(statement("calls"),JsonSupport.map("tenantId",tenant,"ids",ids)).stream().map(raw->store.record("callsession",raw)).toList();}
    List<Map<String,Object>> tasks(Map<String,Object> params){return sql.selectList(statement("taskCandidates"),params).stream().map(raw->store.record("taskoutbox",raw)).toList();}
    List<Map<String,Object>> lockedTasks(Map<String,Object> params){return sql.selectList(statement("lockTasks"),params).stream().map(raw->store.record("taskoutbox",raw)).toList();}
    void prepare(DialBatchScope scope,long tenant,List<String> ids){
        scope.dailyGeneration=daily.readGeneration();
        // Keep the original global call lock order across query chunks.
        var ordered=ids.stream().distinct().sorted().toList();
        for(int start=0;start<ordered.size();start+=100)preparePart(scope,tenant,ordered.subList(start,Math.min(ordered.size(),start+100)));
        journal.preload(scope,tenant,ordered);
    }
    private void preparePart(DialBatchScope scope,long tenant,List<String> ids){
        var params=JsonSupport.map("tenantId",tenant,"ids",ids);
        var selected=new ArrayList<Map<String,Object>>();
        for(Object raw:sql.selectList(statement("calls"),params)){
            var call=store.record("callsession",raw);scope.calls.put(call.get("id").toString(),call);selected.add(call);
            for(int attempt:List.of(CallLifecycle.number(call,"attempts"),CallLifecycle.number(call,"attempts")+1))scope.stateKeys.add(call.get("id")+":"+attempt);
        }
        for(Object raw:sql.selectList(statement("states"),params)){var state=store.record("conversationstate",raw);scope.states.put(state.get("call_id")+":"+state.get("attempt"),state);}
        var phones=selected.stream().map(c->c.get("phone").toString()).distinct().toList();if(phones.isEmpty())return;
        for(String phone:phones)scope.contacts.put(tenant+":"+phone,null);
        for(Object raw:sql.selectList(statement("contacts"),JsonSupport.map("tenantId",tenant,"phones",phones))){var contact=store.record("contact",raw);scope.contacts.put(tenant+":"+contact.get("phone"),contact);}
        var compliance=settings.get(tenant,"compliance");var cutoffs=new ArrayList<Map<String,Object>>();
        for(String phone:phones){var c=scope.contacts.get(tenant+":"+phone);String name=c==null?"":Objects.toString(c.get("timezone"),"");if(name.isEmpty())name=Objects.toString(compliance.get("timezone"),"Asia/Shanghai");ZoneId zone;try{zone=ZoneId.of(name);}catch(DateTimeException invalid){zone=ZoneOffset.UTC;}
            var day=LocalDateTime.ofInstant(scope.instant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(),ZoneOffset.UTC);cutoffs.add(JsonSupport.map("phone",phone,"day",day,"zone",name));scope.phoneDayStarts.put(tenant+":"+phone,day);
        }
        for(var facts:rows("facts",JsonSupport.map("tenantId",tenant,"phones",cutoffs,"dailyGeneration",scope.dailyGeneration))){
            if(facts.get("legacy_attempts")==null||facts.get("event_attempts")==null)throw new TaskWorker.Deferred();
            scope.facts.put(tenant+":"+facts.get("phone"),facts);
        }
    }
}
