package top.chende.coursemind.auth;

import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.api.ApiClient;
import top.chende.coursemind.api.ApiException;
import top.chende.coursemind.config.CourseMindSettings;
import top.chende.coursemind.config.ServerUrl;
import top.chende.coursemind.config.SharedConfigStore;
import top.chende.coursemind.runtime.RuntimePaths;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 登录、身份与共享凭据的唯一入口（应用级）。
 *
 * <p>凭据落地顺序与 VS Code 扩展一致：auth.dat → identity.json → config.json；
 * 配置文件写入失败时按字节回滚 auth.dat，避免出现"IDEA 已登录、采集器仍是旧配置"。
 */
public final class AuthService {

    public enum State {
        /** 未配置服务器。 */
        NOT_CONFIGURED,
        /** 已配置但未登录。 */
        LOGGED_OUT,
        /** 已登录且凭据有效。 */
        LOGGED_IN,
        /** 共享凭据存在但 Token 已过期。 */
        EXPIRED,
        /** 共享凭据损坏或读取失败。 */
        ERROR
    }

    public record Snapshot(@NotNull State state, @Nullable AuthSession session, @Nullable String message) {
    }

    private final Object lock = new Object();
    private final RuntimePaths paths = RuntimePaths.getDefault();
    private final SharedAuthStore authStore = new SharedAuthStore(paths.authFile());
    private final SharedIdentityStore identityStore = new SharedIdentityStore(paths.identityFile());
    private final SharedConfigStore configStore = new SharedConfigStore(paths);

    private volatile Snapshot cached;

    public static AuthService getInstance() {
        return ApplicationManager.getApplication().getService(AuthService.class);
    }

    /** 非阻塞读取最近一次快照；首次调用返回"待刷新"状态，由调用方在后台线程执行 {@link #refresh()}。 */
    public @NotNull Snapshot snapshot() {
        Snapshot current = cached;
        return current != null ? current : new Snapshot(State.LOGGED_OUT, null, "正在读取登录状态");
    }

    /** 重新读取共享登录状态。会启动 DPAPI 进程，必须在后台线程调用。 */
    public @NotNull Snapshot refresh() {
        synchronized (lock) {
            Snapshot snapshot = loadSnapshot();
            cached = snapshot;
            return snapshot;
        }
    }

    private @NotNull Snapshot loadSnapshot() {
        String serverUrl = CourseMindSettings.getInstance().getServerUrl();
        if (ServerUrl.normalize(serverUrl).isEmpty()) {
            return new Snapshot(State.NOT_CONFIGURED, null, "请先在设置中填写 CourseMind 服务器地址");
        }
        if (!authStore.exists()) {
            return new Snapshot(State.LOGGED_OUT, null, null);
        }
        try {
            Optional<AuthSession> loaded = authStore.load();
            if (loaded.isEmpty()) {
                return new Snapshot(State.LOGGED_OUT, null, null);
            }
            AuthSession session = loaded.get();
            String expected = ServerUrl.serverIdOf(serverUrl);
            if (!expected.equals(ServerUrl.serverIdOf(session.serverId()))) {
                return new Snapshot(State.LOGGED_OUT, session,
                        "共享登录状态属于其他服务器（" + session.serverId() + "），请重新登录");
            }
            if (session.expiresAt().map(exp -> !exp.isAfter(Instant.now())).orElse(false)) {
                return new Snapshot(State.EXPIRED, session, "登录已过期，请重新登录");
            }
            return new Snapshot(State.LOGGED_IN, session, null);
        } catch (CourseMindException e) {
            return new Snapshot(State.ERROR, null, e.getMessage());
        }
    }

    /**
     * 登录并同步共享凭据。
     *
     * @throws CourseMindException 任何失败都抛出可直接展示的中文原因
     */
    public @NotNull AuthSession login(@NotNull String serverUrl,
                                      @NotNull String studentNo,
                                      @NotNull String password) {
        synchronized (lock) {
            String normalized = ServerUrl.normalize(serverUrl);
            ServerUrl.validate(normalized).ifPresent(message -> {
                throw new CourseMindException(message);
            });

            ApiClient.LoginResult result;
            try {
                result = new ApiClient(normalized).login(studentNo, password);
            } catch (ApiException e) {
                throw new CourseMindException(e.getMessage(), e);
            }

            CourseMindSettings settings = CourseMindSettings.getInstance();
            String serverId = ServerUrl.serverIdOf(normalized);
            AuthSession.Profile profile = new AuthSession.Profile(
                    result.profile().studentId(),
                    result.profile().studentNo(),
                    result.profile().name(),
                    result.profile().gender(),
                    result.profile().className(),
                    result.profile().classroom(),
                    null);
            AuthSession session = new AuthSession(UUID.randomUUID().toString(), serverId,
                    result.accessToken(), profile, Instant.now().toString());

            byte[] backup = authStore.exists() ? authStore.readRaw() : null;
            authStore.save(session);
            try {
                identityStore.write(profile);
                configStore.applyLogin(serverId, result.uploadKey(), profile.classroom());
            } catch (CourseMindException e) {
                rollback(backup);
                throw new CourseMindException("登录成功，但无法同步采集器配置：" + e.getMessage(), e);
            }

            settings.setServerUrl(serverId);
            settings.setLastStudentNo(profile.studentNo());
            cached = new Snapshot(State.LOGGED_IN, session, null);
            return session;
        }
    }

    /** 退出登录：清除共享凭据与上传密钥，保留服务器地址与采集器调优参数。 */
    public void logout() {
        synchronized (lock) {
            CourseMindException failure = null;
            try {
                authStore.clear();
            } catch (CourseMindException e) {
                failure = e;
            }
            try {
                identityStore.clear();
            } catch (CourseMindException e) {
                failure = failure == null ? e : failure;
            }
            try {
                configStore.clearUploadKey();
            } catch (CourseMindException e) {
                failure = failure == null ? e : failure;
            }
            cached = new Snapshot(State.LOGGED_OUT, null, null);
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** 连接检测（不要求已登录）。 */
    public @NotNull ApiClient.HealthResult testConnection(@NotNull String serverUrl) {
        String normalized = ServerUrl.normalize(serverUrl);
        ServerUrl.validate(normalized).ifPresent(message -> {
            throw new CourseMindException(message);
        });
        try {
            return new ApiClient(normalized).health();
        } catch (ApiException e) {
            throw new CourseMindException(e.getMessage(), e);
        }
    }

    /** 共享 config.json 中登记的服务器地址，用于设置页展示"生效来源"。 */
    public @NotNull Optional<String> sharedServerUrl() {
        try {
            return configStore.sharedServerUrl();
        } catch (CourseMindException e) {
            return Optional.empty();
        }
    }

    public boolean sharedConfigHasUploadKey() {
        try {
            return configStore.hasUploadKey();
        } catch (CourseMindException e) {
            return false;
        }
    }

    public @NotNull RuntimePaths runtimePaths() {
        return paths;
    }

    private void rollback(byte @Nullable [] backup) {
        try {
            if (backup == null) {
                authStore.clear();
            } else {
                authStore.restoreRaw(backup);
            }
        } catch (CourseMindException ignored) {
            // 回滚失败已在原始异常中体现，这里不再覆盖用户看到的原因
        }
    }
}
