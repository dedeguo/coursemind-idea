package top.chende.coursemind.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.api.ApiClient;
import top.chende.coursemind.auth.AuthService;
import top.chende.coursemind.config.CourseMindSettings;
import top.chende.coursemind.config.ServerUrl;
import top.chende.coursemind.config.SharedConfigStore;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.util.Optional;

/**
 * CourseMind 设置页：服务器地址、连接测试、采集开关、配置来源与作用域说明。
 *
 * <p>不修改 Hook 与 collector（M2 负责），也不保存任何 Token。
 */
public final class CourseMindConfigurable implements Configurable {

    private final JBTextField serverField = new JBTextField();
    private final JBCheckBox syncSharedCheckBox = new JBCheckBox("保存时同步更新共享 config.json（影响 CodeBuddy/Trae 采集）");
    private final JBCheckBox collectEnabledCheckBox = new JBCheckBox("启用采集（仅本插件，跨 IDE 共享开关在 M3 核对）");
    private final JBLabel connectionStatus = new JBLabel(" ");
    private final JBLabel scopeLabel = new JBLabel(" ");
    private final JButton testButton = new JButton("测试连接");

    private JPanel panel;

    @Override
    public @Nls String getDisplayName() {
        return "CourseMind";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (panel == null) {
            testButton.addActionListener(event -> testConnection());
            serverField.setColumns(30);

            JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            statusRow.add(testButton);
            statusRow.add(connectionStatus);

            panel = FormBuilder.createFormBuilder()
                    .addComponent(new JBLabel("<html><b>服务器</b></html>"))
                    .addLabeledComponent(new JBLabel("地址："), serverField)
                    .addComponent(statusRow)
                    .addComponent(syncSharedCheckBox)
                    .addSeparator()
                    .addComponent(new JBLabel("<html><b>采集</b></html>"))
                    .addComponent(collectEnabledCheckBox)
                    .addSeparator()
                    .addComponent(new JBLabel("<html><b>配置来源与作用域</b></html>"))
                    .addComponent(scopeLabel)
                    .addComponentFillVertically(new JPanel(), 0)
                    .getPanel();
            panel.setBorder(JBUI.Borders.empty(8));
            reset();
        }
        return panel;
    }

    @Override
    public boolean isModified() {
        CourseMindSettings settings = CourseMindSettings.getInstance();
        return !ServerUrl.normalize(serverField.getText()).equals(settings.getServerUrl())
                || collectEnabledCheckBox.isSelected() != settings.isCollectEnabled();
    }

    @Override
    public void apply() throws ConfigurationException {
        String input = serverField.getText();
        Optional<String> error = ServerUrl.validate(input);
        if (error.isPresent()) {
            throw new ConfigurationException(error.get());
        }
        String normalized = ServerUrl.normalize(input);
        CourseMindSettings settings = CourseMindSettings.getInstance();
        String previous = settings.getServerUrl();
        boolean serverChanged = !normalized.equals(previous);

        if (serverChanged) {
            boolean loggedIn = Files.isRegularFile(AuthService.getInstance().runtimePaths().authFile());
            if (loggedIn) {
                int answer = Messages.showYesNoDialog(
                        "服务器地址已变更。共享登录状态属于原服务器，需要重新登录。\n是否退出当前登录状态？",
                        "CourseMind 服务器变更",
                        Messages.getWarningIcon());
                if (answer != Messages.YES) {
                    throw new ConfigurationException("请先退出登录或恢复原服务器地址");
                }
                try {
                    AuthService.getInstance().logout();
                } catch (CourseMindException e) {
                    throw new ConfigurationException("退出登录失败：" + e.getMessage());
                }
            }
        }

        settings.setServerUrl(normalized);
        settings.setCollectEnabled(collectEnabledCheckBox.isSelected());

        if (syncSharedCheckBox.isSelected() && serverChanged) {
            try {
                new SharedConfigStore(AuthService.getInstance().runtimePaths()).syncServer(normalized);
                connectionStatus.setText("已同步共享 config.json 的服务器字段");
            } catch (CourseMindException e) {
                throw new ConfigurationException("保存设置成功，但同步共享配置失败：" + e.getMessage());
            }
        }
        reset();
    }

    @Override
    public void reset() {
        CourseMindSettings settings = CourseMindSettings.getInstance();
        serverField.setText(settings.getServerUrl());
        collectEnabledCheckBox.setSelected(settings.isCollectEnabled());
        syncSharedCheckBox.setSelected(false);
        connectionStatus.setText(" ");
        refreshScopeLabel();
    }

    private void refreshScopeLabel() {
        AuthService auth = AuthService.getInstance();
        String configPath = auth.runtimePaths().configFile().toString();
        Optional<String> shared = auth.sharedServerUrl();
        String own = CourseMindSettings.getInstance().getServerUrl();
        StringBuilder text = new StringBuilder("<html>");
        text.append("本插件配置：").append(own.isEmpty() ? "未配置" : own).append("<br/>");
        text.append("共享配置：").append(shared.orElse("未读取到 server_url")).append("<br/>");
        text.append("上传凭据：").append(auth.sharedConfigHasUploadKey() ? "已存在（登录后写入）" : "不存在").append("<br/>");
        text.append("配置文件：").append(configPath).append("<br/>");
        text.append("作用域：共享 config.json 同时影响 CodeBuddy IDE、Trae Code 等入口");
        if (shared.isPresent() && !own.isEmpty()
                && !ServerUrl.serverIdOf(shared.get()).equals(ServerUrl.serverIdOf(own))) {
            text.append("<br/><b>注意：两处服务器地址不一致，切换前请确认采集归属。</b>");
        }
        text.append("</html>");
        scopeLabel.setText(text.toString());
    }

    private void testConnection() {
        Optional<String> error = ServerUrl.validate(serverField.getText());
        if (error.isPresent()) {
            connectionStatus.setText(error.get());
            return;
        }
        String url = ServerUrl.normalize(serverField.getText());
        testButton.setEnabled(false);
        connectionStatus.setText("正在连接…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String message;
            try {
                ApiClient.HealthResult health = AuthService.getInstance().testConnection(url);
                message = health.healthy()
                        ? "连接正常" + (health.version() == null ? "" : "（服务端 " + health.version() + "）")
                        : "服务可达，但服务端未返回健康状态";
            } catch (CourseMindException e) {
                message = e.getMessage();
            }
            String finalMessage = message;
            SwingUtilities.invokeLater(() -> {
                connectionStatus.setText(finalMessage);
                testButton.setEnabled(true);
            });
        });
    }

    @Override
    public void disposeUIResources() {
        panel = null;
    }
}
