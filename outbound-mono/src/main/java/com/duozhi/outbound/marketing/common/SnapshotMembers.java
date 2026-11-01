package com.duozhi.outbound.marketing.common;

import java.util.List;

/** Read-only view of a frozen segment snapshot. Implemented by the dataset module (P1). */
public interface SnapshotMembers {
    record Member(
            long sequence, String phoneHash, String phoneCipher, Long contactId, String group) {}

    /** Members after {@code afterSequence} in sequence order; group is "test" or "control". */
    List<Member> page(long tenant, String snapshotId, long afterSequence, int limit);

    long size(long tenant, String snapshotId);
}
