package top.chende.coursemind.auth;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.api.*;
import top.chende.coursemind.config.*;
import top.chende.coursemind.runtime.*;
import java.nio.file.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared authentication authority. All slow methods must run off the EDT. */
public final class AuthService {
    public enum State { NOT_CONFIGURED, LOGGED_OUT, LOGGED_IN, EXPIRED, ERROR }
    public record Snapshot(@NotNull State state, @Nullable AuthSession session, @Nullable String message) { }
    private final Object lock = new Object();
    private final RuntimePaths paths;
    private final CourseMindSettings settings;
    private final SharedAuthStore authStore;
    private final SharedIdentityStore identityStore;
    private final SharedConfigStore configStore;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot cached;
    private volatile ConnectionProfile publishedConnection;
    public AuthService() { this(RuntimePaths.getDefault(), CourseMindSettings.getInstance()); }
    public AuthService(RuntimePaths paths, CourseMindSettings settings) {
        this.paths = paths; this.settings = settings;
        authStore = new SharedAuthStore(paths.authFile());
        identityStore = new SharedIdentityStore(paths.identityFile());
        configStore = new SharedConfigStore(paths);
    }
    public static AuthService getInstance() { return ApplicationManager.getApplication().getService(AuthService.class); }
    public void subscribe(Runnable listener, Disposable owner) {
        listeners.add(listener); Disposer.register(owner, () -> listeners.remove(listener));
    }
    private Snapshot publish(Snapshot next) {
        Snapshot previous = cached; cached = next;
        ConnectionProfile connection = settings.connection();
        boolean connectionChanged = !connection.equals(publishedConnection);
        publishedConnection = connection;
        if (connectionChanged || !fingerprint(previous).equals(fingerprint(next))) {
            for (Runnable listener : listeners) {
                try { listener.run(); } catch (RuntimeException ignored) { /* A closed view cannot undo committed auth. */ }
            }
        }
        return next;
    }
    private static String fingerprint(Snapshot s) {
        if (s == null) return "";
        return s.state() + ":" + (s.session() == null ? "" : s.session().sessionId() + s.session().updatedAt()) + ":" + s.message();
    }
    public Snapshot snapshot() {
        return cached == null ? new Snapshot(State.LOGGED_OUT, null, "正在读取登录状态") : cached;
    }
    public Snapshot refresh() {
        synchronized (lock) {
            return SharedStateLock.run(paths, () -> {
                settings.adoptShared(configStore.sharedServerUrl().orElse(null), configStore.sharedServerId());
                byte[] before = rawAuth();
                Snapshot next = loadSnapshot(true);
                if (next.state() == State.EXPIRED && Arrays.equals(before, rawAuth())) configStore.clearUploadKey();
                if (next.state() == State.LOGGED_OUT && before == null && rawAuth() == null) {
                    transaction(() -> { configStore.clearUploadKey(); identityStore.clear(); });
                }
                return publish(next);
            });
        }
    }
    public void initializeConnection() {
        settings.adoptShared(configStore.sharedServerUrl().orElse(null), configStore.sharedServerId());
    }
    private Snapshot loadSnapshot(boolean verify) {
        ConnectionProfile connection = settings.connection();
        AuthSession observed = null;
        if (ServerUrl.validate(connection.effectiveUrl()).isPresent()) return new Snapshot(State.NOT_CONFIGURED, null, "请先检查服务器地址");
        try {
            Optional<AuthSession> loaded = authStore.load();
            if (loaded.isEmpty()) return new Snapshot(State.LOGGED_OUT, null, null);
            AuthSession session = loaded.get();
            observed = session;
            if (!connection.serverId().equals(ServerUrl.normalize(session.serverId()))) {
                return new Snapshot(State.LOGGED_OUT, null, "共享登录属于其他服务实例；请确认连接设置后登录");
            }
            if (session.expiresAt().map(exp -> !exp.isAfter(Instant.now())).orElse(false)) {
                return new Snapshot(State.EXPIRED, null, "登录已过期，请重新登录");
            }
            if (verify) {
                byte[] before = authStore.readRaw();
                ApiClient.StudentProfile profile = new ApiClient(connection.effectiveUrl()).me(session.accessToken());
                assertUnchanged(before, connection);
                if (profile.studentId() != session.profile().studentId()
                        || !Objects.equals(profile.environmentId(), session.environmentId())
                        || !Objects.equals(profile.accountId(), session.accountId())) {
                    return new Snapshot(State.ERROR, null, "服务器返回的身份与共享会话不一致，已停止使用该身份");
                }
                session = new AuthSession(session.sessionId(), session.serverId(), session.accessToken(),
                        profileOf(profile, session.profile().projectId()), session.updatedAt(), session.environmentId(), session.accountId());
            }
            return new Snapshot(State.LOGGED_IN, session, null);
        } catch (ApiException e) {
            if (!e.isAuthenticationFailure() && cached != null && cached.state() == State.LOGGED_IN
                    && observed != null && cached.session() != null && cached.session().sessionId().equals(observed.sessionId())
                    && settings.connection().equals(connection)) {
                return new Snapshot(State.LOGGED_IN, cached.session(), "暂时无法重新校验身份，请检查网络；统计可能未更新");
            }
            return new Snapshot(e.isAuthenticationFailure() ? State.EXPIRED : State.ERROR, null,
                    e.isAuthenticationFailure() ? "登录失效或权限不足，请重新登录" : "无法校验共享登录：" + e.getMessage());
        } catch (CourseMindException e) { return new Snapshot(State.ERROR, null, e.getMessage()); }
    }
    public boolean isCurrentSession(AuthSession session, ConnectionProfile connection) {
        synchronized (lock) {
            return SharedStateLock.run(paths, () -> {
                AuthSession current = authStore.load().orElse(null);
                return settings.connection().equals(connection) && current != null
                        && current.sessionId().equals(session.sessionId()) && current.accessToken().equals(session.accessToken())
                        && current.profile().studentId() == session.profile().studentId()
                        && current.serverId().equals(connection.serverId());
            });
        }
    }
    public void expireSession(String expectedSessionId) {
        synchronized (lock) {
            SharedStateLock.run(paths, () -> {
                AuthSession current = authStore.load().orElse(null);
                if (current != null && current.sessionId().equals(expectedSessionId)) {
                    transaction(() -> { configStore.clearUploadKey(); authStore.clear(); identityStore.clear(); });
                    publish(new Snapshot(State.EXPIRED, null, "登录已过期，请重新登录"));
                } else publish(loadSnapshot(false));
                return null;
            });
        }
    }
    public AuthSession login(String serverUrl, String studentNo, String password) {
        synchronized (lock) {
            return SharedStateLock.run(paths, () -> {
                ConnectionProfile connection = settings.connection(); connection.validate();
                if (!connection.effectiveUrl().equals(ServerUrl.normalize(serverUrl))) throw new CourseMindException("连接设置已变化，请重试登录");
                byte[] before = rawAuth();
                ApiClient.LoginResult result;
                try { result = new ApiClient(connection.effectiveUrl()).login(studentNo, password); }
                catch (ApiException e) { throw new CourseMindException(e.getMessage(), e); }
                assertUnchanged(before, connection);
                AuthSession.Profile profile = profileOf(result.profile(), null);
                AuthSession session = new AuthSession(UUID.randomUUID().toString(), connection.serverId(),
                        result.accessToken(), profile, Instant.now().toString(), result.profile().environmentId(), result.profile().accountId());
                transaction(() -> {
                    // Remove the old upload credential before identity changes.
                    configStore.clearUploadKey();
                    authStore.save(session); identityStore.write(profile);
                    if (session.environmentId() == null) configStore.applyLogin(connection.effectiveUrl(), connection.serverId(), result.uploadKey(), profile.classroom());
                    else configStore.applyEducationLogin(connection.effectiveUrl(), connection.serverId(), session.environmentId(), session.accountId(), profile.studentId(), profile.classroom());
                });
                settings.setLastStudentNo(profile.studentNo());
                publish(new Snapshot(State.LOGGED_IN, session, null)); return session;
            });
        }
    }
    public void logout() { logout(snapshot().session() == null ? null : snapshot().session().sessionId()); }
    public void logout(String expectedSessionId) {
        synchronized (lock) {
            SharedStateLock.run(paths, () -> {
                AuthSession current = authStore.load().orElse(null);
                if (current != null && !Objects.equals(expectedSessionId, current.sessionId())) {
                    publish(loadSnapshot(false)); throw new CourseMindException("共享账号已在其他窗口变化，请刷新后重试退出");
                }
                transaction(() -> { configStore.clearUploadKey(); authStore.clear(); identityStore.clear(); });
                publish(new Snapshot(State.LOGGED_OUT, null, null)); return null;
            });
        }
    }
    public AuthSession changePassword(String expectedSessionId, String oldPassword, String newPassword) {
        validatePassword(oldPassword, newPassword);
        synchronized (lock) {
            return SharedStateLock.run(paths, () -> {
                ConnectionProfile connection = settings.connection();
                AuthSession current = requireSession(expectedSessionId);
                byte[] before = rawAuth();
                String token;
                try { token = new ApiClient(connection.effectiveUrl()).changePassword(current.accessToken(), oldPassword, newPassword); }
                catch (ApiException e) {
                    if (e.isAuthenticationFailure()) publish(new Snapshot(State.EXPIRED, null, "登录已失效，请重新登录"));
                    throw new CourseMindException(e.getMessage(), e);
                }
                // Never replace a newer cross-IDE login, even if an old extension ignores the lease.
                try { assertUnchanged(before, connection); }
                catch (CourseMindException e) {
                    publish(loadSnapshot(false));
                    throw new CourseMindException("密码已修改，但共享身份已变化，本次未覆盖其他账号；请使用新密码重新登录", e);
                }
                AuthSession next = new AuthSession(UUID.randomUUID().toString(), current.serverId(), token,
                        current.profile(), Instant.now().toString(), current.environmentId(), current.accountId());
                try { authStore.save(next); }
                catch (CourseMindException e) {
                    try { configStore.clearUploadKey(); } catch (CourseMindException cleanup) { e.addSuppressed(cleanup); }
                    publish(new Snapshot(State.EXPIRED, null, "密码已修改，但新凭据保存失败，请使用新密码重新登录"));
                    throw new CourseMindException("密码已修改，但共享登录状态更新失败，请使用新密码重新登录", e);
                }
                publish(new Snapshot(State.LOGGED_IN, next, null)); return next;
            });
        }
    }
    public static void validatePassword(String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isEmpty()) throw new CourseMindException("请填写当前密码");
        if (oldPassword.codePointCount(0, oldPassword.length()) > 128) throw new CourseMindException("当前密码不能超过 128 位");
        int count = newPassword == null ? 0 : newPassword.codePointCount(0, newPassword.length());
        if (count < 8 || count > 128) throw new CourseMindException("新密码须为 8～128 位");
    }
    private AuthSession requireSession(String expectedSessionId) {
        Snapshot current = loadSnapshot(false);
        if (current.state() != State.LOGGED_IN || current.session() == null || !current.session().sessionId().equals(expectedSessionId)) {
            publish(current); throw new CourseMindException("共享登录已变化或失效，请刷新后重试");
        }
        return current.session();
    }
    /** Persist only after target /me verifies the same student for a same-instance entry change. */
    public void applyConnection(ConnectionProfile next, boolean collectEnabled) {
        applyConnection(next, collectEnabled, null);
    }
    public void applyConnection(ConnectionProfile next, boolean collectEnabled, Boolean expectedCollectionEnabled) {
        next.validate();
        synchronized (lock) {
            SharedStateLock.run(paths, () -> {
                if (expectedCollectionEnabled != null && configStore.collectionEnabled() != expectedCollectionEnabled)
                    throw new CourseMindException("共享采集开关已被其他 IDE 修改，请重新打开设置后重试");
                ConnectionProfile previous = settings.connection();
                byte[] before = rawAuth();
                AuthSession session = authStore.load().orElse(null);
                String sharedId = configStore.sharedServerId();
                boolean sameInstance = next.serverId().equals(previous.serverId())
                        && (session == null || next.serverId().equals(session.serverId()))
                        && (sharedId.isEmpty() || next.serverId().equals(sharedId));
                if (session != null && sameInstance && !next.effectiveUrl().equals(previous.effectiveUrl())) {
                    try {
                        ApiClient.StudentProfile profile = new ApiClient(next.effectiveUrl()).me(session.accessToken());
                        if (profile.studentId() != session.profile().studentId() || !Objects.equals(profile.environmentId(), session.environmentId())
                                || !Objects.equals(profile.accountId(), session.accountId())) throw new CourseMindException("目标入口身份或数据环境不一致，连接配置未保存");
                    } catch (ApiException e) { throw new CourseMindException("目标入口校验失败，原配置保留：" + e.getMessage(), e); }
                }
                assertUnchanged(before, previous);
                transaction(() -> {
                    if (!sameInstance) { configStore.clearUploadKey(); authStore.clear(); identityStore.clear(); }
                    configStore.syncServer(next.effectiveUrl(), next.serverId());
                    configStore.setCollectionEnabled(collectEnabled);
                });
                settings.setConnection(next); settings.setCollectEnabled(collectEnabled);
                publish(loadSnapshot(false)); return null;
            });
        }
    }
    private void assertUnchanged(byte[] before, ConnectionProfile connection) {
        if (!Arrays.equals(before, rawAuth()) || !settings.connection().equals(connection)) {
            throw new CourseMindException("共享身份或连接配置刚被其他 IDE 修改，本次未覆盖，请刷新后重试");
        }
    }
    private byte[] rawAuth() { return authStore.exists() ? authStore.readRaw() : null; }
    private void transaction(Runnable action) {
        Path[] files = {paths.authFile(), paths.identityFile(), paths.configFile()};
        byte[][] before = new byte[files.length][];
        try {
            for (int i = 0; i < files.length; i++) before[i] = Files.exists(files[i]) ? Files.readAllBytes(files[i]) : null;
            action.run();
        } catch (IOException e) { throw new CourseMindException("无法备份共享配置，本次未修改", e); }
        catch (RuntimeException error) {
            for (int i = files.length - 1; i >= 0; i--) {
                try { if (before[i] == null) Files.deleteIfExists(files[i]); else AtomicFiles.write(files[i], before[i]); }
                catch (IOException rollbackError) { error.addSuppressed(rollbackError); }
            }
            throw error;
        }
    }
    private static AuthSession.Profile profileOf(ApiClient.StudentProfile p, Integer projectId) {
        return new AuthSession.Profile(p.studentId(), p.studentNo(), p.name(), p.gender(), p.className(), p.classroom(), projectId);
    }
    public ApiClient.HealthResult testConnection(String serverUrl) {
        ServerUrl.validate(serverUrl).ifPresent(message -> { throw new CourseMindException(message); });
        try { return new ApiClient(serverUrl).health(); }
        catch (ApiException e) { throw new CourseMindException(e.getMessage(), e); }
    }
    public Optional<String> sharedServerUrl() {
        try { return configStore.sharedServerUrl(); } catch (CourseMindException e) { return Optional.empty(); }
    }
    public Optional<String> sharedServerId() {
        String id = configStore.sharedServerId();
        return id.isBlank() ? Optional.empty() : Optional.of(id);
    }
    public boolean sharedConfigHasUploadKey() {
        try { return configStore.hasUploadKey(); } catch (CourseMindException e) { return false; }
    }
    public RuntimePaths runtimePaths() { return paths; }
}
