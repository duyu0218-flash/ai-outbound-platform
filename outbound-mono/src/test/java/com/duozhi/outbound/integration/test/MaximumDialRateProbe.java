package com.duozhi.outbound.integration.test;

import org.junit.jupiter.api.Test;

/** Explicit opt-in saturation diagnostic. A successful run never qualifies the 500 CPS gate. */
class MaximumDialRateProbe {
    @Test
    void measureCommittedAcknowledgementsThenReconcileAndDrain() throws Exception {
        new MockDialRate500Probe().run(false, true, true);
    }
}
