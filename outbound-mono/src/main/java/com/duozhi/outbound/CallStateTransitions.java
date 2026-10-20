package com.duozhi.outbound;
import java.util.*;
import com.duozhi.outbound.entity.enums.CallStatus;
/** The explicitly required 13-state monotonic call lifecycle; repeats may still update CDR evidence. */
public final class CallStateTransitions {
    private CallStateTransitions(){}
    private static final List<String> ACTIVE=List.of("created","queued","dialing","answered","in_ai","waiting_human","in_human");
    public static int rank(String state){CallStatus.fromValue(state);return CallLifecycle.TERMINAL.contains(state)?ACTIVE.size():state.equals("handoff_transferring")?4:ACTIVE.indexOf(state);}
    public static boolean allows(String current,String target){return current.equals(target)||!CallLifecycle.TERMINAL.contains(current)&&rank(target)>=rank(current);}
    public static void require(String current,String target){if(!allows(current,target))throw new ApiError(409,CallLifecycle.TERMINAL.contains(current)?"terminal call cannot be reopened":"call state cannot move backwards");}
}
