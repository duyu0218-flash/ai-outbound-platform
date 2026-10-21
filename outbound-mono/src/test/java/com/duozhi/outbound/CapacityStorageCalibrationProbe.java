package com.duozhi.outbound;

import org.junit.jupiter.api.Test;

/** Explicit opt-in finite-cohort storage calibration; never a capacity pass. */
class CapacityStorageCalibrationProbe {
    @Test void measureOnlyAfterTheWholeFiniteSyntheticLifecycleHasDrained()throws Exception{
        new MockDialRate500Probe().run(true);
    }
}
