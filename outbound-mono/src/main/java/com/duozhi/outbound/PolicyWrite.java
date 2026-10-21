package com.duozhi.outbound;

import java.lang.annotation.*;
import org.springframework.transaction.annotation.Transactional;

/** Compatibility policy mutation: the platform lock must precede JDBC checkout. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Transactional
public @interface PolicyWrite { }
