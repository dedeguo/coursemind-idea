package top.chende.coursemind.stats;

import java.time.*;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.*;
import top.chende.coursemind.api.ApiException;

/** Per-view generation gate. A different identity/entry immediately invalidates all cached/in-flight data. */
public final class StatsController implements AutoCloseable {
    public record Scope(String sessionId, String serverId, String entry, int studentId) { }
    public enum Phase { SIGNED_OUT, LOADING, READY, EMPTY, ERROR, FORBIDDEN, EXPIRED }
    public record State(Phase phase, StudentStats stats, Instant fetchedAt, String message) { }
    public record Result(StudentStats stats, boolean current) { }
    private final Executor worker, delivery;
    private final Consumer<State> render;
    private final Clock clock;
    private Scope scope;
    private long generation;
    private boolean loading, closed;
    private State state = new State(Phase.SIGNED_OUT, null, null, "");
    public StatsController(Executor worker, Executor delivery, Consumer<State> render, Clock clock) {
        this.worker = worker; this.delivery = delivery; this.render = render; this.clock = clock;
    }
    public synchronized void bind(Scope next) {
        if (closed || Objects.equals(scope, next)) return;
        scope = next; generation++; loading = false;
        emit(new State(next == null ? Phase.SIGNED_OUT : Phase.LOADING, null, null, ""));
    }
    public synchronized void refresh(Supplier<Result> fetch, Runnable expire) {
        if (closed || scope == null || loading) return;
        // Never carry yesterday's values into a new server-local day.
        if (state.stats() != null && !LocalDate.now(clock.withZone(state.stats().range().zone())).equals(state.stats().range().start())) {
            state = new State(Phase.LOADING, null, null, "");
        }
        loading = true; long ticket = generation;
        StudentStats previous = state.stats(); Instant fetched = state.fetchedAt();
        emit(new State(Phase.LOADING, previous, fetched, "正在更新…"));
        worker.execute(() -> {
            Result result = null; RuntimeException failure = null;
            try { result = fetch.get(); } catch (RuntimeException error) { failure = error; }
            Result completed = result; RuntimeException error = failure;
            delivery.execute(() -> complete(ticket, completed, error, expire));
        });
    }
    private synchronized void complete(long ticket, Result result, RuntimeException error, Runnable expire) {
        if (closed || ticket != generation) return;
        loading = false;
        if (result != null && !result.current()) {
            generation++; scope = null; emit(new State(Phase.SIGNED_OUT, null, null, "共享身份已变化，请刷新")); return;
        }
        if (error == null) {
            StudentStats stats = result.stats();
            emit(new State(stats.empty() ? Phase.EMPTY : Phase.READY, stats, clock.instant(), ""));
        } else if (error instanceof ApiException api && api.status() == 401) {
            emit(new State(Phase.EXPIRED, null, null, "登录已过期，请重新登录")); expire.run();
        } else if (error instanceof ApiException api && api.status() == 403) {
            emit(new State(Phase.FORBIDDEN, null, null, "没有查看统计的权限，请联系教师或管理员"));
        } else {
            emit(new State(Phase.ERROR, state.stats(), state.fetchedAt(), error.getMessage() == null ? "统计暂不可用" : error.getMessage()));
        }
    }
    private void emit(State value) { state = value; render.accept(value); }
    @Override public synchronized void close() { closed = true; scope = null; generation++; state = new State(Phase.SIGNED_OUT, null, null, ""); }
}
