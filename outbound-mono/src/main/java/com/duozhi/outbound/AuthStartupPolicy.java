package com.duozhi.outbound;
import org.springframework.stereotype.Component;
@Component
public class AuthStartupPolicy {
    public AuthStartupPolicy(Config config){
        ProductionConfiguration.validate(config);
        if(config.get("JWT_SECRET","").length()<32)throw new IllegalStateException("JWT_SECRET must contain at least 32 characters");
        if(!"HS256".equals(config.get("JWT_ALGORITHM","HS256")))throw new IllegalStateException("Only HS256 compatibility is implemented");
    }
}
