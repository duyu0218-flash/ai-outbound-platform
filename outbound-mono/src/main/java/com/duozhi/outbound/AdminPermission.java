package com.duozhi.outbound;
import java.lang.annotation.*;
@Target({ElementType.METHOD,ElementType.TYPE}) @Retention(RetentionPolicy.RUNTIME)
public @interface AdminPermission { String value(); boolean supervisor() default false; }
