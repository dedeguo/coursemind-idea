package top.chende.coursemind.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 插件自己的非敏感配置（IDEA 用户配置目录下的 coursemind.xml）。
 *
 * <p>凭据一律不放这里：Token 在 auth.dat（DPAPI），upload_key 在共享 config.json。
 * 服务器地址与共享 config.json 的 server_url 可能同时存在，冲突时由设置页展示并可选择以哪套为准。
 */
@State(name = "CourseMindSettings", storages = @Storage("coursemind.xml"))
public final class CourseMindSettings implements PersistentStateComponent<CourseMindSettings.State> {

    public static final int CONFIG_VERSION = 1;

    private State state = new State();

    public static CourseMindSettings getInstance() {
        return ApplicationManager.getApplication().getService(CourseMindSettings.class);
    }

    public static final class State {
        /** 用户在本插件中选择的服务器地址（已规范化）。 */
        public String serverUrl = "";
        /** 采集开关（M1 仅本插件可见，跨 IDE 共享在 M3 核对）。 */
        public boolean collectEnabled = true;
        /** 配置版本，用于后续迁移。 */
        public int configVersion = CONFIG_VERSION;
        /** 上次登录的学号，仅用于输入框预填，不属于凭据。 */
        public String lastStudentNo = "";
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
        migrate();
    }

    private void migrate() {
        if (state.serverUrl == null) {
            state.serverUrl = "";
        }
        if (state.lastStudentNo == null) {
            state.lastStudentNo = "";
        }
        if (state.configVersion < CONFIG_VERSION) {
            state.serverUrl = ServerUrl.normalize(state.serverUrl);
            state.configVersion = CONFIG_VERSION;
        }
    }

    public @NotNull String getServerUrl() {
        return state.serverUrl == null ? "" : state.serverUrl;
    }

    public void setServerUrl(@Nullable String serverUrl) {
        state.serverUrl = ServerUrl.normalize(serverUrl);
    }

    public boolean isCollectEnabled() {
        return state.collectEnabled;
    }

    public void setCollectEnabled(boolean enabled) {
        state.collectEnabled = enabled;
    }

    public @NotNull String getLastStudentNo() {
        return state.lastStudentNo == null ? "" : state.lastStudentNo;
    }

    public void setLastStudentNo(@Nullable String studentNo) {
        state.lastStudentNo = studentNo == null ? "" : studentNo.trim();
    }

    public boolean isConfigured() {
        return !ServerUrl.normalize(getServerUrl()).isEmpty();
    }
}
