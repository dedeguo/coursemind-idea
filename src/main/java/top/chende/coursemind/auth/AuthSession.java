package top.chende.coursemind.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;

/**
 * auth.dat 解密后的共享登录会话，字段与 VS Code 扩展的 {@code SharedAuthSession} 完全一致。
 *
 * <p>该结构是 IDEA 与 VS Code/Trae 扩展之间的身份契约，改动必须两边同步。
 */
public final class AuthSession {

    public static final int SCHEMA_VERSION = 2;

    private final String sessionId;
    private final String serverId;
    private final String accessToken;
    private final Profile profile;
    private final String updatedAt;
    private final String environmentId;
    private final Integer accountId;

    public AuthSession(@NotNull String sessionId,
                       @NotNull String serverId,
                       @NotNull String accessToken,
                       @NotNull Profile profile,
                       @NotNull String updatedAt) {
        this(sessionId, serverId, accessToken, profile, updatedAt, null, null);
    }

    public AuthSession(String sessionId, String serverId, String accessToken, Profile profile,
                       String updatedAt, String environmentId, Integer accountId) {
        this.sessionId = sessionId;
        this.serverId = serverId;
        this.accessToken = accessToken;
        this.profile = profile;
        this.updatedAt = updatedAt;
        this.environmentId = environmentId;
        this.accountId = accountId;
    }
    public @Nullable String environmentId() { return environmentId; }
    public @Nullable Integer accountId() { return accountId; }

    public @NotNull String sessionId() {
        return sessionId;
    }

    public @NotNull String serverId() {
        return serverId;
    }

    public @NotNull String accessToken() {
        return accessToken;
    }

    public @NotNull Profile profile() {
        return profile;
    }

    public @NotNull String updatedAt() {
        return updatedAt;
    }

    public @NotNull Optional<Instant> expiresAt() {
        return Jwt.expiresAt(accessToken);
    }

    public @NotNull JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", environmentId == null ? 1 : SCHEMA_VERSION);
        if (environmentId != null) {
            root.addProperty("environmentId", environmentId);
            root.addProperty("accountId", accountId);
        }
        root.addProperty("sessionId", sessionId);
        root.addProperty("serverId", serverId);
        root.addProperty("accessToken", accessToken);
        root.add("profile", profile.toJson());
        root.addProperty("updatedAt", updatedAt);
        return root;
    }

    /**
     * 严格解析：结构不合法返回 empty，由调用方按"损坏"处理，绝不静默修复或覆盖。
     */
    public static @NotNull Optional<AuthSession> fromJson(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject root = element.getAsJsonObject();
        if (!hasInt(root, "schemaVersion") || (root.get("schemaVersion").getAsInt() != 1 && root.get("schemaVersion").getAsInt() != SCHEMA_VERSION)) {
            return Optional.empty();
        }
        String sessionId = string(root, "sessionId");
        String serverId = string(root, "serverId");
        String accessToken = string(root, "accessToken");
        String updatedAt = string(root, "updatedAt");
        Optional<Profile> profile = Profile.fromJson(root.get("profile"));
        if (sessionId == null || serverId == null || accessToken == null || updatedAt == null || profile.isEmpty()) {
            return Optional.empty();
        }
        String environmentId = string(root, "environmentId");
        Integer accountId = hasInt(root, "accountId") ? root.get("accountId").getAsInt() : null;
        if (root.get("schemaVersion").getAsInt() == 2 && (environmentId == null || accountId == null || accountId <= 0)) return Optional.empty();
        if (root.get("schemaVersion").getAsInt() == 1 && (root.has("environmentId") || root.has("accountId"))) return Optional.empty();
        return Optional.of(new AuthSession(sessionId, serverId, accessToken, profile.get(), updatedAt, environmentId, accountId));
    }

    private static @Nullable String string(JsonObject root, String name) {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return value.isBlank() ? null : value;
    }

    private static boolean hasInt(JsonObject root, String name) {
        JsonElement element = root.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    /** 学生身份（对应扩展的 StudentIdentity）。 */
    public static final class Profile {

        private final int studentId;
        private final String studentNo;
        private final String name;
        private final String gender;
        private final String className;
        private final String classroom;
        private final Integer projectId;

        public Profile(int studentId,
                       @NotNull String studentNo,
                       @NotNull String name,
                       @Nullable String gender,
                       @NotNull String className,
                       @NotNull String classroom,
                       @Nullable Integer projectId) {
            this.studentId = studentId;
            this.studentNo = studentNo;
            this.name = name;
            this.gender = gender;
            this.className = className;
            this.classroom = classroom;
            this.projectId = projectId;
        }

        public int studentId() {
            return studentId;
        }

        public @NotNull String studentNo() {
            return studentNo;
        }

        public @NotNull String name() {
            return name;
        }

        public @Nullable String gender() {
            return gender;
        }

        public @NotNull String className() {
            return className;
        }

        public @NotNull String classroom() {
            return classroom;
        }

        public @Nullable Integer projectId() {
            return projectId;
        }

        public @NotNull JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("studentId", studentId);
            json.addProperty("studentNo", studentNo);
            json.addProperty("name", name);
            if (gender != null) {
                json.addProperty("gender", gender);
            }
            json.addProperty("className", className);
            json.addProperty("classroom", classroom);
            if (projectId != null) {
                json.addProperty("projectId", projectId);
            }
            return json;
        }

        static @NotNull Optional<Profile> fromJson(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject json = element.getAsJsonObject();
            if (!hasInt(json, "studentId")) {
                return Optional.empty();
            }
            int studentId = json.get("studentId").getAsInt();
            String studentNo = string(json, "studentNo");
            String name = string(json, "name");
            String className = plainString(json, "className");
            String classroom = plainString(json, "classroom");
            if (studentId <= 0 || studentNo == null || name == null || className == null || classroom == null) {
                return Optional.empty();
            }
            String gender = string(json, "gender");
            Integer projectId = hasInt(json, "projectId") ? json.get("projectId").getAsInt() : null;
            return Optional.of(new Profile(studentId, studentNo, name, gender, className, classroom, projectId));
        }
    }

    private static String plainString(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
}
