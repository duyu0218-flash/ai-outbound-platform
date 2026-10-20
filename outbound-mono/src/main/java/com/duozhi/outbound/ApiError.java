package com.duozhi.outbound;

public class ApiError extends RuntimeException {
    public final int status;
    public ApiError(int status, String detail) { super(detail); this.status = status; }
}
