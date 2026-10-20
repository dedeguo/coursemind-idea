package top.chende.coursemind.config;

import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerUrlTest {

    @Test
    public void addsHttpSchemeWhenMissing() {
        assertEquals("http://8.140.216.36:29236", ServerUrl.normalize("8.140.216.36:29236"));
    }

    @Test
    public void trimsWhitespaceAndTrailingSlashes() {
        assertEquals("https://cm.example.com/api",
                ServerUrl.normalize("  https://cm.example.com/api///  "));
    }

    @Test
    public void keepsPortAndPath() {
        assertEquals("http://10.0.0.5:8080", ServerUrl.normalize("http://10.0.0.5:8080/"));
    }

    @Test
    public void rejectsEmptyInput() {
        Optional<String> error = ServerUrl.validate("   ");
        assertTrue(error.isPresent());
    }

    @Test
    public void rejectsUnsupportedScheme() {
        assertTrue(ServerUrl.validate("ftp://example.com").isPresent());
    }

    @Test
    public void rejectsQueryAndFragment() {
        assertTrue(ServerUrl.validate("https://example.com/path?x=1").isPresent());
        assertTrue(ServerUrl.validate("https://example.com/path#frag").isPresent());
    }

    @Test
    public void acceptsPlainHttpForCampusTest() {
        assertFalse(ServerUrl.validate("http://10.1.2.3:29236").isPresent());
        assertFalse(ServerUrl.isHttps("10.1.2.3:29236"));
        assertTrue(ServerUrl.isHttps("https://cm.example.com"));
    }

    @Test
    public void serverIdIgnoresTrailingSlash() {
        assertEquals(ServerUrl.serverIdOf("http://host:1"),
                ServerUrl.serverIdOf("http://host:1/"));
    }
}
