package top.chende.coursemind.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import top.chende.coursemind.CourseMindException;

/** Non-sensitive preferences only. Shared auth remains authoritative. */
@State(name = "CourseMindSettings", storages = @Storage("coursemind.xml"))
public final class CourseMindSettings implements PersistentStateComponent<CourseMindSettings.State> {
    public static final int CONFIG_VERSION = 2;
    private State state = new State();
    public CourseMindSettings() { migrate(); }
    public static CourseMindSettings getInstance() {
        return ApplicationManager.getApplication().getService(CourseMindSettings.class);
    }
    public static final class State {
        public String serverUrl = ""; // legacy single address, retained for migration
        public String publicUrl = "";
        public String campusUrl = "";
        public String network = "public";
        public boolean connectionSaved;
        public boolean collectEnabled = true;
        public int configVersion;
        public String lastStudentNo = "";
        public String migrationNotice = "";
    }
    @Override public synchronized @NotNull State getState() { return state; }
    @Override public synchronized void loadState(@NotNull State loaded) { state = loaded; migrate(); }
    private void migrate() {
        if (state.lastStudentNo == null) state.lastStudentNo = "";
        if (state.migrationNotice == null) state.migrationNotice = "";
        if (state.configVersion < CONFIG_VERSION) {
            if (state.serverUrl != null && !state.serverUrl.isBlank() && (state.publicUrl == null || state.publicUrl.isBlank())) {
                state.publicUrl = ServerUrl.normalize(state.serverUrl);
                state.network = "public";
                state.connectionSaved = true;
                state.migrationNotice = "已保留旧服务器地址到公网字段；原配置未标明网络用途，请确认两套入口属于同一服务实例。";
            }
            if (state.publicUrl == null || state.publicUrl.isBlank()) state.publicUrl = ConnectionProfile.DEFAULT_PUBLIC;
            if (state.campusUrl == null || state.campusUrl.isBlank()) state.campusUrl = ConnectionProfile.DEFAULT_CAMPUS;
            state.configVersion = CONFIG_VERSION;
        }
        state.serverUrl = "";
    }
    public synchronized ConnectionProfile connection() { return new ConnectionProfile(state.publicUrl, state.campusUrl, state.network); }
    public synchronized void setConnection(ConnectionProfile profile) {
        profile.validate();
        state.publicUrl = profile.publicUrl(); state.campusUrl = profile.campusUrl(); state.network = profile.network();
        state.connectionSaved = true; state.migrationNotice = "";
    }
    /** First use adopts valid machine/shared config, never overwrites explicitly saved preferences. */
    public synchronized void adoptShared(String effective, String serverId) {
        if (state.connectionSaved || effective == null || effective.isBlank()) return;
        String id = serverId == null || serverId.isBlank() ? effective : serverId;
        ConnectionProfile profile = new ConnectionProfile(id,
                ServerUrl.normalize(effective).equals(ServerUrl.normalize(id)) ? state.campusUrl : effective,
                ServerUrl.normalize(effective).equals(ServerUrl.normalize(id)) ? "public" : "campus");
        try { profile.validate(); } catch (CourseMindException e) {
            throw new CourseMindException("共享服务器配置无效，未以默认值覆盖：" + e.getMessage());
        }
        setConnection(profile);
        state.migrationNotice = "已采用机房/其他 IDE 的共享服务器配置；请确认两套入口。";
    }
    public synchronized String migrationNotice() { return state.migrationNotice; }
    public String getServerUrl() { return connection().effectiveUrl(); }
    /** Compatibility for callers editing the active entry point. */
    public void setServerUrl(String url) {
        ConnectionProfile p = connection();
        synchronized (this) {
            state.publicUrl = "campus".equals(p.network()) ? p.publicUrl() : ServerUrl.normalize(url);
            state.campusUrl = "campus".equals(p.network()) ? ServerUrl.normalize(url) : p.campusUrl();
            state.connectionSaved = true;
        }
    }
    public synchronized boolean isCollectEnabled() { return state.collectEnabled; }
    public synchronized void setCollectEnabled(boolean value) { state.collectEnabled = value; }
    public synchronized String getLastStudentNo() { return state.lastStudentNo; }
    public synchronized void setLastStudentNo(String value) { state.lastStudentNo = value == null ? "" : value.trim(); }
    public boolean isConfigured() { return ServerUrl.validate(getServerUrl()).isEmpty(); }
}
