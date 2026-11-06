package com.duozhi.outbound.operations.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.operations.service.OperationalPush;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ops/benchmark/push")
public class OperationalPushController {
    private final OperationalPush push;

    public OperationalPushController(OperationalPush push) {
        this.push = push;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        i.admin("operations:write");
        return i;
    }

    @GetMapping("/{type}")
    public Object get(@PathVariable
    String type, HttpServletRequest r) {
        return push.read(actor(r).tenant(), type);
    }

    @GetMapping("/{type}/defaults")
    public Object defaults(@PathVariable
    String type, HttpServletRequest r) {
        actor(r);
        return OperationalPush.defaults();
    }

    @PutMapping("/{type}")
    public Object save(
            @PathVariable
            String type, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        return push.save(actor(r), type, b);
    }
}
