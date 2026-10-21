package com.duozhi.outbound;

import java.util.*;
import static com.duozhi.outbound.CapacityEvidenceValidator.SECOND;
import static com.duozhi.outbound.CapacityEvidenceValidator.Event;

/** Offline L03 evidence checks. The caller must export the complete frozen slot
 * manifest, including warmup, and independently prove clock/export completeness.
 * Passing these AI checks alone never qualifies a capacity run. */
public final class CapacityAiEvidenceValidator {
    public record Span(String session,Event activated,Event deactivated) {
        public Span {
            if(session==null||session.isBlank()||activated==null||deactivated==null||
                !session.equals(activated.identity())||!session.equals(deactivated.identity()))
                throw new IllegalArgumentException("interactive session evidence required");
        }
    }
    public record Slot(String identity,String session,boolean opening,Event planned) {
        public Slot {
            if(identity==null||identity.isBlank()||session==null||session.isBlank()||planned==null||!identity.equals(planned.identity()))
                throw new IllegalArgumentException("original planned AI slot required");
        }
    }
    public record Reply(String identity,Event completed,boolean success) {
        public Reply {
            if(identity==null||identity.isBlank()||completed==null||!identity.equals(completed.identity()))
                throw new IllegalArgumentException("durable AI completion identity required");
        }
    }
    public record Result(long[] plannedSubsequent,long[] completedSubsequent,long minimumInteractive,
                         long worstInteractiveAt,long p95Micros,long errorCount,List<String> errors) {
        public boolean passed(){return errorCount==0;}
    }
    private record Interval(long start,long end) { }
    private static final class Problems {
        long count;final List<String> examples=new ArrayList<>();
        void add(String error){count++;if(examples.size()<100)examples.add(error);}
    }
    private CapacityAiEvidenceValidator(){ }

    public static Result validate(List<Span> spans,List<Slot> manifest,List<Reply> completions,long t0,int seconds){
        if(seconds<1||seconds>7*86400)throw new IllegalArgumentException("explicit bounded AI window required");
        long t1=Math.addExact(t0,Math.multiplyExact((long)seconds,SECOND));
        var errors=new Problems();var ready=new HashMap<String,List<Interval>>();
        for(var span:spans){
            // Only [activation.hi, deactivation.lo) is provably interactive.
            long start=span.activated().hi(),end=span.deactivated().lo();
            if(end<=start){errors.add("invalid interactive interval: "+span.session());continue;}
            ready.computeIfAbsent(span.session(),key->new ArrayList<>()).add(new Interval(start,end));
        }
        ready.replaceAll((session,intervals)->merge(intervals));
        var replies=new HashMap<String,Reply>();
        for(var reply:completions)if(replies.putIfAbsent(reply.identity(),reply)!=null)errors.add("duplicate AI completion: "+reply.identity());
        var plannedIds=new HashSet<String>();var active=new HashMap<String,List<Interval>>();
        long[] planned=new long[seconds],completed=new long[seconds];var latencies=new ArrayList<Long>();
        for(var slot:manifest){
            if(!plannedIds.add(slot.identity())){errors.add("duplicate AI slot: "+slot.identity());continue;}
            var reply=replies.remove(slot.identity());var plan=slot.planned();
            if(!slot.opening())count(plan,planned,t0,t1);
            if(reply==null){errors.add("missing AI completion: "+slot.identity());continue;}
            if(!reply.success()){errors.add("unsuccessful AI completion: "+slot.identity());continue;}
            long delay=Math.subtractExact(reply.completed().hi(),plan.lo());
            if(reply.completed().hi()<plan.lo()){errors.add("impossible AI time ordering: "+slot.identity());continue;}
            if(delay>5*SECOND)errors.add("AI hard deadline exceeded: "+slot.identity());
            boolean live=ready.getOrDefault(slot.session(),List.of()).stream().anyMatch(interval->interval.start()<=plan.lo()&&interval.end()>reply.completed().hi());
            if(!live)errors.add("AI turn not contained in proven interactive lifetime: "+slot.identity());
            if(plan.lo()>=t0&&plan.hi()<t1)latencies.add(delay);
            if(!slot.opening()){
                count(reply.completed(),completed,t0,t1);
                // A connection without planned follow-up activity cannot count
                // toward the 500-session gate. Warmup slots establish T0 activity.
                long end=Math.addExact(plan.lo(),10*SECOND);
                if(delay<=5*SECOND&&plan.hi()<end)active.computeIfAbsent(slot.session(),key->new ArrayList<>()).add(new Interval(plan.hi(),end));
            }
        }
        for(String id:replies.keySet())errors.add("unplanned AI completion: "+id);
        var changes=new TreeMap<Long,Long>();changes.put(t0,0L);
        for(var entry:ready.entrySet()){
            var sessionReady=merge(entry.getValue());var sessionActive=merge(active.getOrDefault(entry.getKey(),List.of()));
            int a=0,b=0;
            while(a<sessionReady.size()&&b<sessionActive.size()){
                var r=sessionReady.get(a);var s=sessionActive.get(b);long start=Math.max(t0,Math.max(r.start(),s.start())),end=Math.min(t1,Math.min(r.end(),s.end()));
                if(start<end){changes.merge(start,1L,Long::sum);changes.merge(end,-1L,Long::sum);}
                if(r.end()<=s.end())a++;else b++;
            }
        }
        long current=0,minimum=Long.MAX_VALUE,worst=t0;
        for(var point:changes.entrySet()){
            if(point.getKey()>=t1)break;current+=point.getValue();
            if(current<minimum){minimum=current;worst=point.getKey();}
        }
        if(minimum<500)errors.add("fewer than 500 interactive AI sessions at "+worst);
        for(int second=0;second<seconds;second++){
            if(planned[second]<50)errors.add("fewer than 50 subsequent AI slots in second "+second);
            if(completed[second]<50)errors.add("fewer than 50 subsequent AI completions in second "+second);
        }
        latencies.sort(Long::compare);long p95=-1;
        if(latencies.isEmpty())errors.add("missing main-window AI latency population");
        else{p95=latencies.get((int)Math.ceil(latencies.size()*.95)-1);if(p95>1_500_000)errors.add("AI P95 exceeds 1500 ms");}
        return new Result(planned,completed,minimum,worst,p95,errors.count,List.copyOf(errors.examples));
    }
    private static void count(Event event,long[] buckets,long t0,long t1){
        if(event.lo()<t0||event.hi()>=t1)return;
        int bucket=(int)((event.lo()-t0)/SECOND);
        if(event.hi()<t0+(bucket+1L)*SECOND)buckets[bucket]++;
    }
    private static List<Interval> merge(List<Interval> input){
        var sorted=new ArrayList<>(input);sorted.sort(Comparator.comparingLong(Interval::start));var result=new ArrayList<Interval>();
        for(var next:sorted){
            if(result.isEmpty()||result.get(result.size()-1).end()<next.start())result.add(next);
            else{var previous=result.remove(result.size()-1);result.add(new Interval(previous.start(),Math.max(previous.end(),next.end())));}
        }return result;
    }
}
