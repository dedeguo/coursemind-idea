package top.chende.coursemind.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.runtime.RuntimePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SharedConfigStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private SharedConfigStore store(Path root) {
        return new SharedConfigStore(RuntimePaths.of(root));
    }

    private JsonObject read(Path root) throws IOException {
        Path file = root.resolve("config.json");
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    public void applyLoginCreatesMinimalConfigWithDefaults() throws IOException {
        Path root = folder.newFolder().toPath();
        store(root).applyLogin("http://10.0.0.1:29236/", "upload-key", "1-339");

        JsonObject json = read(root);
        assertEquals("http://10.0.0.1:29236", json.get("server_url").getAsString());
        assertEquals("http://10.0.0.1:29236", json.get("server_id").getAsString());
        assertEquals("upload-key", json.get("upload_key").getAsString());
        assertEquals("1-339", json.get("classroom").getAsString());
        assertEquals(10, json.get("cache_max_mb").getAsInt());
        assertTrue(store(root).hasUploadKey());
    }

    @Test
    public void applyLoginKeepsUnknownFieldsAndExistingClassroom() throws IOException {
        Path root = folder.newFolder().toPath();
        Files.writeString(root.resolve("config.json"),
                "{\"server_url\":\"http://old\",\"cache_max_mb\":10,\"custom_flag\":42,\"classroom\":\"9-1\"}",
                StandardCharsets.UTF_8);

        store(root).applyLogin("http://new:1", "k", "1-339");

        JsonObject json = read(root);
        assertEquals(42, json.get("custom_flag").getAsInt());
        assertEquals("9-1", json.get("classroom").getAsString());
        assertEquals("http://new:1", json.get("server_url").getAsString());
    }

    @Test
    public void clearUploadKeyKeepsServerAndTuning() throws IOException {
        Path root = folder.newFolder().toPath();
        store(root).applyLogin("http://host:1", "k", null);

        store(root).clearUploadKey();

        JsonObject json = read(root);
        assertFalse(json.has("upload_key"));
        assertEquals("http://host:1", json.get("server_url").getAsString());
        assertTrue(json.has("cache_max_records"));
        assertFalse(store(root).hasUploadKey());
    }

    @Test
    public void corruptConfigIsNotOverwritten() throws IOException {
        Path root = folder.newFolder().toPath();
        Path file = root.resolve("config.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);

        try {
            store(root).applyLogin("http://host:1", "k", null);
            fail("损坏的 config.json 不应被覆盖");
        } catch (CourseMindException expected) {
            assertEquals("{ not json", Files.readString(file, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void sharedServerUrlToleratesTrailingSlash() throws IOException {
        Path root = folder.newFolder().toPath();
        Files.writeString(root.resolve("config.json"), "{\"server_url\":\"http://host:1/\"}", StandardCharsets.UTF_8);
        assertEquals("http://host:1", store(root).sharedServerUrl().orElseThrow());
    }
}
