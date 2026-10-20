package top.chende.coursemind.collector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.runtime.RuntimePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 共享 collector 的检测、版本仲裁与部署。
 *
 * <p>仲裁原则（COL-09/COL-10）：本机版本高于插件自带版本时绝不覆盖；同版本但哈希不同视为冲突，
 * 交人工处理；只有缺失或本机更低时才写入，并在写入前备份旧文件。
 */
public final class CollectorService {

    public enum Decision {
        /** 本机未安装，可部署。 */
        DEPLOY,
        /** 本机版本低于内置版本，可升级。 */
        UPGRADE,
        /** 版本与哈希都一致，无需操作。 */
        UP_TO_DATE,
        /** 本机版本更高，保留现有版本。 */
        BLOCKED_NEWER,
        /** 版本相同但哈希不同，不覆盖。 */
        CONFLICT,
        /** 非 Windows 或非 x64。 */
        UNSUPPORTED,
        /** 插件未包含采集器产物。 */
        NO_BUNDLED
    }

    public record State(boolean osSupported,
                        boolean bundledAvailable,
                        @NotNull String bundledVersion,
                        boolean deployed,
                        @Nullable String localVersion,
                        @Nullable String localSha256,
                        @NotNull Path collectorPath,
                        boolean writable,
                        @NotNull Decision decision,
                        @NotNull String message) {

        public boolean canApply() {
            return decision == Decision.DEPLOY || decision == Decision.UPGRADE;
        }
    }

