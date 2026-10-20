package top.chende.coursemind.collector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import top.chende.coursemind.CourseMindException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 随插件分发的 collector 产物：只读 {@code /runtime/win32-x64/manifest.json} 与 {@code collector.exe}。
 *
 * <p>manifest 中的 sha256 必须与二进制一致，部署前用它校验内置包完整性（COL-04）。
 */
public final class BundledCollector {

    private static final String ROOT = "/runtime/win32-x64/";
    private static final String MANIFEST = ROOT + "manifest.json";
    private static final String BINARY = ROOT + "collector.exe";

    private final String version;
    private final String sha256;
    private final long size;
    private final String builtAt;

    private BundledCollector(String version, String sha256, long size, String builtAt) {
        this.version = version;
        this.sha256 = sha256;
        this.size = size;
        this.builtAt = builtAt;
    }

    /** 读取内置 manifest；资源缺失或字段不全时返回 empty，由调用方提示"本插件未包含采集器"。 */
    public static @NotNull Optional<BundledCollector> load() {
        try (InputStream stream = BundledCollector.class.getResourceAsStream(MANIFEST)) {
            if (stream == null) {
                return Optional.empty();
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            String version = get(json, "version");
            String sha256 = get(json, "sha256");
            if (version == null || sha256 == null) {
                return Optional.empty();
            }
            long size = json.has("size") && json.get("size").isJsonPrimitive() ? json.get("size").getAsLong() : 0L;
            return Optional.of(new BundledCollector(version, sha256, size, get(json, "built_at")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String get(JsonObject json, String name) {
        return json.has(name) && json.get(name).isJsonPrimitive() ? json.get(name).getAsString() : null;
    }

    public @NotNull String version() {
        return version;
    }

    public @NotNull String sha256() {
        return sha256;
    }

    public long size() {
        return size;
    }

    public @NotNull String builtAt() {
        return builtAt == null ? "" : builtAt;
    }

    /** 读取内置二进制内容；每次读取约 9 MB，调用方应在后台线程执行。 */
    public byte @NotNull [] bytes() {
        try (InputStream stream = BundledCollector.class.getResourceAsStream(BINARY)) {
            if (stream == null) {
                throw new CourseMindException("插件未包含 collector.exe，请重新安装完整插件包");
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new CourseMindException("读取插件内置采集器失败", e);
        }
    }
}
