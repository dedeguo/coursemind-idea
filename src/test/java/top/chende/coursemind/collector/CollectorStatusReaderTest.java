package top.chende.coursemind.collector;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import top.chende.coursemind.auth.AuthSession;
import top.chende.coursemind.config.*;
import top.chende.coursemind.runtime.RuntimePaths;
import java.nio.file.*;
import java.time.*;
import static org.junit.Assert.*;

public class CollectorStatusReaderTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final ConnectionProfile connection = new ConnectionProfile("https://public", "http://campus", "public");
    private final AuthSession session = new AuthSession("s", "https://public", "unused",
            new AuthSession.Profile(7,"7","测试",null,"班级","教室",null),"2026-10-21T08:00:00Z");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T09:00:00Z"),ZoneOffset.UTC);
    private RuntimePaths setup() throws Exception {
        RuntimePaths paths = RuntimePaths.of(folder.newFolder().toPath()); Files.createDirectories(paths.cacheDir());
        Files.writeString(paths.configFile(),"{\"server_url\":\"https://public\",\"server_id\":\"https://public\",\"upload_key\":\"fixture\"}");
        Files.write(paths.collectorExe(),new byte[0]); return paths;
    }
    private CollectorStatusReader.Status read(RuntimePaths paths) {
        return new CollectorStatusReader().read(paths,connection,session,clock,paths.root().resolve("test-hooks.json"));
    }
    @Test public void queueCountsOnlyCurrentStudentAndInstance() throws Exception {
        RuntimePaths paths=setup(); Files.writeString(paths.cacheDir().resolve("pending.json"),"""
        [{"server_id":"https://public","record":{"student_id":7}},
         {"server_id":"https://other","record":{"student_id":7}},
         {"server_id":"https://public","record":{"student_id":8}}]
        """);
        assertEquals("待上传 1 条",read(paths).label());
    }
    @Test public void sharedUploadCannotBecomePersonalSyncOrHealthyStatus() throws Exception {
        RuntimePaths paths=setup(); Files.writeString(paths.cacheDir().resolve("collector-debug.jsonl"),"""
        {"timestamp":"2026-10-21T08:20:00Z","event":"upload_succeeded"}
        """);
        var status=read(paths); assertEquals("共享采集器有上传确认",status.label());
        assertTrue(status.detail().contains("最近同步：未知")); assertFalse(status.label().contains("采集正常"));
        assertEquals("2026-10-21T08:20:00Z",status.sharedUploadAt());
    }
    @Test public void oldOrBrokenLogLinesNeverBecomeSuccess() throws Exception {
        RuntimePaths paths=setup(); Files.writeString(paths.cacheDir().resolve("collector-debug.jsonl"),"""
        not-json
        {"timestamp":"2026-10-20T08:20:00Z","event":"upload_succeeded"}
        {"timestamp":"2026-10-21T08:20:00Z","event":"hook_received"}
        """);
        assertEquals("共享采集器收到事件",read(paths).label()); assertEquals("",read(paths).sharedUploadAt());
    }
    @Test public void malformedQueueIsUnknownNotZeroAndErrorsNeverExposeRawLog() throws Exception {
        RuntimePaths paths=setup(); Files.writeString(paths.cacheDir().resolve("pending.json"),"[{\"record\":{}}]");
        assertTrue(read(paths).label().contains("未知"));
        Files.delete(paths.cacheDir().resolve("pending.json"));
        Files.writeString(paths.cacheDir().resolve("collector-debug.jsonl"),"""
        {"timestamp":"2026-10-21T08:20:00Z","event":"upload_failed","error":"password=secret token=secret"}
        """);
        var state=read(paths); assertTrue(state.label().contains("异常")); assertFalse(state.detail().contains("secret"));
    }
    @Test public void missingCredentialDoesNotClaimCollectionIsNormal() throws Exception {
        RuntimePaths paths=setup(); Files.writeString(paths.configFile(),"{\"server_url\":\"https://public\"}");
        assertEquals("缺少上传凭据",read(paths).label());
    }
    @Test public void pauseOnOldClientIsNeverReportedAsEffective() throws Exception {
        RuntimePaths paths=setup();
        new SharedConfigStore(paths).setCollectionEnabled(false);
        assertEquals("共享暂停待生效",read(paths).label());
        Files.writeString(paths.versionFile(),"{\"version\":\"0.2.11\"}");
        assertEquals("共享采集已暂停",read(paths).label());
    }

}