    public record DeployResult(@NotNull State state,
                               boolean changed,
                               @Nullable Path backup,
                               @NotNull String selfCheckMessage) {
    }

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.systemDefault());

    private final RuntimePaths paths;
    private final BundledCollector bundled;

    public CollectorService(@NotNull RuntimePaths paths) {
        this.paths = paths;
        this.bundled = BundledCollector.load().orElse(null);
    }

    public @NotNull State inspect() {
        boolean supported = isSupportedOs();
        boolean bundledAvailable = bundled != null;
        String bundledVersion = bundled == null ? "" : bundled.version();
        Path exe = paths.collectorExe();
        boolean deployed = Files.isRegularFile(exe);
        String localVersion = deployed ? readLocalVersion() : null;
        String localSha = deployed ? sha256OrNull(exe) : null;

        Decision decision;
        String message;
        if (!supported) {
            decision = Decision.UNSUPPORTED;
            message = "当前系统不是 Windows x64，首版仅支持 Windows";
        } else if (!bundledAvailable) {
            decision = Decision.NO_BUNDLED;
            message = "插件未包含采集器（manifest 缺失）";
        } else if (!deployed) {
            decision = Decision.DEPLOY;
            message = "本机未安装采集器，可部署 " + bundledVersion;
        } else {
            int compared = compareVersions(localVersion, bundledVersion);
            if (compared > 0) {
                decision = Decision.BLOCKED_NEWER;
                message = "本机版本 " + localVersion + " 高于插件自带 " + bundledVersion + "，保留现有版本";
            } else if (compared < 0) {
                decision = Decision.UPGRADE;
                message = "本机版本 " + localVersion + " 低于插件自带 " + bundledVersion + "，可升级";
            } else if (localSha != null && bundled != null && localSha.equalsIgnoreCase(bundled.sha256())) {
                decision = Decision.UP_TO_DATE;
                message = "已是最新版本 " + localVersion;
            } else {
                decision = Decision.CONFLICT;
                message = "版本号相同但文件哈希不同，已停止写入，请人工确认";
            }
        }
        return new State(supported, bundledAvailable, bundledVersion, deployed, localVersion, localSha,
                exe, isWritable(paths.root()), decision, message);
    }

    /** 纯函数：版本仲裁规则，便于单元测试。 */
    static @NotNull Decision decide(boolean deployed,
                                    @Nullable String localVersion,
                                    @Nullable String localSha256,
                                    @NotNull String bundledVersion,
                                    @NotNull String bundledSha256) {
        if (!deployed) {
            return Decision.DEPLOY;
        }
        int compared = compareVersions(localVersion, bundledVersion);
        if (compared > 0) {
            return Decision.BLOCKED_NEWER;
        }
        if (compared < 0) {
            return Decision.UPGRADE;
        }
        return localSha256 != null && localSha256.equalsIgnoreCase(bundledSha256)
                ? Decision.UP_TO_DATE
                : Decision.CONFLICT;
    }

    /** 语义化比较：按数字段比较，非数字段按 0 处理；a>b 返回正数。 */
    static int compareVersions(@Nullable String a, @Nullable String b) {
        String[] left = (a == null ? "" : a).split("\\.");
        String[] right = (b == null ? "" : b).split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = i < left.length ? number(left[i]) : 0;
            int r = i < right.length ? number(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int number(String value) {
        String digits = value.replaceAll("[^0-9].*$", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    /** 部署或升级；不做任何"降级"写入。 */
    public @NotNull DeployResult deploy() {
        State state = inspect();
        if (!state.canApply()) {
            return new DeployResult(state, false, null, "无需部署：" + state.message());
        }
        BundledCollector source = bundled;
        if (source == null) {
            throw new CourseMindException("插件未包含采集器，无法部署");
        }
        byte[] binary = source.bytes();
        String actual = sha256(binary);
        if (!actual.equalsIgnoreCase(source.sha256())) {
            throw new CourseMindException("内置采集器校验失败（哈希不匹配），已停止部署");
        }

        Path exe = paths.collectorExe();
        Path backup = null;
        try {
            Files.createDirectories(paths.root());
            if (Files.isRegularFile(exe)) {
                backup = backupExisting(exe);
            }
            Path temporary = paths.root().resolve("collector.exe." + Instant.now().toEpochMilli() + ".tmp");
            Files.write(temporary, binary);
            try {
                Files.move(temporary, exe, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            writeVersionFile(source);
        } catch (AccessDeniedException e) {
            throw new CourseMindException("没有写入权限，无法部署采集器：" + paths.root(), e);
        } catch (IOException e) {
            throw new CourseMindException("采集器文件被占用或写入失败，请关闭相关进程后重试", e);
        }
        pruneBackups();
        String selfCheck = runSelfCheck(exe);
        return new DeployResult(inspect(), true, backup, selfCheck);
    }

    /** 轻量自检：空 stdin 调用一次，要求 5 秒内以 0 退出（采集器按约定永不阻断）。 */
    public @NotNull String runSelfCheck(@Nullable Path exe) {
        Path target = exe == null ? paths.collectorExe() : exe;
        if (!Files.isRegularFile(target)) {
            return "未部署，跳过自检";
        }
        try {
            Process process = new ProcessBuilder(target.toString(), "--source", "coursemind-selftest")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "自检超时（采集器未在 8 秒内退出）";
            }
            return process.exitValue() == 0 ? "自检通过" : "自检失败：退出码 " + process.exitValue();
        } catch (IOException e) {
            return "自检未能启动：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "自检被中断";
        }
    }

    public @NotNull Optional<String> localVersion() {
        return Optional.ofNullable(readLocalVersion());
    }

    private @Nullable String readLocalVersion() {
        Path file = paths.versionFile();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return json.has("version") && json.get("version").isJsonPrimitive()
                    ? json.get("version").getAsString() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Path backupExisting(@NotNull Path exe) throws IOException {
        Files.createDirectories(paths.backupsDir());
        String version = readLocalVersion();
        String label = (version == null || version.isBlank()) ? "unknown" : version;
        Path backup = paths.backupsDir().resolve("collector-" + label + "-" + TIMESTAMP.format(Instant.now()) + ".exe");
        Files.copy(exe, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private void pruneBackups() {
        Path dir = paths.backupsDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> backups = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().startsWith("collector-"))
                    .forEach(backups::add);
        } catch (IOException e) {
            return;
        }
        backups.sort(Comparator.comparing(Path::getFileName));
        while (backups.size() > 3) {
            try {
                Files.deleteIfExists(backups.remove(0));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void writeVersionFile(@NotNull BundledCollector source) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("version", source.version());
        json.addProperty("sha256", source.sha256());
        json.addProperty("deployed_at", Instant.now().toString());
        top.chende.coursemind.runtime.AtomicFiles.write(paths.versionFile(),
                json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private boolean isWritable(@NotNull Path directory) {
        try {
            Files.createDirectories(directory);
            Path probe = Files.createTempFile(directory, "coursemind-write-", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isSupportedOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    private static @Nullable String sha256OrNull(@NotNull Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            return null;
        }
    }

    static @NotNull String sha256(byte @NotNull [] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new CourseMindException("当前 JVM 不支持 SHA-256 校验", e);
        }
    }
}
