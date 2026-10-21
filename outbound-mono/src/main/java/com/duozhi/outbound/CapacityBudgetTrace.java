package com.duozhi.outbound;

import java.time.Instant;
import java.util.*;

/** Replays unique, committed fact revisions against externally exported snapshots.
 * Keys include business day and timezone; absent windows never default to zero. */
public final class CapacityBudgetTrace {
    public record Key(String scope,String businessDay,String timezoneRevision) {
        public Key { if(scope==null||scope.isBlank()||businessDay==null||businessDay.isBlank()||timezoneRevision==null||timezoneRevision.isBlank())throw new IllegalArgumentException("budget window identity required"); }
    }
    public record Value(long legacy,long events,long providerCalls,long expense) {
        public Value { if(legacy<0||events<0||providerCalls<0||expense<0)throw new IllegalArgumentException("negative budget component"); }
        long effective(){return Math.max(legacy,events);}
        Value change(Delta d){return new Value(Math.addExact(legacy,d.legacy()),Math.addExact(events,d.events()),Math.addExact(providerCalls,d.providerCalls()),Math.addExact(expense,d.expense()));}
    }
    public record Delta(long legacy,long events,long providerCalls,long expense) { }
    public record Change(Key key,String factIdentity,long revision,Instant committedAt,String origin,Delta delta) {
        public Change { if(key==null||factIdentity==null||factIdentity.isBlank()||revision<1||committedAt==null||!Set.of("main","auxiliary").contains(origin)||delta==null)throw new IllegalArgumentException("invalid budget fact"); }
    }
    public record Result(Map<Key,Value> atT0,Map<Key,Value> atT1,Map<Key,Value> atEnd,Map<String,Integer> phaseFacts,List<String> errors) {
        public boolean passed(){return errors.isEmpty();}
    }
    private CapacityBudgetTrace() { }
    public static Result verify(Map<Key,Value> initializedWindows,List<Change> changes,
                                Instant warm,Instant t0,Instant t1,Instant end,
                                Map<Key,Value> actualT0,Map<Key,Value> actualT1,Map<Key,Value> actualEnd) {
        if(warm.isAfter(t0)||!t0.isBefore(t1)||!t1.isBefore(end))throw new IllegalArgumentException("invalid phase boundaries");
        var errors=new ArrayList<String>();var ids=new HashSet<String>();var values=new HashMap<>(initializedWindows);
        var phases=new LinkedHashMap<String,Integer>();var ordered=new ArrayList<>(changes);
        ordered.sort(Comparator.comparing(Change::committedAt).thenComparing(Change::factIdentity).thenComparingLong(Change::revision));
        Map<Key,Value> atT0=null,atT1=null;
        for(var change:ordered) {
            if(atT0==null&&!change.committedAt().isBefore(t0))atT0=Map.copyOf(values);
            if(atT1==null&&!change.committedAt().isBefore(t1))atT1=Map.copyOf(values);
            String identity=change.key()+":"+change.factIdentity()+":"+change.revision();
            if(!ids.add(identity)){errors.add("duplicate fact revision: "+identity);continue;}
            if(change.committedAt().isBefore(warm)||!change.committedAt().isBefore(end)){errors.add("fact outside captured phases: "+identity);continue;}
            Value before=values.get(change.key());
            if(before==null){errors.add("window not initialized: "+change.key());continue;}
            try{values.put(change.key(),before.change(change.delta()));}catch(ArithmeticException|IllegalArgumentException invalid){errors.add("invalid budget delta: "+identity);continue;}
            String phase=change.committedAt().isBefore(t0)?"pre":change.committedAt().isBefore(t1)?"run":"tail";
            phases.merge(phase+":"+change.origin(),1,Integer::sum);
        }
        if(atT0==null)atT0=Map.copyOf(values);if(atT1==null)atT1=Map.copyOf(values);
        var finalValues=Map.copyOf(values);
        if(!atT0.equals(actualT0))errors.add("T0 snapshot differs from replay");
        if(!atT1.equals(actualT1))errors.add("T1 snapshot differs from replay");
        if(!finalValues.equals(actualEnd))errors.add("end snapshot differs from replay");
        return new Result(atT0,atT1,finalValues,Map.copyOf(phases),List.copyOf(errors));
    }
}
