package com.duozhi.outbound.call.domain;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.call.domain.enums.CallStatus;
import com.duozhi.outbound.common.exception.ApiError;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class CallStateTransitionsTest {
    static Stream<Arguments> terminalPairs() {
        return Stream.of("completed", "failed", "no_answer", "busy", "voicemail")
                .flatMap(
                        current ->
                                Arrays.stream(CallStatus.values())
                                        .map(target -> Arguments.of(current, target.value())));
    }

    @ParameterizedTest
    @MethodSource("terminalPairs")
    void everyTerminalRejectsEveryOtherStateAndAllowsSameStateCdr(String current, String target) {
        if (current.equals(target))
            assertThatCode(() -> CallStateTransitions.require(current, target))
                    .doesNotThrowAnyException();
        else
            assertThatThrownBy(() -> CallStateTransitions.require(current, target))
                    .isInstanceOfSatisfying(
                            ApiError.class, error -> assertThat(error.status).isEqualTo(409));
    }

    @ParameterizedTest
    @CsvSource({
        "queued,created",
        "dialing,queued",
        "answered,dialing",
        "in_ai,answered",
        "waiting_human,in_ai",
        "waiting_human,handoff_transferring",
        "in_human,handoff_transferring"
    })
    void backwardsEdgesAreRejected(String current, String target) {
        assertThatThrownBy(() -> CallStateTransitions.require(current, target))
                .isInstanceOf(ApiError.class);
    }

    @ParameterizedTest
    @CsvSource({
        "created,queued",
        "queued,dialing",
        "dialing,answered",
        "answered,in_ai",
        "in_ai,waiting_human",
        "handoff_transferring,waiting_human",
        "handoff_transferring,in_human",
        "in_human,completed",
        "dialing,busy",
        "dialing,no_answer",
        "answered,voicemail",
        "queued,failed"
    })
    void normalAndEarlyTerminalEdgesRemainAvailable(String current, String target) {
        assertThatCode(() -> CallStateTransitions.require(current, target))
                .doesNotThrowAnyException();
    }
}
