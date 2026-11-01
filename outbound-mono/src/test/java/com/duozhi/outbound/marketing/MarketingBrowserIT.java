package com.duozhi.outbound.marketing;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** Browser operations against real P1-P4 HTTP controllers with all execution workers disabled. */
class MarketingBrowserIT extends MarketingTestBase {
    @Value("${local.server.port}")
    int port;

    @Test
    void marketingEntrancesFormsPersistenceLifecycleAndSessionBoundariesWork() throws Exception {
        Path log = Path.of("target/marketing-browser.log");
        var builder =
                new ProcessBuilder("node", "frontend/tests/marketing-acceptance.mjs")
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        builder.environment().put("MONO_BROWSER_URL", "http://127.0.0.1:" + port);
        builder.environment().put("MONO_BROWSER_ADMIN_TOKEN", admin);
        builder.environment().put("MONO_BROWSER_AGENT_TOKEN", agent);
        Process process = builder.start();
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("marketing browser timeout; %s", Files.readString(log)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        assertThat(Files.exists(Path.of("target/marketing-browser-result.json"))).isTrue();
    }
}
