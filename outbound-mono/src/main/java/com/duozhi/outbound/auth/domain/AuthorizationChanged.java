package com.duozhi.outbound.auth.domain;

/** Invalidates process-local audio authorization only after the database change commits. */
public record AuthorizationChanged(long tenant, long user) {}
