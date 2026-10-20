package top.chende.coursemind.auth;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * auth.dat 读写往返测试。仅 Windows 可执行（DPAPI），CI 在 Linux 上自动跳过。
 */
public class SharedAuthStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static AuthSession sample() {
        return new AuthSession(
                "session-1",
                "http://10.0.0.1:29236",
                "header.payload.signature",
                new AuthSession.Profile(7, "20230001", "张三", "男", "计算机2301", "1-339", null),
                Instant.now().toString());
    }

    @Test
    public void missingFileMeansLoggedOut() throws IOException {
        Path root = folder.newFolder().toPath();
        SharedAuthStore store = new SharedAuthStore(root.resolve("auth.dat"));
        assertFalse(store.exists());
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void roundTripsSessionThroughDpapi() throws IOException {
        Assume.assumeTrue("DPAPI 仅 Windows 可用", Dpapi.isSupported());
        Path root = folder.newFolder().toPath();
        SharedAuthStore store = new SharedAuthStore(root.resolve("auth.dat"));

        store.save(sample());
        assertTrue(store.exists());

        Optional<AuthSession> loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals("session-1", loaded.get().sessionId());
        assertEquals("http://10.0.0.1:29236", loaded.get().serverId());
        assertEquals("20230001", loaded.get().profile().studentNo());
        assertEquals("计算机2301", loaded.get().profile().className());

        // 密文不得包含明文 Token
        byte[] cipher = Files.readAllBytes(root.resolve("auth.dat"));
        assertFalse(new String(cipher, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("header.payload.signature"));

        store.clear();
        assertFalse(store.exists());
    }

    @Test
    public void restoreRawKeepsPreviousSession() throws IOException {
        Assume.assumeTrue("DPAPI 仅 Windows 可用", Dpapi.isSupported());
        Path root = folder.newFolder().toPath();
        SharedAuthStore store = new SharedAuthStore(root.resolve("auth.dat"));
        store.save(sample());
        byte[] backup = store.readRaw();

        store.clear();
        store.restoreRaw(backup);

        assertTrue(store.load().isPresent());
    }
}
