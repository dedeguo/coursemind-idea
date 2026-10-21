package top.chende.coursemind.auth;

import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import top.chende.coursemind.config.*;
import top.chende.coursemind.runtime.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Real HTTP + DPAPI, with only temporary runtime files and loopback test services. */
public class AuthServiceIntegrationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private HttpServer publicServer, campusServer;
    private String publicUrl, campusUrl;
    private CourseMindSettings settings;
    private AuthService service;
    private RuntimePaths paths;
    private final AtomicBoolean rejectCampus = new AtomicBoolean();
    private final AtomicBoolean rejectPublic = new AtomicBoolean();
    private final AtomicBoolean wrongCampusIdentity = new AtomicBoolean();
    @Before public void setUp() throws Exception {
        Assume.assumeTrue(Dpapi.isSupported());
        publicServer = server(false); campusServer = server(true);
        publicUrl = url(publicServer); campusUrl = url(campusServer);
        settings = new CourseMindSettings(); settings.setConnection(new ConnectionProfile(publicUrl, campusUrl, "public"));
        paths = RuntimePaths.of(folder.newFolder().toPath()); service = new AuthService(paths, settings);
    }
    @After public void tearDown() {
        if (publicServer != null) publicServer.stop(0);
        if (campusServer != null) campusServer.stop(0);
    }
    private HttpServer server(boolean campus) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/student/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String data = "{\"student_id\":7,\"student_no\":\"20230001\",\"name\":\"测试学生\",\"class_name\":\"测试班级\",\"classroom\":\"1-339\",\"access_token\":\"old-token\",\"upload_key\":\"test-key\"}";
            if (path.endsWith("change-password")) {
                assertEquals("Bearer old-token", exchange.getRequestHeaders().getFirst("Authorization"));
                if (body.contains("wrong")) { status = 400; data = "{}"; }
                else data = "{\"access_token\":\"new-token\"}";
            }
            if (campus && rejectCampus.get()) status = 401;
            if (!campus && rejectPublic.get()) status = 401;
            if (campus && wrongCampusIdentity.get()) data = data.replace("\"student_id\":7", "\"student_id\":8");
            byte[] response = ("{\"code\":" + (status == 200 ? 0 : status) + ",\"message\":\"" + (status == 400 ? "旧密码不正确" : "test") + "\",\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start(); return server;
    }
    private String url(HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort(); }
    private AuthSession login() { return service.login(publicUrl, "20230001", "password"); }
    @Test public void entrySwitchKeepsCredentialsAndQueueButInstanceChangeClearsIdentity() throws Exception {
        AuthSession session = login(); byte[] auth = Files.readAllBytes(paths.authFile());
        Files.createDirectories(paths.cacheDir()); Path queue = paths.cacheDir().resolve("pending.json");
        String pending = "[{\"server_id\":\"" + publicUrl + "\",\"record\":{\"student_id\":7}}]";
        Files.writeString(queue, pending);
        service.applyConnection(new ConnectionProfile(publicUrl, campusUrl, "campus"), true);
        assertArrayEquals(auth, Files.readAllBytes(paths.authFile()));
        SharedConfigStore store = new SharedConfigStore(paths);
        assertEquals(campusUrl, store.sharedServerUrl().orElseThrow()); assertEquals(publicUrl, store.sharedServerId());
        assertTrue(store.hasUploadKey()); assertEquals(session.sessionId(), service.refresh().session().sessionId());
        service.applyConnection(new ConnectionProfile(campusUrl, publicUrl, "public"), true);
        assertFalse(Files.exists(paths.authFile())); assertFalse(store.hasUploadKey()); assertEquals(pending, Files.readString(queue));
    }
    @Test public void rejectedEntryLeavesSettingsAndSharedFilesUntouched() throws Exception {
        login(); byte[] config = Files.readAllBytes(paths.configFile()); rejectCampus.set(true);
        assertThrows(RuntimeException.class, () -> service.applyConnection(new ConnectionProfile(publicUrl, campusUrl, "campus"), true));
        assertEquals(publicUrl, settings.getServerUrl()); assertArrayEquals(config, Files.readAllBytes(paths.configFile()));
    }
    @Test public void passwordRotatesSessionAndStaleLogoutCannotClearNewLogin() throws Exception {
        AuthSession old = login();
        AuthSession next = service.changePassword(old.sessionId(), "password", "new-password");
        assertNotEquals(old.sessionId(), next.sessionId()); assertEquals("new-token", new SharedAuthStore(paths.authFile()).load().orElseThrow().accessToken());
        assertTrue(new SharedConfigStore(paths).hasUploadKey());
        assertThrows(RuntimeException.class, () -> service.logout(old.sessionId()));
        assertEquals(next.sessionId(), new SharedAuthStore(paths.authFile()).load().orElseThrow().sessionId());
        service.logout(next.sessionId()); assertEquals(AuthService.State.LOGGED_OUT, service.refresh().state());
    }
    @Test public void wrongPasswordDoesNotChangeSharedToken() throws Exception {
        AuthSession old = login(); byte[] bytes = Files.readAllBytes(paths.authFile());
        assertThrows(RuntimeException.class, () -> service.changePassword(old.sessionId(), "wrong", "new-password"));
        assertArrayEquals(bytes, Files.readAllBytes(paths.authFile()));
    }
    @Test public void externalLogoutIsObservedAndNeverResurrected() throws Exception {
        login(); Files.delete(paths.authFile());
        assertEquals(AuthService.State.LOGGED_OUT, service.refresh().state()); assertFalse(Files.exists(paths.authFile()));
        assertFalse(new SharedConfigStore(paths).hasUploadKey());
    }
    @Test public void revokedTokenStopsCredentialUseAndCollection() throws Exception {
        login(); rejectPublic.set(true);
        assertEquals(AuthService.State.EXPIRED, service.refresh().state());
        assertNull(service.snapshot().session()); assertFalse(new SharedConfigStore(paths).hasUploadKey());
    }
    @Test public void wrongIdentityAtCampusIsRejectedWithoutChangingCredentials() throws Exception {
        login(); byte[] before = Files.readAllBytes(paths.authFile()); wrongCampusIdentity.set(true);
        assertThrows(RuntimeException.class, () -> service.applyConnection(new ConnectionProfile(publicUrl, campusUrl, "campus"), true));
        assertArrayEquals(before, Files.readAllBytes(paths.authFile())); assertEquals(publicUrl, settings.getServerUrl());
    }
    @Test public void loginPersistenceFailureRollsBackAllThreeSharedFiles() throws Exception {
        login();
        byte[] auth = Files.readAllBytes(paths.authFile()), identity = Files.readAllBytes(paths.identityFile());
        Files.writeString(paths.configFile(), "{invalid-json");
        assertThrows(RuntimeException.class, this::login);
        assertArrayEquals(auth, Files.readAllBytes(paths.authFile()));
        assertArrayEquals(identity, Files.readAllBytes(paths.identityFile()));
        assertEquals("{invalid-json", Files.readString(paths.configFile()));
    }
    @Test public void invalidNewPasswordNeverContactsServer() {
        assertThrows(RuntimeException.class, () -> AuthService.validatePassword("old", "short"));
        assertThrows(RuntimeException.class, () -> AuthService.validatePassword("", "long-enough"));
    }
    @Test public void staleStatistics401CannotDeleteRotatedSharedSession() throws Exception {
        AuthSession old = login();
        AuthSession next = service.changePassword(old.sessionId(), "password", "new-password");
        service.expireSession(old.sessionId());
        assertEquals(next.sessionId(), new SharedAuthStore(paths.authFile()).load().orElseThrow().sessionId());
        service.expireSession(next.sessionId());
        assertFalse(Files.exists(paths.authFile()));
        assertFalse(new SharedConfigStore(paths).hasUploadKey());
        assertEquals(AuthService.State.EXPIRED, service.snapshot().state());
    }
    @Test public void statisticsScopeCheckRejectsChangedEntryAndExternalSession() throws Exception {
        AuthSession old = login();
        assertTrue(service.isCurrentSession(old, settings.connection()));
        assertFalse(service.isCurrentSession(old, new ConnectionProfile(publicUrl, campusUrl, "campus")));
        new SharedAuthStore(paths.authFile()).save(new AuthSession("external-session", old.serverId(), old.accessToken(), old.profile(), old.updatedAt()));
        assertFalse(service.isCurrentSession(old, settings.connection()));
    }
    @Test public void temporaryNetworkFailureKeepsVerifiedIdentityForStaleStatsDisplay() {
        login(); publicServer.stop(0);
        AuthService.Snapshot snapshot = service.refresh();
        assertEquals(AuthService.State.LOGGED_IN, snapshot.state());
        assertNotNull(snapshot.session()); assertTrue(snapshot.message().contains("网络"));
    }
    @Test public void sharedPauseIsSavedAndStaleSettingsCannotOverwriteOtherIde() throws Exception {
        login();
        service.applyConnection(settings.connection(), false, true);
        var store = new SharedConfigStore(paths); assertFalse(store.collectionEnabled());
        store.setCollectionEnabled(true);
        byte[] before=Files.readAllBytes(paths.configFile());
        assertThrows(RuntimeException.class, () -> service.applyConnection(settings.connection(), false, false));
        assertArrayEquals(before, Files.readAllBytes(paths.configFile()));
        assertTrue(store.collectionEnabled());
    }

}
