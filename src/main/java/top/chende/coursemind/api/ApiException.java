package top.chende.coursemind.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端访问异常：{@code message} 可直接展示给用户，已按状态码转换为中文建议。
 */
public final class ApiException extends RuntimeException {

    private final int status;
    private final boolean authenticationFailure;

    public ApiException(@NotNull String message, int status, boolean authenticationFailure, @Nullable Throwable cause) {
        super(message, cause);
        this.status = status;
        this.authenticationFailure = authenticationFailure;
    }

    /** HTTP 状态码；网络层失败时为 0。 */
    public int status() {
        return status;
    }

    /** 401/403：凭据无效或过期，调用方应停止使用当前身份。 */
    public boolean isAuthenticationFailure() {
        return authenticationFailure;
    }
}
