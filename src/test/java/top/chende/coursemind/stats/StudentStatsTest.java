package top.chende.coursemind.stats;
import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StudentStatsTest {
    public static JsonObject fixture() {
        return JsonParser.parseString("""
            {"range":{"type":"today","start":"2026-10-21","end":"2026-10-21","timezone":"Asia/Shanghai"},
            "summary":{"prompt_count":6,"session_count":2,"avg_turns":3.0,"multi_turn_ratio":1},
            "topics":[{"name":"代码实现","count":3}],
            "recent_sessions":[{"session_id":"s1","machine_id":"m1","started_at":"2026-10-21T08:00:00Z",
            "last_active_at":"2026-10-21T08:16:00Z","turn_count":2,"topic_name":"代码实现","title":"布局测试会话","source":"codebuddy"}]}
            """).getAsJsonObject();
    }
    @Test public void serverAggregatesAreUsedVerbatimAndSourceIsNotGuessed() {
        JsonObject data = fixture(); data.getAsJsonArray("recent_sessions").get(0).getAsJsonObject().remove("source");
        StudentStats stats = StudentStats.parse(data);
        assertEquals(6, stats.summary().promptCount()); assertEquals(3.0, stats.summary().avgTurns(), 0);
        assertEquals("unknown", stats.sessions().get(0).source());
    }
    @Test public void missingOrMalformedFieldsAreErrorsNotZeroStats() {
        JsonObject data = fixture(); data.remove("summary");
        assertThrows(RuntimeException.class, () -> StudentStats.parse(data));
        JsonObject ratio = fixture(); ratio.getAsJsonObject("summary").addProperty("multi_turn_ratio", 100);
        assertThrows(RuntimeException.class, () -> StudentStats.parse(ratio));
        JsonObject fractional = fixture(); fractional.getAsJsonObject("summary").addProperty("prompt_count", 1.5);
        assertThrows(RuntimeException.class, () -> StudentStats.parse(fractional));
    }
    @Test public void emptyTodayIsAnExplicitServerResult() {
        JsonObject data = fixture();
        data.add("summary", JsonParser.parseString("{\"prompt_count\":0,\"session_count\":0,\"avg_turns\":0,\"multi_turn_ratio\":0}"));
        data.add("topics", new JsonArray()); data.add("recent_sessions", new JsonArray());
        assertTrue(StudentStats.parse(data).empty());
    }
}
