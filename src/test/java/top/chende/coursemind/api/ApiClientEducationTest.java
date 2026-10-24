package top.chende.coursemind.api;

import org.junit.Test;
import top.chende.coursemind.education.EducationData;
import top.chende.coursemind.education.WorkbenchLinks;

import java.util.List;

import static org.junit.Assert.*;

public class ApiClientEducationTest {
    @Test public void validatesBatchLinksBeforeRequest() {
        var client = new ApiClient("https://example.invalid");
        var pairs = List.of(new EducationData.Pair(2, 3));
        assertThrows(IllegalArgumentException.class, () -> client.sessionLinks("token", "invalid", pairs));
        assertThrows(IllegalArgumentException.class, () -> client.sessionLinks("token", "add", List.of()));
        assertThrows(IllegalArgumentException.class, () -> client.sessionLinks("token", "add",
            List.of(new EducationData.Pair(0, 3))));
    }

    @Test public void buildsWorkbenchUrlsWithoutCredentials() {
        assertEquals("https://example.test/education/tasks/2", WorkbenchLinks.task("https://example.test", 2));
        assertEquals("https://example.test/education/sessions/3", WorkbenchLinks.session("https://example.test/", 3));
        assertThrows(IllegalArgumentException.class, () -> WorkbenchLinks.task("https://example.test", 0));
    }
}
