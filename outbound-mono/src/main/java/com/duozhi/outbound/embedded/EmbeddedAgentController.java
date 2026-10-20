package com.duozhi.outbound.embedded;
import java.util.*;
import org.springframework.web.bind.annotation.*;
/** Legacy agent operations, served by the same application process and credential checks. */
@RestController
public class EmbeddedAgentController {
    private final EmbeddedAgent agent;
    public EmbeddedAgentController(EmbeddedAgent agent){this.agent=agent;}
    @PostMapping("/agent/start") Map<String,Object> start(@RequestBody Map<String,Object> payload,@RequestHeader(value="Authorization",required=false) String auth){return agent.start(payload,auth);}
    @PostMapping("/agent/turn") Map<String,Object> turn(@RequestBody Map<String,Object> payload,@RequestHeader(value="Authorization",required=false) String auth)throws Exception{return agent.turn(payload,auth);}
}
