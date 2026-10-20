package top.chende.coursemind.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import top.chende.coursemind.CourseMindException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CodeBuddyHooksTest {

    private static final String COLLECTOR = "c:/users/54204/appdata/local/coursemind/collector.exe";
    private static final String COMMAND = "\"C:/Users/54204/AppData/Local/CourseMind/collector.exe\" --source codebuddy";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static JsonObject parse(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    public void mergeAddsBothEventsAndKeepsThirdPartyEntries() {
        JsonObject source = parse("{\"enabledPlugins\":{\"pdf\":true},\"hooks\":{"
                + "\"SessionEnd\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"node notify.cjs\"}]}]}}");

        JsonObject merged = CodeBuddyHooks.merge(source, COMMAND, COLLECTOR);

        assertTrue(merged.has("enabledPlugins"));
        assertEquals(1, merged.getAsJsonObject("hooks").getAsJsonArray("SessionEnd").size());
        assertEquals(1, merged.getAsJsonObject("hooks").getAsJsonArray("UserPromptSubmit").size());
        assertEquals(1, merged.getAsJsonObject("hooks").getAsJsonArray("Stop").size());
        assertEquals(COMMAND, firstCommand(merged, "Stop"));
    }

    @Test
    public void mergeIsIdempotent() {
        JsonObject once = CodeBuddyHooks.merge(parse("{}"), COMMAND, COLLECTOR);
        JsonObject twice = CodeBuddyHooks.merge(once, COMMAND, COLLECTOR);
        assertEquals(once, twice);
    }

    @Test
    public void mergeReplacesLegacyDeploymentCommand() {
        JsonObject source = parse("{\"hooks\":{\"Stop\":[{\"hooks\":[{"
                + "\"type\":\"command\",\"command\":\"\\\"c:\\\\ai-collector\\\\collector.exe\\\" --source codebuddy\","
                + "\"timeout\":10}]}]}}");

        JsonObject merged = CodeBuddyHooks.merge(source, COMMAND, COLLECTOR);
        JsonArray stop = merged.getAsJsonObject("hooks").getAsJsonArray("Stop");
        assertEquals(1, stop.size());
        assertEquals(COMMAND, firstCommand(merged, "Stop"));
    }

    @Test
    public void mergeKeepsForeignCommandInsideSameEntry() {
        JsonObject source = parse("{\"hooks\":{\"Stop\":[{\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"node other.cjs\"},"
                + "{\"type\":\"command\",\"command\":\"" + COMMAND.replace("\"", "\\\"") + "\"}]}]}}");

        JsonObject merged = CodeBuddyHooks.merge(source, COMMAND, COLLECTOR);
        JsonArray stop = merged.getAsJsonObject("hooks").getAsJsonArray("Stop");
        assertEquals(2, stop.size());
        assertTrue(stop.toString().contains("node other.cjs"));
    }

    @Test
    public void removeManagedKeepsThirdPartyEvents() {
        JsonObject merged = CodeBuddyHooks.merge(parse("{\"hooks\":{\"SessionEnd\":[{\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"node notify.cjs\"}]}]}}"), COMMAND, COLLECTOR);

        JsonObject removed = CodeBuddyHooks.removeManaged(merged, COLLECTOR);

        assertFalse(removed.getAsJsonObject("hooks").has("Stop"));
        assertFalse(removed.getAsJsonObject("hooks").has("UserPromptSubmit"));
        assertEquals(1, removed.getAsJsonObject("hooks").getAsJsonArray("SessionEnd").size());
    }

    @Test
    public void missingFileIsCreatedOnApply() throws IOException {
        Path settings = folder.newFolder(".codebuddy").toPath().resolve("settings.json");
        CodeBuddyHooks hooks = new CodeBuddyHooks(settings, Path.of("C:/CourseMind/collector.exe"));

        CodeBuddyHooks.ApplyResult result = hooks.apply();

        assertTrue(result.changed());
        JsonObject written = parse(Files.readString(settings, StandardCharsets.UTF_8));
        assertEquals(1, written.getAsJsonObject("hooks").getAsJsonArray("UserPromptSubmit").size());
        assertNotNull(hooks.inspect());
        assertEquals(2, hooks.inspect().managedEntries());
    }

    @Test
    public void applyIsIdempotentAndBacksUp() throws IOException {
        Path dir = folder.newFolder(".codebuddy").toPath();
        Path settings = dir.resolve("settings.json");
        Files.writeString(settings, "{\"hooks\":{}}", StandardCharsets.UTF_8);
        CodeBuddyHooks hooks = new CodeBuddyHooks(settings, Path.of("C:/CourseMind/collector.exe"));

        CodeBuddyHooks.ApplyResult first = hooks.apply();
        CodeBuddyHooks.ApplyResult second = hooks.apply();

        assertTrue(first.changed());
        assertNotNull(first.backup());
        assertFalse(second.changed());
        assertEquals(null, second.backup());
    }

    @Test
    public void corruptJsonIsNotOverwritten() throws IOException {
        Path settings = folder.newFolder(".codebuddy").toPath().resolve("settings.json");
        Files.writeString(settings, "{ not json", StandardCharsets.UTF_8);
        CodeBuddyHooks hooks = new CodeBuddyHooks(settings, Path.of("C:/CourseMind/collector.exe"));

        assertFalse(hooks.inspect().valid());
        try {
            hooks.apply();
            fail("损坏的 Hook 文件不应被写入");
        } catch (CourseMindException expected) {
            assertEquals("{ not json", Files.readString(settings, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void removesOnlyLegacyEntriesWhenCollectorPathDiffers() {
        JsonObject source = parse("{\"hooks\":{\"Stop\":[{\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"\\\"c:\\\\ai-collector\\\\collector.exe\\\" --source codebuddy\"},"
                + "{\"type\":\"command\",\"command\":\"node other.cjs\"}]}]}}");

        JsonObject removed = CodeBuddyHooks.removeManaged(source, COLLECTOR);

        JsonArray stop = removed.getAsJsonObject("hooks").getAsJsonArray("Stop");
        assertEquals(1, stop.size());
        assertTrue(stop.toString().contains("node other.cjs"));
    }

    private static String firstCommand(JsonObject root, String event) {
        return root.getAsJsonObject("hooks").getAsJsonArray(event)
                .get(0).getAsJsonObject().getAsJsonArray("hooks")
                .get(0).getAsJsonObject().get("command").getAsString();
    }
}
