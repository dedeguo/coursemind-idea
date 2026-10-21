package top.chende.coursemind.config;

import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * 服务器地址的规范化与校验。
 *
 * <p>与 VS Code 扩展的 {@code normalizeServerUrl} 保持同一口径：去首尾空白、去尾斜杠；
 * 不同之处是插件侧额外接受"只填 host:port"的输入，默认补 http://，并在设置页提示非加密连接。
 */
public final class ServerUrl {

    public static final String SCHEME_HTTP = "http";
    public static final String SCHEME_HTTPS = "https";

    private ServerUrl() {
    }

    /**
     * 规范化地址：补 scheme、去首尾空白、去尾斜杠。无法解析时返回去空白后的原值，由 {@link #validate} 报错。
     */
    public static @NotNull String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!value.contains("://")) {
            value = SCHEME_HTTP + "://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 校验并返回规范化后的地址；不合法时返回错误提示（中文，可直接展示）。
     */
    public static @NotNull Optional<String> validate(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return Optional.of("请填写服务器地址");
        }
        URI uri;
        try {
            uri = new URI(normalized);
        } catch (URISyntaxException e) {
            return Optional.of("服务器地址格式不正确，请检查后重试");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SCHEME_HTTP.equals(scheme) && !SCHEME_HTTPS.equals(scheme)) {
            return Optional.of("服务器地址只支持 http:// 或 https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return Optional.of("服务器地址缺少主机名或 IP");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            return Optional.of("服务器地址不能包含查询参数或锚点");
        }
        if (normalized.chars().anyMatch(Character::isWhitespace)) {
            return Optional.of("服务器地址不能包含空格");
        }
        return Optional.empty();
    }

    /** 旧单地址标识的规范化；双网络实例身份使用 ConnectionProfile.serverId()（公网地址）。 */
    public static @NotNull String serverIdOf(String raw) {
        return normalize(raw);
    }

    public static boolean isHttps(String raw) {
        return normalize(raw).toLowerCase(Locale.ROOT).startsWith(SCHEME_HTTPS + "://");
    }
}
