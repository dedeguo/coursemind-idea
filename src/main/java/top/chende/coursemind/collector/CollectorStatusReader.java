package top.chende.coursemind.collector;

import com.google.gson.*;
import top.chende.coursemind.auth.AuthSession;
import top.chende.coursemind.config.*;
import top.chende.coursemind.runtime.RuntimePaths;
import java.nio.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;

/** Read-only, bounded evidence. Existing diagnostics do not identify student or backend. */
public final class CollectorStatusReader {
    public record Status(String label, String detail, String sharedUploadAt) { }
    private static final int TAIL_BYTES = 512 * 1024;
    public Status read(RuntimePaths paths, ConnectionProfile connection, AuthSession session, Clock clock) {
        return read(paths, connection, session, clock, top.chende.coursemind.hooks.CodeBuddyHooks.defaultSettingsFile());
    }
    public Status read(RuntimePaths paths, ConnectionProfile connection, AuthSession session, Clock clock, Path hookFile) {
        try {
            if (session == null) return new Status("未登录，未采集", "登录后可读取本人待上传状态", "");
            JsonObject config = object(paths.configFile(), 128 * 1024);
            if (config == null) return new Status("采集配置未知", "请在设置中检测采集环境", "");
            if (config.has("collection_enabled") && !config.get("collection_enabled").getAsBoolean())
                return CollectorService.compareVersions(new CollectorService(paths).localVersion().orElse(""), "0.2.11") >= 0
                    ? new Status("共享采集已暂停", "现有队列保留；已在途 Hook 可能继续完成", "")
                    : new Status("共享暂停待生效", "需部署 collector 0.2.11+；当前客户端可能仍在采集", "");
            String id = text(config, "server_id", text(config, "server_url", ""));
            if (!ServerUrl.normalize(id).equals(connection.serverId())) return new Status("共享采集服务器不一致", "请打开设置确认采集归属", "");
            if (text(config, "upload_key", "").isBlank()) return new Status("缺少上传凭据", "请重新登录后检查采集环境", "");
            if (!Files.isRegularFile(paths.collectorExe())) return new Status("未部署采集客户端", "请在设置中部署或修复客户端", "");
            Path cache = Path.of(text(config, "cache_dir", paths.cacheDir().toString()));
            int pending = 0; boolean queueKnown = true;
            Path queue = cache.resolve("pending.json");
            if (Files.exists(queue)) {
                if (Files.size(queue) > 12 * 1024 * 1024) queueKnown = false;
                else {
                    JsonElement entries = JsonParser.parseString(Files.readString(queue));
                    if (!entries.isJsonArray()) queueKnown = false;
                    else for (JsonElement item : entries.getAsJsonArray()) {
                        if (!item.isJsonObject()) { queueKnown = false; continue; }
                        JsonObject entry = item.getAsJsonObject();
                        JsonObject record = entry.has("record") && entry.get("record").isJsonObject() ? entry.getAsJsonObject("record") : null;
                        String entryId = text(entry, "server_id", text(entry, "server_url", ""));
                        if (record == null || !record.has("student_id") || entryId.isBlank()) { queueKnown = false; continue; }
                        if (record.get("student_id").getAsInt() == session.profile().studentId()
                                && ServerUrl.normalize(entryId).equals(connection.serverId())) pending++;
                    }
                }
            }
            Instant since;
            try { since = Instant.parse(session.updatedAt()); } catch (RuntimeException e) { since = clock.instant(); }
            if (since.isBefore(clock.instant().minusSeconds(86400))) since = clock.instant().minusSeconds(86400);
            Evidence evidence = new Evidence();
            inspectTail(cache.resolve("collector-debug.previous.jsonl"), since, clock.instant(), evidence);
            inspectTail(cache.resolve("collector-debug.jsonl"), since, clock.instant(), evidence);
            String upload = evidence.upload == null ? "" : evidence.upload.toString();
            if (pending > 0) return new Status("待上传 " + pending + " 条", "按当前账号及服务实例读取；最近同步：未知", upload);
            if (!queueKnown) return new Status("待上传状态未知", "队列格式或大小超出可确认范围，未将其当作 0 条", upload);
            if (evidence.failed != null && (evidence.upload == null || evidence.failed.isAfter(evidence.upload))) {
                return new Status("共享采集器近期上传异常", "日志无法区分账号/服务器；请在设置中检查。最近同步：未知", upload);
            }
            if (evidence.upload != null) return new Status("共享采集器有上传确认", "无法归属当前账号/服务器；最近同步：未知", upload);
            if (evidence.hook != null) return new Status("共享采集器收到事件", "尚无可归属的上传确认；最近同步：未知", "");
            var hooks = new top.chende.coursemind.hooks.CodeBuddyHooks(hookFile, paths.collectorExe()).inspect();
            boolean ready = hooks.valid() && hooks.managedEventsPresent().containsAll(top.chende.coursemind.hooks.CodeBuddyHooks.MANAGED_EVENTS);
            return new Status(ready ? "CodeBuddy Hook 已配置，等待触发验证" : "CodeBuddy Hook 未就绪",
                    "其他工具状态待核对；最近同步：未知", "");
        } catch (Exception e) { return new Status("采集状态未知", "本地状态暂不可读，请在设置中检测采集环境", ""); }
    }
    private static final class Evidence { Instant upload, failed, hook; }
    private static void inspectTail(Path path, Instant since, Instant now, Evidence evidence) throws Exception {
        if (!Files.isRegularFile(path)) return;
        String tail;
        try (SeekableByteChannel channel = Files.newByteChannel(path)) {
            long start = Math.max(0, channel.size() - TAIL_BYTES); channel.position(start);
            ByteBuffer bytes = ByteBuffer.allocate(TAIL_BYTES);
            while (bytes.hasRemaining() && channel.read(bytes) > 0) { }
            bytes.flip(); tail = StandardCharsets.UTF_8.decode(bytes).toString();
            if (start > 0) { int newline = tail.indexOf('\n'); tail = newline < 0 ? "" : tail.substring(newline + 1); }
        }
        for (String line : tail.split("\n")) {
            try {
                JsonObject entry = JsonParser.parseString(line).getAsJsonObject();
                Instant time = Instant.parse(text(entry, "timestamp", ""));
                if (time.isBefore(since) || time.isAfter(now.plusSeconds(60))) continue;
                switch (text(entry, "event", "")) {
                    case "upload_succeeded" -> evidence.upload = later(evidence.upload, time);
                    case "upload_failed", "collector_exception" -> evidence.failed = later(evidence.failed, time);
                    case "hook_received" -> evidence.hook = later(evidence.hook, time);
                }
            } catch (RuntimeException ignored) { /* Truncated/rotating lines are not success evidence. */ }
        }
    }
    private static Instant later(Instant first, Instant second) { return first == null || second.isAfter(first) ? second : first; }
    private static JsonObject object(Path path, int limit) throws Exception {
        if (!Files.isRegularFile(path) || Files.size(path) > limit) return null;
        JsonElement value = JsonParser.parseString(Files.readString(path)); return value.isJsonObject() ? value.getAsJsonObject() : null;
    }
    private static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : fallback;
    }
}
