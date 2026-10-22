package com.duozhi.outbound;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RealtimeTaskPressureTest {
    @Test void controlDeadlinePressureFallsBeforeTheHardTwoSecondDeadline(){
        assertThat(RealtimeTaskPressure.fraction(Map.of("control_age_sec",.1,"ai_age_sec",0d,"dead",0d))).isEqualTo(1);
        assertThat(RealtimeTaskPressure.fraction(Map.of("control_age_sec",.3,"ai_age_sec",0d,"dead",0d))).isCloseTo(.5,within(1e-9));
        for(double age:new double[]{.5,2,Double.NaN,-1})assertThat(RealtimeTaskPressure.fraction(Map.of("control_age_sec",age,"ai_age_sec",0d,"dead",0d))).isZero();
    }
    @Test void deadOrMissingEvidenceStopsNewAdmissionsAndAiPressureAlsoLimitsThem(){
        assertThat(RealtimeTaskPressure.fraction(null)).isZero();assertThat(RealtimeTaskPressure.fraction(Map.of())).isZero();
        assertThat(RealtimeTaskPressure.fraction(Map.of("control_age_sec",0d,"ai_age_sec",0d,"dead",1d))).isZero();
        assertThat(RealtimeTaskPressure.fraction(Map.of("control_age_sec",0d,"ai_age_sec",1d,"dead",0d))).isEqualTo(.5);
        assertThat(RealtimeTaskPressure.fraction(Map.of("control_age_sec",0d,"ai_age_sec",1.5,"dead",0d))).isZero();
    }
}
