package top.chende.coursemind.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.runtime.AtomicFiles;
import top.chende.coursemind.runtime.RuntimePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 共享 collector 配置（%LOCALAPPDATA%\CourseMind\config.json）。
 *
 * <p>该文件同时被 VS Code 扩展与 collector 使用，IDEA 插件只允许改自己拥有的键
 * （server_url、server_id、identity_file、upload_key、classroom），其余字段逐字保留。
 * 写入采用"读-校验-原子替换"：若期间文件被其他进程修改则放弃写入并提示重试。
 */
public final class SharedConfigStore {

    private final RuntimePaths paths;

    public SharedConfigStore(@NotNull RuntimePaths paths) {
        this.paths = paths;
    }

    public @NotNull Path configFile() {
        return paths.configFile();
    }

    public @NotNull JsonObject read() {
        byte[] raw = readBytes();
        return raw == null ? new JsonObject() : parse(raw);
    }

    public @NotNull Optional<String> sharedServerUrl() {
        JsonObject json = read();
        JsonElement element = json.get("server_url");
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }
        String value = element.getAsString().trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(ServerUrl.normalize(value));
    }

    public boolean hasUploadKey() {
        JsonObject json = read();
        JsonElement element = json.get("upload_key");
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                && !element.getAsString().isBlank();
    }

    public String sharedServerId() {
        JsonObject json = read();
        return string(json, "server_id", string(json, "server_url", ""));
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && !value.getAsString().isBlank() ? ServerUrl.normalize(value.getAsString()) : fallback;
    }

    /** 登录成功后写入服务器标识与上传凭据（collector 只用 upload_key 上传）。 */
    public void applyLogin(@NotNull String serverUrl, @NotNull String uploadKey, @Nullable String classroom) {
        applyLogin(serverUrl, serverUrl, uploadKey, classroom);
    }

    public void applyLogin(String effectiveUrl, String serverId, String uploadKey, String classroom) {
        if (uploadKey.isBlank()) {
            throw new CourseMindException("服务端未返回上传凭据（upload_key），请联系管理员检查服务端配置");
        }
        mutate(json -> {
            json.addProperty("server_url", ServerUrl.normalize(effectiveUrl));
            json.addProperty("server_id", ServerUrl.normalize(serverId));
            fillPathIfAbsent(json, "identity_file", paths.identityFile().toString());
            fillPathIfAbsent(json, "cache_dir", paths.cacheDir().toString());
            json.addProperty("upload_key", uploadKey);
            if (classroom != null && !classroom.isBlank() && !json.has("classroom")) {
                json.addProperty("classroom", classroom);
            }
        });
    }

    /** 退出登录时只移除上传凭据，保留服务器与采集器调优参数。 */
    public void clearUploadKey() {
        if (!Files.isRegularFile(paths.configFile())) {
            return;
        }
        mutate(json -> json.remove("upload_key"));
    }

    /** 仅同步服务器字段（切换服务器后使用）。 */
    public void syncServer(@NotNull String serverUrl) {
        syncServer(serverUrl, serverUrl);
    }

    public void syncServer(String effectiveUrl, String serverId) {
        mutate(json -> {
            String previousId = string(json, "server_id", string(json, "server_url", ""));
            if (!ServerUrl.normalize(serverId).equals(previousId)) json.remove("upload_key");
            json.addProperty("server_url", ServerUrl.normalize(effectiveUrl));
            json.addProperty("server_id", ServerUrl.normalize(serverId));
            fillPathIfAbsent(json, "identity_file", paths.identityFile().toString());
            fillPathIfAbsent(json, "cache_dir", paths.cacheDir().toString());
        });
    }

    /** 已有路径保持原值，避免覆盖管理员/扩展配置的自定义目录。 */
    private static void fillPathIfAbsent(@NotNull JsonObject json, @NotNull String key, @NotNull String value) {
        JsonElement existing = json.get(key);
        if (existing == null || !existing.isJsonPrimitive() || existing.getAsString().isBlank()) {
            json.addProperty(key, value);
        }
    }

    private void mutate(@NotNull Consumer<JsonObject> mutator) {
        byte[] before = readBytes();
        JsonObject json = before == null ? defaults() : parse(before);
        mutator.accept(json);
        byte[] after = readBytes();
        if (!Arrays.equals(before, after)) {
            throw new CourseMindException("共享配置 config.json 刚被其他 IDE 修改，本次未写入，请重试");
        }
        String content = new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n";
        try {
            AtomicFiles.write(paths.configFile(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CourseMindException("无法写入共享配置 config.json，请检查目录权限", e);
        }
    }

    private @Nullable byte[] readBytes() {
        Path file = paths.configFile();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CourseMindException("无法读取共享配置 config.json", e);
        }
    }

    private @NotNull JsonObject parse(byte @NotNull [] raw) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new CourseMindException("共享配置 config.json 格式不正确，请检查后重试");
            }
            return parsed.getAsJsonObject();
        } catch (CourseMindException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CourseMindException("共享配置 config.json 不是合法 JSON，已停止写入以免覆盖", e);
        }
    }

    /** collector 尚未部署时的最小配置，字段默认值与 VS Code 扩展一致。 */
    private @NotNull JsonObject defaults() {
        JsonObject json = new JsonObject();
        json.addProperty("cache_dir", paths.cacheDir().toString());
        json.addProperty("identity_file", paths.identityFile().toString());
        json.addProperty("cache_max_mb", 10);
        json.addProperty("cache_max_records", 5000);
        json.addProperty("max_retries", 10);
        json.addProperty("retry_backoff_base_s", 1);
        json.addProperty("retry_backoff_max_s", 60);
        json.addProperty("request_timeout_s", 3);
        json.addProperty("diagnostic_log", true);
        json.addProperty("diagnostic_log_max_mb", 5);
        json.addProperty("diagnostic_include_raw", false);
        return json;
    }
}
