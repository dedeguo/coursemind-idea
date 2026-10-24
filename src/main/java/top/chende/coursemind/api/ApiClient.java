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
import java.net.URLEncoder;
import java.util.List;
import top.chende.coursemind.education.EducationData;

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

    public top.chende.coursemind.stats.StudentStats myStats(String accessToken) {
        return top.chende.coursemind.stats.StudentStats.parse(request("GET",
                "/api/v1/student/me/stats?range=today&recent_limit=5", accessToken, null));
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
        String environmentId = string(data, "environment_id");
        Integer accountId = data.has("account_id") ? intValue(data, "account_id") : null;
        if ((environmentId == null) != (accountId == null) || (accountId != null && accountId <= 0))
            throw new ApiException("教学身份缺少账号或环境标识，请重新登录", 0, true, null);
        return new StudentProfile(studentId, studentNo, name, string(data, "gender"),
                className == null ? "" : className, classroom == null ? "" : classroom, environmentId, accountId);
    }

    public EducationData.Page<EducationData.Group> teachingGroups(String token, int page) {
        return EducationData.page(requestData("GET", "/api/v1/education/teaching-groups?page_size=100&page=" + page, token, null), EducationData::group);
    }
    public EducationData.Page<EducationData.Task> tasks(String token, int page, Integer groupId) {
        return EducationData.page(requestData("GET", "/api/v1/education/tasks?page_size=20&page=" + page
                + (groupId == null ? "" : "&group_id=" + groupId), token, null), EducationData::task);
    }
    public EducationData.Task task(String token, int id) {
        return EducationData.task(request("GET", "/api/v1/education/tasks/" + id, token, null));
    }
    public EducationData.Page<EducationData.Session> sessions(String token, int page, Integer taskId, String query) {
        return EducationData.page(requestData("GET", "/api/v1/education/sessions?page_size=20&page=" + page
                + (taskId == null ? "" : "&task_id=" + taskId)
                + (query == null || query.isBlank() ? "" : "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)), token, null), EducationData::session);
    }
    public EducationData.Session session(String token, long id) {
        return EducationData.session(request("GET", "/api/v1/education/sessions/" + id, token, null));
    }
    public List<EducationData.Task> sessionTasks(String token, long id) {
        return EducationData.list(requestData("GET", "/api/v1/education/sessions/" + id + "/tasks", token, null), EducationData::task);
    }
    public List<EducationData.LinkResult> sessionLinks(String token, String action, List<EducationData.Pair> pairs) {
        if (!("add".equals(action) || "remove".equals(action)) || pairs.isEmpty() || pairs.size() > 200)
            throw new IllegalArgumentException("请选择 1–200 对会话与任务");
        JsonObject body = new JsonObject(); body.addProperty("action", action); JsonArray items = new JsonArray();
        for (var pair : pairs) {
            if (pair.taskId() <= 0 || pair.sessionId() <= 0) throw new IllegalArgumentException("无效的任务或会话 ID");
            JsonObject item = new JsonObject(); item.addProperty("task_id", pair.taskId()); item.addProperty("session_id", pair.sessionId()); items.add(item);
        }
        body.add("pairs", items);
        return EducationData.list(request("POST", "/api/v1/education/session-links/batch", token, body).get("results"), EducationData::result);
    }

    private @NotNull JsonObject request(String method, String path, String token, JsonObject body) {
        JsonElement result = requestData(method, path, token, body);
        return result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
    }

    private @NotNull JsonElement requestData(@NotNull String method,
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
            if (status == 404 && path.startsWith("/api/v1/education/"))
                throw new ApiException("对象不存在或无权访问，请刷新课程与会话", status, false, null);
            throw new ApiException(mapError(status, message, validationDetail(text)),
                    status, status == 401 || status == 403, null);
        }
        JsonElement data = envelope.get("data");
        return data == null ? com.google.gson.JsonNull.INSTANCE : data;
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
                                 @NotNull String classroom,
                                 @Nullable String environmentId,
                                 @Nullable Integer accountId) {
    }
}
