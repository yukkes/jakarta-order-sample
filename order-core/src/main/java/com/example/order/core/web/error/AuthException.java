package com.example.order.core.web.error;

public class AuthException extends RuntimeException {

    private final int status;

    public AuthException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static AuthException invalidCredentials() {
        return new AuthException(401, "ユーザー名またはパスワードが正しくありません");
    }
}
