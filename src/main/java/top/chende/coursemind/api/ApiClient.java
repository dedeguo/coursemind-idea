package top.chende.coursemind.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.chende.coursemind.config.ServerUrl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 服务端访问层：只做请求、解析与错误映射，不触碰 UI 或持久化。
 *
 * <p>接口来自 {@code coursemind-collect} 服务端实测：{@code /health}、{@code /api/v1/student/login}、
 * {@code /api/v1/student/me}；响应统一为 {@code {code, message, data}}。
 */
public final class ApiClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final HttpClient http;

    public ApiClient(@NotNull String serverUrl) {
        this.baseUrl = ServerUrl.normalize(serverUrl);
        // 强制 HTTP/1.1：实测服务端在 HTTP/2 下收不到请求体，FastAPI 返回 422（loc=body, Field required）。
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public @NotNull HealthResult health() {
        JsonObject data = request("GET", "/health", null, null);
        String status = string(data, "status");
        return new HealthResult("healthy".equalsIgnoreCase(status), string(data, "version"));
    }

    public @NotNull LoginResult login(@NotNull String studentNo, @NotNull String password) {
        JsonObject body = new JsonObject();
        body.addProperty("student_no", studentNo.trim());
        body.addProperty("password", password);
        JsonObject data = request("POST", "/api/v1/student/login", null, body);
        String token = string(data, "access_token");
        String uploadKey = string(data, "upload_key");
        if (token == null || token.isBlank()) {
            throw new ApiException("服务端未返回登录凭据，请联系管理员检查服务端版本", 0, false, null);
        }
        return new LoginResult(token, uploadKey == null ? "" : uploadKey, profileOf(data));
    }

    public @NotNull StudentProfile me(@NotNull String accessToken) {
        JsonObject data = request("GET", "/api/v1/student/me", accessToken, null);
        return profileOf(data);
    }

    public @NotNull String changePassword(String accessToken, String oldPassword, String newPassword) {
        JsonObject body = new JsonObject();
        body.addProperty("old_password", oldPassword);
        body.addProperty("new_password", newPassword);
        JsonObject data = request("POST", "/api/v1/student/change-password", accessToken, body);
        String token = string(data, "access_token");
        if (token == null || token.isBlank()) {
            throw new ApiException("密码可能已修改，但服务端未返回新凭据，请重新登录", 0, false, null);
        }
        return token;
    }

    private @NotNull StudentProfile profileOf(@NotNull JsonObject data) {
        int studentId = intValue(data, "student_id");
        String studentNo = string(data, "student_no");
        String name = string(data, "name");
        String className = string(data, "class_name");
        String classroom = string(data, "classroom");
        if (studentId <= 0 || studentNo == null || name == null) {
            throw new ApiException("服务端返回的身份信息不完整，请联系管理员", 0, false, null);
        }
        return new StudentProfile(studentId, studentNo, name, string(data, "gender"),
                className == null ? "" : className, classroom == null ? "" : classroom);
    }

    private @NotNull JsonObject request(@NotNull String method,
                                        @NotNull String path,
                                        @Nullable String token,
                                        @Nullable JsonObject body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        }

        HttpResponse<byte[]> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ApiException("连接服务器超时，请检查网络或服务器地址", 0, false, e);
        } catch (IOException e) {
            throw new ApiException("无法连接服务器，请检查服务器地址与网络", 0, false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("请求已取消", 0, false, e);
        }

        String text = new String(response.body(), StandardCharsets.UTF_8);
        JsonObject envelope = parseObject(text);
        int status = response.statusCode();
        int code = envelope == null ? Integer.MIN_VALUE : intValue(envelope, "code");
        String message = envelope == null ? null : string(envelope, "message");

        if (status / 100 != 2 || code != 0) {
            throw new ApiException(mapError(status, message, validationDetail(text)),
                    status, status == 401 || status == 403, null);
        }
        JsonElement data = envelope.get("data");
        return data != null && data.isJsonObject() ? data.getAsJsonObject() : new JsonObject();
    }

    private static @Nullable JsonObject parseObject(@NotNull String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 校验失败详情：本服务端的统一包络为 {@code data.errors[]}（FastAPI 原始结构），
     * 也兼容默认的顶层 {@code detail[]}。返回"字段（原因）"形式，便于定位。
     */
    static @Nullable String validationDetail(@NotNull String text) {
        JsonObject object = parseObject(text);
        if (object == null) {
            return null;
        }
        JsonArray errors = null;
        JsonElement data = object.get("data");
        if (data != null && data.isJsonObject()) {
            JsonElement nested = data.getAsJsonObject().get("errors");
            if (nested != null && nested.isJsonArray()) {
                errors = nested.getAsJsonArray();
            }
        }
        if (errors == null) {
            JsonElement detail = object.get("detail");
            if (detail != null && detail.isJsonArray()) {
                errors = detail.getAsJsonArray();
            }
        }
        if (errors == null || errors.isEmpty() || !errors.get(0).isJsonObject()) {
            return null;
        }
        JsonObject first = errors.get(0).getAsJsonObject();
        String message = string(first, "msg");
        String field = fieldOf(first);
        if (message == null) {
            return field;
        }
        return field == null ? message : field + "（" + message + "）";
    }

    private static @Nullable String fieldOf(@NotNull JsonObject error) {
        JsonElement loc = error.get("loc");
        if (loc == null || !loc.isJsonArray()) {
            return null;
        }
        StringBuilder field = new StringBuilder();
        for (JsonElement item : loc.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) {
                continue;
            }
            String part = item.getAsString();
            if ("body".equals(part)) {
                continue;
            }
            if (field.length() > 0) {
                field.append('.');
            }
            field.append(part);
        }
        return field.length() == 0 ? null : field.toString();
    }

    static @NotNull String mapError(int status, @Nullable String serverMessage, @Nullable String validationDetail) {
        if (status == 0) {
            return serverMessage != null ? serverMessage : "无法连接服务器，请检查服务器地址与网络";
        }
        if (status == 401) {
            return serverMessage != null && !serverMessage.isBlank()
                    ? serverMessage
                    : "登录状态无效或已过期，请重新登录";
        }
        if (status == 403) {
            return "没有访问权限，请确认账号状态或联系管理员";
        }
        if (status == 404) {
            return "服务器未提供该接口，请确认服务端版本";
        }
        if (status == 422) {
            return validationDetail != null
                    ? "请求参数不正确：" + validationDetail
                    : "请求参数不正确，请检查输入";
        }
        if (status == 429) {
            return "请求过于频繁，请稍后再试";
        }
        if (status >= 500) {
            return "服务器异常（HTTP " + status + "），请稍后重试";
        }
        return serverMessage != null && !serverMessage.isBlank()
                ? serverMessage
                : "请求失败（HTTP " + status + "）";
    }

    private static @Nullable String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    private static int intValue(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        return element.getAsInt();
    }

    public record HealthResult(boolean healthy, @Nullable String version) {
    }

    public record LoginResult(@NotNull String accessToken, @NotNull String uploadKey, @NotNull StudentProfile profile) {
    }

    public record StudentProfile(int studentId,
                                 @NotNull String studentNo,
                                 @NotNull String name,
                                 @Nullable String gender,
                                 @NotNull String className,
                                 @NotNull String classroom) {
    }
}
