package top.chende.coursemind.hooks;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.runtime.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * CodeBuddy Hook 配置（{@code ~/.codebuddy/settings.json}）。
 *
 * <p>该文件是用户级多方共享文件：只处理 CourseMind 自己的 {@code UserPromptSubmit} 与 {@code Stop}
 * 条目，其余事件与命令原样保留；写入前备份，写完不重复追加（幂等）。
 */
public final class CodeBuddyHooks {

    public static final List<String> MANAGED_EVENTS = List.of("UserPromptSubmit", "Stop");
    private static final String SOURCE = "codebuddy";
    private static final int TIMEOUT_SECONDS = 10;
    private static final String LEGACY_COMMAND = "c:/ai-collector/collector.exe";
    private static final DateTimeFormatter BACKUP_SUFFIX =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.systemDefault());
    private static final Pattern DUPLICATE_SLASH = Pattern.compile("/{2,}");

    public record Status(@NotNull Path file,
                         boolean exists,
                         boolean valid,
                         @Nullable String error,
                         int managedEntries,
                         @NotNull List<String> managedEventsPresent,
                         @NotNull List<String> otherEvents,
                         @Nullable String currentCommand) {
    }

    public record Plan(boolean changed, @NotNull List<String> lines, boolean backupRequired, @Nullable String error) {
    }

    public record ApplyResult(boolean changed, @Nullable Path backup, @NotNull String message) {
    }

    private final Path settingsFile;
    private final Path collectorExe;
    private final String source;

    public CodeBuddyHooks(@NotNull Path settingsFile, @NotNull Path collectorExe) {
        this(settingsFile, collectorExe, "codebuddy");
    }

    public CodeBuddyHooks(Path settingsFile, Path collectorExe, String source) {
        if (!List.of("codebuddy", "workbuddy", "claude-code", "trae-code").contains(source))
            throw new IllegalArgumentException("Unknown source");
        this.source = source;
        this.settingsFile = settingsFile;
        this.collectorExe = collectorExe;
    }

    public static @NotNull Path defaultSettingsFile() {
        return Path.of(System.getProperty("user.home", "."), ".codebuddy", "settings.json");
    }

    public @NotNull Path settingsFile() {
        return settingsFile;
    }

    public @NotNull String command() {
        String path = collectorExe.toAbsolutePath().toString().replace('\\', '/');
        if (source.equals("claude-code")) path = path.replaceFirst("^([A-Za-z]):", "/$1");
        if (source.equals("claude-code") && path.matches("^/[A-Za-z]/.*"))
            path = "/" + Character.toLowerCase(path.charAt(1)) + path.substring(2);
        return (source.equals("trae-code") ? "& " : "") + "\"" + path + "\" --source " + source;
    }

    public static @NotNull String buildCommand(@NotNull Path exe) {
        return "\"" + exe.toAbsolutePath().toString().replace('\\', '/') + "\" --source " + SOURCE;
    }

    public @NotNull Status inspect() {
        if (!Files.isRegularFile(settingsFile)) {
            return new Status(settingsFile, false, true, null, 0, List.of(), List.of(), null);
        }
        Optional<JsonObject> parsed = readJson();
        if (parsed.isEmpty()) {
            return new Status(settingsFile, true, false,
                    "Hook 文件不是合法 JSON，已停止写入，请先手工修复：" + settingsFile,
                    0, List.of(), List.of(), null);
        }
        JsonObject root = parsed.get();
        JsonElement hooksElement = root.get("hooks");
        if (hooksElement != null && !hooksElement.isJsonObject()) {
            return new Status(settingsFile, true, false,
                    "Hook 文件结构异常（hooks 不是对象），已停止写入：" + settingsFile,
                    0, List.of(), List.of(), null);
        }
        JsonObject hooks = hooksElement == null ? new JsonObject() : hooksElement.getAsJsonObject();
        int managed = 0;
        List<String> managedPresent = new ArrayList<>();
        List<String> others = new ArrayList<>();
        String current = null;
        for (String name : hooks.keySet()) {
            JsonElement value = hooks.get(name);
            if (!MANAGED_EVENTS.contains(name)) {
                others.add(name);
                continue;
            }
            int count = countManaged(value, normalizedCollectorPath());
            if (count > 0) {
                managedPresent.add(name);
            }
            managed += count;
            if (current == null) {
                current = firstCommand(value);
            }
        }
        return new Status(settingsFile, true, true, null, managed, managedPresent, others, current);
    }

    public @NotNull Plan plan() {
        return plan(true);
    }

    public @NotNull Plan planRemove() {
        return plan(false);
    }

    private @NotNull Plan plan(boolean apply) {
        List<String> lines = new ArrayList<>();
        Optional<JsonObject> parsed = readJsonOrThrow();
        JsonObject source = parsed.orElseGet(JsonObject::new);
        JsonObject target = apply
                ? merge(source, command(), normalizedCollectorPath())
                : removeManaged(source, normalizedCollectorPath());
        boolean changed = !target.equals(source);
        lines.add("文件：" + settingsFile);
        lines.add("作用域：用户级共享，所选工具的各 IDE 入口同时生效");
        if (apply) lines.add("执行命令：" + command());
        lines.add("备份：原文件同目录 .coursemind-*.bak，保留最近 3 份；恢复只处理受管命令");
        if (!changed) {
            lines.add(apply ? "结果：CourseMind 条目已是最新，无需写入" : "结果：没有 CourseMind 条目，无需写入");
        } else {
            for (String event : MANAGED_EVENTS) {
                lines.add((apply ? "写入 " : "移除 ") + event + " 的 CourseMind 条目");
            }
            List<String> others = otherEvents(source);
            lines.add(others.isEmpty() ? "第三方条目：无" : "保留第三方事件：" + String.join("、", others));
        }
        return new Plan(changed, lines, changed && Files.isRegularFile(settingsFile), null);
    }

    /** 应用（新增或修复）CourseMind 条目；返回是否写入及备份路径。 */
    public @NotNull ApplyResult apply() {
        return write(true);
    }

    /** 仅移除 CourseMind 管理的条目，不动其他事件与命令。 */
    public @NotNull ApplyResult remove() {
        return write(false);
    }

    public @NotNull Path latestBackup() {
        Path directory = settingsFile.toAbsolutePath().getParent();
        String prefix = settingsFile.getFileName() + ".coursemind-";
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().startsWith(prefix)
                    && p.getFileName().toString().endsWith(".bak")).max(Comparator.comparing(Path::getFileName))
                    .orElseThrow(() -> new CourseMindException("没有可恢复的 CourseMind 备份"));
        } catch (IOException e) { throw new CourseMindException("无法读取备份目录", e); }
    }
    private JsonObject restoreTarget(JsonObject current, Path backup) {
        JsonObject original;
        try { original = parseOrThrow(Files.readAllBytes(backup)); }
        catch (IOException e) { throw new CourseMindException("无法读取 Hook 备份", e); }
        JsonObject target = removeManaged(current, normalizedCollectorPath());
        JsonObject originalHooks = hooksOf(original, false);
        JsonObject targetHooks = hooksOf(target, true);
        for (String event : MANAGED_EVENTS) {
            JsonArray output = arrayOf(targetHooks, event);
            for (JsonElement element : arrayOf(originalHooks, event)) {
                if (!element.isJsonObject()) continue;
                JsonObject group = element.getAsJsonObject().deepCopy();
                if (!group.has("hooks") || !group.get("hooks").isJsonArray()) continue;
                JsonArray owned = new JsonArray();
                for (JsonElement command : group.getAsJsonArray("hooks")) {
                    if (command.isJsonObject() && command.getAsJsonObject().has("command")
                            && command.getAsJsonObject().get("command").isJsonPrimitive()
                            && isManagedCommand(command.getAsJsonObject().get("command").getAsString(), normalizedCollectorPath())) owned.add(command);
                }
                if (!owned.isEmpty()) { group.add("hooks", owned); output.add(group); }
            }
            if (!output.isEmpty()) targetHooks.add(event, output);
        }
        if (targetHooks.isEmpty()) target.remove("hooks");
        return target;
    }
    public @NotNull Plan planRestore(Path backup) {
        JsonObject source = parseOrThrow(readBytes());
        boolean changed = !restoreTarget(source, backup).equals(source);
        return new Plan(changed, List.of("文件：" + settingsFile, "备份：" + backup,
                "仅恢复该备份中的 CourseMind 受管命令；保留当前第三方命令及其他设置。",
                changed ? "执行前再次备份当前文件" : "受管条目无需恢复"), changed, null);
    }
    public @NotNull ApplyResult restore(Path backup) {
        byte[] before = readBytes();
        return commit(before, parseOrThrow(before), restoreTarget(parseOrThrow(before), backup), "已恢复备份中的 CourseMind 受管条目");
    }

    private @NotNull ApplyResult write(boolean add) {
        byte[] before = readBytes();
        JsonObject source = parseOrThrow(before);
        JsonObject target = add
                ? merge(source, command(), normalizedCollectorPath())
                : removeManaged(source, normalizedCollectorPath());
        return commit(before, source, target, add ? "已写入 CourseMind Hook 配置" : "已移除 CourseMind Hook 配置");
    }
    private ApplyResult commit(byte[] before, JsonObject source, JsonObject target, String message) {
        if (target.equals(source)) return new ApplyResult(false, null, "无需写入");
        byte[] after = readBytes();
        if (!Arrays.equals(before, after)) {
            throw new CourseMindException("Hook 文件刚被其他程序修改，本次未写入，请重试");
        }
        Path backup = null;
        if (before != null) {
            backup = backup();
        }
        if (!Arrays.equals(before, readBytes())) throw new CourseMindException("备份期间 Hook 文件被修改，请重新预览");
        String content = new GsonBuilder().setPrettyPrinting().create().toJson(target) + "\n";
        try {
            AtomicFiles.write(settingsFile, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CourseMindException("无法写入 Hook 文件，请检查文件占用与权限：" + settingsFile, e);
        }
        pruneBackups();
        return new ApplyResult(true, backup, message);
    }

    /** 合并算法：只重建两个受管事件数组，其他键原样保留。 */
    static @NotNull JsonObject merge(@NotNull JsonObject source,
                                     @NotNull String command,
                                     @NotNull String collectorPath) {
        JsonObject root = source.deepCopy();
        JsonObject hooks = hooksOf(root, true);
        for (String event : MANAGED_EVENTS) {
            JsonArray array = arrayOf(hooks, event);
            JsonArray cleaned = new JsonArray();
            for (JsonElement element : array) {
                JsonElement entry = keepEntry(element, collectorPath);
                if (entry != null) {
                    cleaned.add(entry);
                }
            }
            cleaned.add(commandEntry(command));
            hooks.add(event, cleaned);
        }
        root.add("hooks", hooks);
        return root;
    }

    static @NotNull JsonObject removeManaged(@NotNull JsonObject source, @NotNull String collectorPath) {
        JsonObject root = source.deepCopy();
        if (!root.has("hooks") || !root.get("hooks").isJsonObject()) {
            return root;
        }
        JsonObject hooks = root.getAsJsonObject("hooks");
        for (String event : MANAGED_EVENTS) {
            if (!hooks.has(event) || !hooks.get(event).isJsonArray()) {
                continue;
            }
            JsonArray cleaned = new JsonArray();
            for (JsonElement element : hooks.getAsJsonArray(event)) {
                JsonElement entry = keepEntry(element, collectorPath);
                if (entry != null) {
                    cleaned.add(entry);
                }
            }
            if (cleaned.isEmpty()) {
                hooks.remove(event);
            } else {
                hooks.add(event, cleaned);
            }
        }
        if (hooks.isEmpty()) {
            root.remove("hooks");
        }
        return root;
    }

    /** 去掉条目中指向本 collector 的命令；条目空掉则删除，其余字段保持不变。 */
    private static @Nullable JsonElement keepEntry(@NotNull JsonElement element, @NotNull String collectorPath) {
        if (!element.isJsonObject()) {
            return element.deepCopy();
        }
        JsonObject entry = element.getAsJsonObject().deepCopy();
        if (!entry.has("hooks") || !entry.get("hooks").isJsonArray()) {
            return entry;
        }
        JsonArray commands = new JsonArray();
        for (JsonElement item : entry.getAsJsonArray("hooks")) {
            if (!item.isJsonObject()) {
                commands.add(item.deepCopy());
                continue;
            }
            JsonObject command = item.getAsJsonObject();
            String value = command.has("command") && command.get("command").isJsonPrimitive()
                    ? command.get("command").getAsString() : null;
            if (!isManagedCommand(value, collectorPath)) {
                commands.add(command);
            }
        }
        if (commands.isEmpty()) {
            return null;
        }
        if (commands.size() == entry.getAsJsonArray("hooks").size()) {
            return entry;
        }
        entry.add("hooks", commands);
        return entry;
    }

    private static @NotNull JsonObject commandEntry(@NotNull String command) {
        JsonObject hook = new JsonObject();
        hook.addProperty("type", "command");
        hook.addProperty("command", command);
        if (!command.endsWith("--source claude-code")) hook.addProperty("timeout", TIMEOUT_SECONDS);
        JsonArray hooks = new JsonArray();
        hooks.add(hook);
        JsonObject entry = new JsonObject();
        entry.add("hooks", hooks);
        return entry;
    }

    static boolean isManagedCommand(@Nullable String command, @NotNull String collectorPath) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = normalizeCommand(command);
        String path = normalizeCommand(collectorPath);
        if (!path.isEmpty() && (normalized.equals(path) || normalized.startsWith(path + " "))) {
            return true;
        }
        return normalized.equals(LEGACY_COMMAND) || normalized.startsWith(LEGACY_COMMAND + " ");
    }

    static @NotNull String normalizeCommand(@NotNull String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        normalized = normalized.replaceFirst("^\\s*&\\s*", "").replace("\"", "");
        normalized = normalized.replaceFirst("^/([a-z])/", "$1:/");
        return DUPLICATE_SLASH.matcher(normalized).replaceAll("/");
    }

    private int countManaged(@Nullable JsonElement value, @NotNull String collectorPath) {
        if (value == null || !value.isJsonArray()) {
            return 0;
        }
        int count = 0;
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("hooks") || !entry.get("hooks").isJsonArray()) {
                continue;
            }
            for (JsonElement item : entry.getAsJsonArray("hooks")) {
                if (item.isJsonObject() && item.getAsJsonObject().has("command")) {
                    JsonElement command = item.getAsJsonObject().get("command");
                    if (command.isJsonPrimitive()
                            && isManagedCommand(command.getAsString(), collectorPath)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static @Nullable String firstCommand(@Nullable JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return null;
        }
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("hooks") && entry.get("hooks").isJsonArray()) {
                for (JsonElement item : entry.getAsJsonArray("hooks")) {
                    if (item.isJsonObject() && item.getAsJsonObject().has("command")) {
                        return item.getAsJsonObject().get("command").getAsString();
                    }
                }
            }
        }
        return null;
    }

    private static @NotNull JsonObject hooksOf(@NotNull JsonObject root, boolean create) {
        JsonElement hooks = root.get("hooks");
        if (hooks != null && hooks.isJsonObject()) {
            return hooks.getAsJsonObject();
        }
        if (hooks != null && !hooks.isJsonNull()) {
            throw new CourseMindException("Hook 文件结构异常（hooks 不是对象），已停止写入");
        }
        JsonObject created = new JsonObject();
        if (create) {
            root.add("hooks", created);
        }
        return created;
    }

    private static @NotNull JsonArray arrayOf(@NotNull JsonObject hooks, @NotNull String event) {
        JsonElement value = hooks.get(event);
        if (value == null || value.isJsonNull()) {
            return new JsonArray();
        }
        if (!value.isJsonArray()) {
            throw new CourseMindException("Hook 文件中 " + event + " 不是数组，已停止写入");
        }
        return value.getAsJsonArray();
    }

    private static @NotNull List<String> otherEvents(@NotNull JsonObject source) {
        if (!source.has("hooks") || !source.get("hooks").isJsonObject()) {
            return List.of();
        }
        JsonObject hooks = source.getAsJsonObject("hooks");
        Set<String> names = new LinkedHashSet<>();
        for (String name : hooks.keySet()) {
            if (!MANAGED_EVENTS.contains(name)) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    private @NotNull Optional<JsonObject> readJson() {
        byte[] raw = readBytes();
        if (raw == null) {
            return Optional.empty();
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? Optional.of(parsed.getAsJsonObject()) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private @NotNull Optional<JsonObject> readJsonOrThrow() {
        if (!Files.isRegularFile(settingsFile)) {
            return Optional.empty();
        }
        Optional<JsonObject> parsed = readJson();
        if (parsed.isEmpty()) {
            throw new CourseMindException("Hook 文件不是合法 JSON，已停止写入，请先手工修复：" + settingsFile);
        }
        return parsed;
    }

    private @NotNull JsonObject parseOrThrow(byte @Nullable [] raw) {
        if (raw == null) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // 落到统一异常
        }
        throw new CourseMindException("Hook 文件不是合法 JSON，已停止写入，请先手工修复：" + settingsFile);
    }

    private byte @Nullable [] readBytes() {
        if (!Files.isRegularFile(settingsFile)) {
            return null;
        }
        try {
            return Files.readAllBytes(settingsFile);
        } catch (IOException e) {
            throw new CourseMindException("无法读取 Hook 文件：" + settingsFile, e);
        }
    }

    private @NotNull Path backup() {
        Path backup = settingsFile.resolveSibling(
                settingsFile.getFileName() + ".coursemind-" + BACKUP_SUFFIX.format(Instant.now()) + "-" + java.util.UUID.randomUUID() + ".bak");
        try {
            Files.createDirectories(settingsFile.toAbsolutePath().getParent());
            Files.copy(settingsFile, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) {
            throw new CourseMindException("无法备份 Hook 文件，本次未写入", e);
        }
    }

    private void pruneBackups() {
        Path dir = settingsFile.toAbsolutePath().getParent();
        if (dir == null) {
            return;
        }
        String prefix = settingsFile.getFileName() + ".coursemind-";
        List<Path> backups = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().startsWith(prefix)).forEach(backups::add);
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

    private @NotNull String normalizedCollectorPath() {
        return normalizeCommand(collectorExe.toAbsolutePath().toString());
    }
}
