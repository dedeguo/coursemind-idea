package top.chende.coursemind.runtime;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SharedStateLockTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void independentWritersSerializeAndReleaseAfterFailure() throws Exception {
        RuntimePaths paths = RuntimePaths.of(folder.newFolder().toPath());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        try {
            Callable<Void> task = () -> {
                SharedStateLock.run(paths, () -> {
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try { Thread.sleep(80); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    active.decrementAndGet(); return null;
                }); return null;
            };
            for (Future<Void> result : workers.invokeAll(java.util.List.of(task, task))) result.get();
            assertEquals(1, maximum.get());
            assertThrows(RuntimeException.class, () -> SharedStateLock.run(paths, () -> { throw new RuntimeException("fixture"); }));
            assertFalse(Files.exists(paths.root().resolve("state-write.lock")));
        } finally { workers.shutdownNow(); }
    }
    @Test public void foreignExtensionLeaseIsNotStolen() throws Exception {
        RuntimePaths paths = RuntimePaths.of(folder.newFolder().toPath());
        Path directory = paths.root().resolve("state-write.lock"); Files.createDirectory(directory);
        assertThrows(RuntimeException.class, () -> SharedStateLock.acquire(paths, 30));
        assertTrue(Files.isDirectory(directory)); Files.delete(directory);
    }
}
