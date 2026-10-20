package com.duozhi.outbound;
import java.util.*;

/** Initial-status facts loaded under partition/call locks for one transaction.
 * The existing lifecycle remains the only implementation of status transitions. */
final class CallStatusBatchScope implements AutoCloseable {
    private static final ThreadLocal<CallStatusBatchScope> CURRENT=new ThreadLocal<>();
    final Map<String,Map<String,Object>> calls=new HashMap<>(),usage=new HashMap<>(),events=new HashMap<>(),tasks=new HashMap<>(),receipts=new HashMap<>();
    private final CallStatusBatchScope previous=CURRENT.get();private final DialBatchScope settings=new DialBatchScope();
    CallStatusBatchScope(){CURRENT.set(this);}
    static CallStatusBatchScope current(){return CURRENT.get();}
    @Override public void close(){settings.close();if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
