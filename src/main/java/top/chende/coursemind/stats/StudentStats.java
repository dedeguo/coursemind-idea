package top.chende.coursemind.stats;

import com.google.gson.*;
import top.chende.coursemind.api.ApiException;
import java.time.*;
import java.util.*;

/** Server-owned aggregates; no local recounting or inferred tool identity. */
public record StudentStats(Range range, Summary summary, List<Topic> topics, List<Session> sessions) {
    public record Range(String type, LocalDate start, LocalDate end, ZoneId zone) { }
    public record Summary(long promptCount, long sessionCount, double avgTurns, double multiTurnRatio) { }
    public record Topic(String name, long count) { }
    public record Session(String sessionId, String machineId, String startedAt, String lastActiveAt,
                          long turns, String topic, String title, String source) { }
    public StudentStats { topics = List.copyOf(topics); sessions = List.copyOf(sessions); }
    public boolean empty() { return summary.promptCount() == 0 && summary.sessionCount() == 0 && sessions.isEmpty(); }
    public static StudentStats parse(JsonObject data) {
        try {
            JsonObject r = data.getAsJsonObject("range"), s = data.getAsJsonObject("summary");
            Range range = new Range(required(r, "type"), LocalDate.parse(required(r, "start")),
                    LocalDate.parse(required(r, "end")), ZoneId.of(required(r, "timezone")));
            if (!"today".equals(range.type()) || range.end().isBefore(range.start())) throw new IllegalArgumentException();
            Summary summary = new Summary(count(s, "prompt_count"), count(s, "session_count"),
                    number(s, "avg_turns"), number(s, "multi_turn_ratio"));
            if (summary.multiTurnRatio() > 1) throw new IllegalArgumentException();
            List<Topic> topics = new ArrayList<>();
            for (JsonElement entry : data.getAsJsonArray("topics")) {
                JsonObject topic = entry.getAsJsonObject(); topics.add(new Topic(required(topic, "name"), count(topic, "count")));
            }
            List<Session> sessions = new ArrayList<>();
            for (JsonElement entry : data.getAsJsonArray("recent_sessions")) {
                JsonObject session = entry.getAsJsonObject();
                sessions.add(new Session(required(session, "session_id"), required(session, "machine_id"),
                        text(session, "started_at", ""), text(session, "last_active_at", ""), count(session, "turn_count"),
                        text(session, "topic_name", "未分类"), text(session, "title", "未命名会话"), text(session, "source", "unknown")));
            }
            return new StudentStats(range, summary, topics, sessions);
        } catch (RuntimeException e) {
            throw new ApiException("统计响应格式不完整或数值无效，请检查服务端版本", 0, false, null);
        }
    }
    private static String required(JsonObject object, String key) {
        String value = text(object, key, ""); if (value.isBlank()) throw new IllegalArgumentException(); return value;
    }
    private static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && !value.getAsString().isBlank() ? value.getAsString() : fallback;
    }
    private static double number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        double number = value.getAsDouble(); if (!Double.isFinite(number) || number < 0) throw new IllegalArgumentException(); return number;
    }
    private static long count(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        long result = value.getAsBigDecimal().longValueExact();
        if (result < 0) throw new IllegalArgumentException();
        return result;
    }
}
