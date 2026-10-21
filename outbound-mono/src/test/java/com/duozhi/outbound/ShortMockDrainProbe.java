package com.duozhi.outbound;
import org.junit.jupiter.api.Test;
/** Explicit opt-in diagnostic. Lifecycle assertions do not relax MockDialRate500Probe's capacity gate. */
class ShortMockDrainProbe {
    @Test void stopAdmissionThenDrainTheFixedSyntheticCohort()throws Exception {
        new MockDialRate500Probe().run(false,true);
    }
}
