package com.duozhi.outbound.auth.service;

import com.duozhi.outbound.common.exception.ApiError;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static String require(String password) {
        if (password.length() < 8
                || password.length() > 20
                || !password.matches("(?s).*[a-z].*")
                || !password.matches("(?s).*[A-Z].*")
                || !password.matches("(?s).*[0-9].*")
                || !password.matches("(?s).*[^a-zA-Z0-9\\s].*"))
            throw new ApiError(422, "新密码须为8至20位，包含大写、小写字母、数字及特殊符号");
        return password;
    }
}
