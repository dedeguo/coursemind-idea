package top.chende.coursemind.education;

import top.chende.coursemind.api.ApiException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.*;

/** One gate per project view. Scope changes cancel all delayed results and writes. */
public final class TeachingController implements AutoCloseable {
    public record Scope(String sessionId, String serverId, String entry, String environmentId, int accountId, int studentId) { }
    private final Executor worker, delivery;
    private final Consumer<Boolean> loading;
    private Scope scope;
    private long generation;
    private boolean busy, closed;
    public TeachingController(Executor worker, Executor delivery, Consumer<Boolean> loading) {
        this.worker = worker; this.delivery = delivery; this.loading = loading;
    }
    public synchronized void bind(Scope next) {
        if (closed || Objects.equals(scope, next)) return;
        scope = next; generation++; busy = false; loading.accept(false);
    }
    public synchronized <T> void execute(Supplier<T> fetch, BooleanSupplier current, Consumer<T> success,
                                         Consumer<String> failure, Runnable invalid, Runnable expire) {
        if (closed || busy || scope == null) return;
        busy = true; long ticket = generation; loading.accept(true);
        worker.execute(() -> {
            T data = null; RuntimeException error = null; boolean valid = false;
            try {
                if (active(ticket) && current.getAsBoolean()) { data = fetch.get(); valid = current.getAsBoolean(); }
            } catch (RuntimeException e) { error = e; try { valid = current.getAsBoolean(); } catch (RuntimeException ignored) { } }
            T result = data; RuntimeException problem = error; boolean accepted = valid;
            delivery.execute(() -> {
                synchronized (this) {
                    if (!active(ticket)) return;
                    busy = false; loading.accept(false);
                    if (!accepted) { invalid.run(); return; }
                    if (problem == null) success.accept(result);
                    else {
                        failure.accept(problem.getMessage() == null ? "请求失败，请刷新后重试" : problem.getMessage());
                        if (problem instanceof ApiException api && api.status() == 401) expire.run();
                    }
                }
            });
        });
    }
    private synchronized boolean active(long ticket) { return !closed && scope != null && ticket == generation; }
    @Override public synchronized void close() { closed = true; scope = null; generation++; busy = false; }
}
