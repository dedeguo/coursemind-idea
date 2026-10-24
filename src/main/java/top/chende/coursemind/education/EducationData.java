package top.chende.coursemind.education;

import com.google.gson.*;
import top.chende.coursemind.api.ApiException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/** Wire objects use server session IDs, never the source session ID as a key. */
public final class EducationData {
    private EducationData() { }
    public record Page<T>(List<T> items, long total, int page, int pageSize) {
        public boolean hasNext() { return (long) page * pageSize < total; }
    }
    public record Group(int id, String name, String course, String term) {
        @Override public String toString() { return course + " · " + name + " · " + term; }
    }
    public record Task(int id, int groupId, String title, String course, String group, String kind,
                       String status, String timeStatus, String openAt, String dueAt, boolean removed,
                       String markdown, List<String> resources) {
        public boolean linkable() { return "published".equals(status) && !removed; }
        @Override public String toString() { return title + " · " + group + " · " + statusLabel(status); }
        public String detail() {
            return course + " · " + group + "\n" + statusLabel(status) + (removed ? " · 已移除" : " · " + timeLabel(timeStatus))
                + "\n开放：" + time(openAt) + "\n截止：" + (dueAt == null ? "不设截止" : time(dueAt))
                + "（北京时间）\n\n" + markdown + (resources.isEmpty() ? "" : "\n\n资源链接：\n" + String.join("\n", resources));
        }
    }
    public record Session(long id, String sourceId, String source, String machine, int recordCount, String lastAt) {
        @Override public String toString() { return sourceId + " · " + source + " · " + recordCount + " 条"; }
    }
    public record Pair(int taskId, long sessionId) { }
    public record LinkResult(int taskId, long sessionId, String status, String message) {
        public String label() { return switch (status) {
            case "added" -> "已添加"; case "removed" -> "已解除";
            case "already_exists" -> "已有关联"; case "already_removed" -> "已解除";
            default -> "失败：" + (message == null ? "请刷新后重试" : message);
        }; }
    }
    public static Group group(JsonObject json) {
        return new Group(id(json, "id"), text(json, "name"), text(json, "course_name"), text(json, "term_name"));
    }
    public static Task task(JsonObject json) {
        List<String> links = new ArrayList<>();
        for (JsonElement link : array(json.get("resource_links"))) links.add(link.getAsString());
        return new Task(id(json, "id"), id(json, "group_id"), text(json, "title"), text(json, "course_name"),
            text(json, "group_name"), text(json, "kind"), text(json, "status"), text(json, "time_status"),
            text(json, "open_at"), optional(json, "due_at"), optional(json, "removed_at") != null,
            text(json, "body_markdown"), List.copyOf(links));
    }
    public static Session session(JsonObject json) {
        return new Session(positive(json, "id"), text(json, "source_session_id"), text(json, "source"),
            text(json, "machine_id"), json.get("record_count").getAsInt(), text(json, "last_at"));
    }
    public static LinkResult result(JsonObject json) {
        return new LinkResult(id(json, "task_id"), positive(json, "session_id"), text(json, "status"), optional(json, "message"));
    }
    public static <T> Page<T> page(JsonElement value, Function<JsonObject, T> parse) {
        JsonObject json = object(value);
        return new Page<>(list(json.get("items"), parse), json.get("total").getAsLong(),
            id(json, "page"), id(json, "page_size"));
    }
    public static <T> List<T> list(JsonElement value, Function<JsonObject, T> parse) {
        List<T> rows = new ArrayList<>();
        for (JsonElement row : array(value)) rows.add(parse.apply(object(row)));
        return List.copyOf(rows);
    }
    private static JsonArray array(JsonElement value) {
        if (value == null || !value.isJsonArray()) throw invalid();
        return value.getAsJsonArray();
    }
    public static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw invalid();
        return value.getAsJsonObject();
    }
    private static int id(JsonObject json, String key) {
        long value = positive(json, key);
        if (value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }
    private static long positive(JsonObject json, String key) {
        try { long value = json.get(key).getAsBigDecimal().longValueExact(); if (value > 0) return value; }
        catch (RuntimeException ignored) { }
        throw invalid();
    }
    private static String text(JsonObject json, String key) {
        String value = optional(json, key); if (value == null) throw invalid(); return value;
    }
    private static String optional(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        return value.getAsString();
    }
    private static ApiException invalid() { return new ApiException("教学数据格式不完整，请检查服务端版本", 0, false, null); }
    public static String time(String value) {
        try { return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.parse(value)); }
        catch (RuntimeException ignored) { return "未知"; }
    }
    public static String statusLabel(String value) { return switch (value) {
        case "draft" -> "草稿"; case "published" -> "已发布"; case "closed" -> "已关闭"; case "archived" -> "已归档"; default -> "未知状态";
    }; }
    private static String timeLabel(String value) { return switch (value) {
        case "not_open" -> "未开放"; case "ongoing" -> "进行中"; case "expired" -> "已截止"; default -> "未知时间状态";
    }; }
}
