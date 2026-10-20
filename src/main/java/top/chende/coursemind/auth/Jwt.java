package top.chende.coursemind.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 只读取 JWT 载荷以展示有效期，不校验签名（签名校验属于服务端职责）。
 */
public final class Jwt {

    private Jwt() {
    }

    public static @NotNull Optional<JsonObject> payload(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            return Optional.of(JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static @NotNull Optional<Instant> expiresAt(String token) {
        return payload(token)
                .filter(json -> json.has("exp") && json.get("exp").isJsonPrimitive())
                .map(json -> Instant.ofEpochSecond(json.get("exp").getAsLong()));
    }

    public static boolean isExpired(String token, @NotNull Instant now) {
        return expiresAt(token).map(exp -> !exp.isAfter(now)).orElse(false);
    }
}
