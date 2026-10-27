package com.duozhi.outbound.voiceai.controller;

import com.duozhi.outbound.voiceai.service.BailianTrials;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class BailianToolController {
    private final BailianTrials trials;

    public BailianToolController(BailianTrials trials) {
        this.trials = trials;
    }

    @PostMapping("/api/v1/webhooks/bailian/trial-tool")
    public Object tool(
            @RequestHeader(value = "X-API-KEY", defaultValue = "")
            String key,
            @RequestBody
            Map<String, Object> body) {
        return trials.tool(key, body);
    }
}
