package top.chende.coursemind.auth;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JwtTest {

    private static String token(long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"1\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".signature";
    }

    @Test
    public void readsExpiryFromPayload() {
        long exp = 1_900_000_000L;
        assertEquals(Instant.ofEpochSecond(exp), Jwt.expiresAt(token(exp)).orElseThrow());
    }

    @Test
    public void detectsExpiredToken() {
        String expired = token(1_600_000_000L);
        assertTrue(Jwt.isExpired(expired, Instant.ofEpochSecond(1_700_000_000L)));
        assertFalse(Jwt.isExpired(expired, Instant.ofEpochSecond(1_500_000_000L)));
    }

    @Test
    public void malformedTokenHasNoExpiry() {
        assertTrue(Jwt.expiresAt("not-a-token").isEmpty());
        assertFalse(Jwt.isExpired("not-a-token", Instant.now()));
    }
}
