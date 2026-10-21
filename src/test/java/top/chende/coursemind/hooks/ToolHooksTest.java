package top.chende.coursemind.hooks;

import com.google.gson.JsonParser;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import top.chende.coursemind.runtime.RuntimePaths;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class ToolHooksTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private ToolHooks service(Path home) { return new ToolHooks(home, RuntimePaths.of(home.resolve("runtime")), true); }
    @Test public void missingToolsAndUnsupportedPlatformAreReadOnly() throws Exception {
        Path home=folder.newFolder().toPath();
        var hooks=service(home);
        for (var tool:ToolHooks.Tool.values()) {
            assertFalse(hooks.inspect(tool).available());
            assertFalse(Files.exists(hooks.file(tool).getParent()));
        }
        assertFalse(new ToolHooks(home,RuntimePaths.of(home.resolve("runtime")),false).inspect(ToolHooks.Tool.CODEBUDDY).available());
        assertFalse(Files.exists(home.resolve("runtime")));
    }
    @Test public void selectedToolsOnlyAndPartialFailureCanRetry() throws Exception {
        Path home=folder.newFolder().toPath(); var hooks=service(home);
        Files.createDirectory(home.resolve(".codebuddy")); Files.createDirectory(home.resolve(".workbuddy"));
        var buddy=hooks.preview(ToolHooks.Tool.CODEBUDDY,false);
        var work=hooks.preview(ToolHooks.Tool.WORKBUDDY,false);
        Files.writeString(hooks.file(ToolHooks.Tool.WORKBUDDY),"{\"foreign\":1}");
        var results=hooks.apply(List.of(buddy,work));
        assertFalse(results.get(0).failed()); assertTrue(results.get(1).failed());
        assertFalse(Files.exists(home.resolve(".trae-cn")));
        assertEquals("{\"foreign\":1}",Files.readString(hooks.file(ToolHooks.Tool.WORKBUDDY)));
        assertFalse(hooks.apply(List.of(hooks.preview(ToolHooks.Tool.WORKBUDDY,false))).get(0).failed());
        assertTrue(JsonParser.parseString(Files.readString(hooks.file(ToolHooks.Tool.WORKBUDDY))).getAsJsonObject().has("foreign"));
    }
    @Test public void adaptersAreIdempotentAndRemovePreservesThirdParty() throws Exception {
        Path home=folder.newFolder().toPath();
        for (var tool:ToolHooks.Tool.values()) {
            Path file=home.resolve(tool.directory).resolve(tool.file); Files.createDirectories(file.getParent());
            Files.writeString(file,"{\"env\":{\"A\":\"B\"},\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"node third-party.js\"}]}]}}");
            var adapter=new CodeBuddyHooks(file,home.resolve("中文 space/collector.exe"),tool.source);
            var applied=adapter.apply(); assertTrue(applied.changed()); assertNotNull(applied.backup());
            assertFalse(adapter.apply().changed());
            String text=Files.readString(file); assertTrue(text.contains("--source "+tool.source)); assertTrue(text.contains("third-party"));
            assertTrue(adapter.remove().changed());
            String removed=Files.readString(file); assertTrue(removed.contains("third-party")); assertTrue(removed.contains("env")); assertFalse(removed.contains("--source"));
        }
    }
    @Test public void commandsMatchEachWindowsShellAndOwnOnlyExactExecutable() throws Exception {
        Path home=folder.newFolder().toPath(); Path exe=home.resolve("中文 space/collector.exe");
        assertTrue(new CodeBuddyHooks(home.resolve("t.json"),exe,"trae-code").command().startsWith("& \""));
        var claude=new CodeBuddyHooks(home.resolve("c.json"),exe,"claude-code");
        assertFalse(claude.command().startsWith("&")); claude.apply();
        assertFalse(Files.readString(home.resolve("c.json")).contains("timeout"));
        assertTrue(CodeBuddyHooks.isManagedCommand("\"/c/中文 space/collector.exe\" --source claude-code","c:/中文 space/collector.exe"));
        assertFalse(CodeBuddyHooks.isManagedCommand("echo c:/collector.exe", "c:/collector.exe"));
        assertFalse(CodeBuddyHooks.isManagedCommand("c:/collector.exe.backup", "c:/collector.exe"));
    }
    @Test public void invalidJsonNeverOverwrittenAndPreviewRemainsReadOnly() throws Exception {
        Path home=folder.newFolder().toPath(); var hooks=service(home); Path file=hooks.file(ToolHooks.Tool.CODEBUDDY);Files.createDirectories(file.getParent());
        Files.writeString(file,"{broken");
        try { hooks.preview(ToolHooks.Tool.CODEBUDDY,false); fail(); } catch (RuntimeException expected) { }
        assertEquals("{broken",Files.readString(file));
        try(var entries=Files.list(file.getParent())) { assertEquals(1,entries.count()); }
    }
    @Test public void restoreOnlyOwnedEntriesAndRejectChangedBackup() throws Exception {
        Path home=folder.newFolder().toPath(); var tools=service(home);
        Path file=tools.file(ToolHooks.Tool.CODEBUDDY); Files.createDirectories(file.getParent());
        var adapter=new CodeBuddyHooks(file,home.resolve("runtime/collector.exe"));
        adapter.apply(); adapter.remove();
        var current=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        current.addProperty("newThirdPartySetting",42); Files.writeString(file,current.toString());
        var preview=tools.previewRestore(ToolHooks.Tool.CODEBUDDY);
        assertFalse(tools.apply(List.of(preview)).get(0).failed());
        var restored=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(42,restored.get("newThirdPartySetting").getAsInt());
        assertTrue(restored.has("hooks"));
        var next=tools.previewRestore(ToolHooks.Tool.CODEBUDDY);
        Files.writeString(next.restoreBackup(),"{}"); byte[] before=Files.readAllBytes(file);
        assertTrue(tools.apply(List.of(next)).get(0).failed());
        assertArrayEquals(before,Files.readAllBytes(file));
    }

    @Test public void skippedPreviewCannotBecomeUnpreviewedWriteAfterToolAppears() throws Exception {
        Path home=folder.newFolder().toPath();var tools=service(home);
        var preview=tools.preview(ToolHooks.Tool.CODEBUDDY,false);
        Files.createDirectories(tools.file(ToolHooks.Tool.CODEBUDDY).getParent());
        var results=tools.apply(List.of(preview));
        assertFalse(results.get(0).failed()); assertTrue(results.get(0).message().contains("跳过"));
        assertFalse(Files.exists(tools.file(ToolHooks.Tool.CODEBUDDY)));
        assertFalse(Files.exists(home.resolve("runtime")));
    }

}
