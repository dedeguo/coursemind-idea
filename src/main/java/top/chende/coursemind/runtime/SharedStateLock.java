package top.chende.coursemind.runtime;

import top.chende.coursemind.CourseMindException;
import java.nio.file.*;
import java.io.IOException;
import java.util.function.Supplier;

/** Directory lease protocol shared with the VS Code extension. Never steals a live/stale lease. */
public final class SharedStateLock implements AutoCloseable {
    private final Path directory;
    private SharedStateLock(Path directory) { this.directory = directory; }
    public static SharedStateLock acquire(RuntimePaths paths) {
        return acquire(paths, 15000);
    }
    public static SharedStateLock acquire(RuntimePaths paths, long timeoutMillis) {
        Path directory = paths.root().resolve("state-write.lock");
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        try {
            Files.createDirectories(paths.root());
            while (true) {
                try { Files.createDirectory(directory); return new SharedStateLock(directory); }
                catch (FileAlreadyExistsException e) {
                    if (System.nanoTime() >= deadline) throw new CourseMindException("共享状态正被其他 IDE 更新；若所有 IDE 均已关闭，可检查 state-write.lock 后重试");
                    Thread.sleep(40);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CourseMindException("共享状态操作已取消", e);
        } catch (IOException e) { throw new CourseMindException("无法获取共享状态写入锁", e); }
    }
    public static <T> T run(RuntimePaths paths, Supplier<T> operation) {
        try (SharedStateLock ignored = acquire(paths)) { return operation.get(); }
    }
    @Override public void close() {
        try { Files.delete(directory); }
        catch (IOException e) { throw new CourseMindException("无法释放共享状态写入锁，请检查目录权限", e); }
    }
}
