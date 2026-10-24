package top.chende.coursemind.education;

import com.google.gson.JsonParser;
import org.junit.Test;
import top.chende.coursemind.api.ApiException;

import java.util.List;

import static org.junit.Assert.*;

public class EducationDataTest {
    @Test public void parsesGroupTaskAndPage() {
        var group = EducationData.group(JsonParser.parseString(
            "{\"id\":1,\"name\":\"G1\",\"course_name\":\"语文\",\"term_name\":\"2026 春季\"}").getAsJsonObject());
        var task = EducationData.task(JsonParser.parseString("""
            {"id":2,"group_id":1,"title":"预习","course_name":"语文","group_name":"G1","kind":"assignment",
             "status":"published","time_status":"ongoing","open_at":"2026-10-21T01:00:00Z","due_at":null,
             "removed_at":null,"body_markdown":"# 说明","resource_links":["https://example.invalid/a"]}
            """).getAsJsonObject());
        var page = EducationData.page(JsonParser.parseString(
            "{\"items\":[],\"total\":0,\"page\":1,\"page_size\":20}"), EducationData::task);

        assertEquals("语文 · G1 · 2026 春季", group.toString());
        assertEquals(2, task.id());
        assertTrue(task.linkable());
        assertEquals("2026-10-21 09:00", EducationData.time(task.openAt()));
        assertEquals("已发布 · 进行中", task.detail().split("\\R")[1]);
        assertFalse(page.hasNext());
    }

    @Test public void parsesSessionsAndLinkResults() {
        var session = EducationData.session(JsonParser.parseString(
            "{\"id\":3,\"source_session_id\":\"s-1\",\"source\":\"codebuddy\",\"machine_id\":\"pc-1\",\"record_count\":4,\"last_at\":\"2026-10-21T02:00:00Z\"}").getAsJsonObject());
        var added = EducationData.result(JsonParser.parseString(
            "{\"task_id\":2,\"session_id\":3,\"status\":\"added\"}").getAsJsonObject());
        var failed = EducationData.result(JsonParser.parseString(
            "{\"task_id\":2,\"session_id\":3,\"status\":\"failed\",\"message\":\"任务已关闭\"}").getAsJsonObject());

        assertEquals("s-1 · codebuddy · 4 条", session.toString());
        assertEquals("已添加", added.label());
        assertEquals("失败：任务已关闭", failed.label());
    }

    @Test public void rejectsIncompleteServerPayloads() {
        assertThrows(ApiException.class, () -> EducationData.task(JsonParser.parseString(
            "{\"id\":2,\"title\":\"缺字段\"}").getAsJsonObject()));
        assertThrows(ApiException.class, () -> EducationData.page(JsonParser.parseString("[]"), EducationData::task));
        assertEquals(List.of(), EducationData.list(JsonParser.parseString("[]"), EducationData::task));
    }
}
