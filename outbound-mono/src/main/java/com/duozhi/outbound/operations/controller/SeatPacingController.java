package com.duozhi.outbound.operations.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.operations.service.SeatPacing;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ops/seats/pacing")
public class SeatPacingController {
    private final SeatPacing pacing;

    public SeatPacingController(SeatPacing pacing) {
        this.pacing = pacing;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        i.admin("operations:write");
        return i;
    }

    @GetMapping
    public Object list(HttpServletRequest r) {
        return pacing.list(actor(r).tenant());
    }

    @PutMapping("/{team}")
    public Object save(
            @PathVariable
            long team, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        return pacing.save(actor(r), team, b);
    }
}
