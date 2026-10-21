package top.chende.coursemind.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import top.chende.coursemind.ui.CourseMindUi;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.api.ApiClient;
import top.chende.coursemind.auth.AuthService;
import top.chende.coursemind.config.CourseMindSettings;
import top.chende.coursemind.config.ServerUrl;
import top.chende.coursemind.config.ConnectionProfile;
import com.intellij.openapi.progress.ProgressManager;
import javax.swing.JRadioButton;
import javax.swing.ButtonGroup;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import com.intellij.openapi.application.ModalityState;
import java.util.Optional;

/**
 * CourseMind 设置页：服务器地址、连接测试、采集开关、配置来源与作用域说明。
 *
 * <p>R1 按原型分区，复用已有身份、部署和 Hook 服务，不保存任何 Token。
 */
public final class CourseMindConfigurable implements Configurable {

    private final JBTextField serverField = new JBTextField();
    private final JBTextField campusField = new JBTextField();
    private final JRadioButton publicNetwork = new JRadioButton("公网", true);
    private final JRadioButton campusNetwork = new JRadioButton("校园内网");
    private final JTextArea migrationNotice = CourseMindUi.note("");
    private final JBCheckBox collectEnabledCheckBox = new JBCheckBox("启用 AI 对话采集（当前仅保存本插件设置）");
    private final JTextArea connectionStatus = CourseMindUi.message();
    private final JTextArea scopeLabel = CourseMindUi.note(" ");
    private final JTextArea effectiveServer = CourseMindUi.note(" ");
    private final JTextArea credentialStatus = CourseMindUi.note(" ");
    private final JButton testButton = new JButton("测试连接");

    private JComponent panel;
    private CollectorHookPanel collectorHookPanel;
    private int uiGeneration;

    public CourseMindConfigurable() {
        testButton.addActionListener(event -> testConnection());
        ButtonGroup group = new ButtonGroup(); group.add(publicNetwork); group.add(campusNetwork);
        publicNetwork.addActionListener(event -> updatePreview());
        campusNetwork.addActionListener(event -> updatePreview());
        DocumentListener listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updatePreview(); }
            public void removeUpdate(DocumentEvent e) { updatePreview(); }
            public void changedUpdate(DocumentEvent e) { updatePreview(); }
        };
        serverField.getDocument().addDocumentListener(listener);
        campusField.getDocument().addDocumentListener(listener);
        serverField.setText(ConnectionProfile.DEFAULT_PUBLIC);
        campusField.setText(ConnectionProfile.DEFAULT_CAMPUS);
    }

    @Override
    public @Nls String getDisplayName() {
        return "CourseMind";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (panel == null) {
            uiGeneration++;
            testButton.setEnabled(true);
            serverField.setColumns(30);
            collectorHookPanel = new CollectorHookPanel();

            panel = buildContent(collectorHookPanel);
            reset();
            collectorHookPanel.refreshAsync();

        }
        return panel;
    }

    JComponent buildContent(CollectorHookPanel collectorHookPanel) {
        JPanel networkChoice = new JPanel(new java.awt.GridLayout(1, 2, 12, 0));
        networkChoice.add(publicNetwork); networkChoice.add(campusNetwork);
        JPanel connection = CourseMindUi.stack(12,
                networkChoice,
                CourseMindUi.field("公网服务器地址", serverField),
                CourseMindUi.field("校园内网服务器地址", campusField),
                CourseMindUi.note("已内置两套默认地址，均可修改保存。两套入口对应同一服务实例；保存会同步共享采集入口，影响其他 IDE。"),
                migrationNotice,
                effectiveServer, credentialStatus,
                testButton, connectionStatus);
        scopeLabel.setVisible(false);

        JButton details = new JButton("显示配置详情与共享选项");
        details.addActionListener(event -> {
            boolean visible = !scopeLabel.isVisible();
            scopeLabel.setVisible(visible);

            details.setText(visible ? "收起配置详情与共享选项" : "显示配置详情与共享选项");
            scopeLabel.getParent().revalidate();
            scopeLabel.getParent().repaint();
        });
        JPanel body = CourseMindUi.stack(16,
                CourseMindUi.section("客户端配置", connection),
                CourseMindUi.section("AI 编程工具", collectorHookPanel.hookComponent()),
                CourseMindUi.section("采集客户端", CourseMindUi.stack(12,
                        collectEnabledCheckBox,
                        CourseMindUi.note("此开关尚未控制跨 IDE 共享采集，实际 Hook 与 collector 行为保持不变。"),
                        collectorHookPanel.collectorComponent())),
                CourseMindUi.stack(12, details, scopeLabel));
        body.setBorder(JBUI.Borders.empty(16));
        return CourseMindUi.scroll(body);
    }

    private ConnectionProfile draft() {
        return new ConnectionProfile(serverField.getText(), campusField.getText(), campusNetwork.isSelected() ? "campus" : "public");
    }
    private void updatePreview() {
        ConnectionProfile p = draft();
        effectiveServer.setText("当前选择：" + p.networkLabel() + "（应用后生效）\n" + p.effectiveUrl()
                + (ServerUrl.isHttps(p.effectiveUrl()) ? "" : "\nHTTP 连接，未加密"));
        connectionStatus.setText("");
    }
    @Override public boolean isModified() {
        return !draft().equals(CourseMindSettings.getInstance().connection())
                || collectEnabledCheckBox.isSelected() != CourseMindSettings.getInstance().isCollectEnabled();
    }
    @Override public void apply() throws ConfigurationException {
        ConnectionProfile next = draft();
        try { next.validate(); } catch (CourseMindException e) { throw new ConfigurationException(e.getMessage()); }
        CourseMindSettings settings = CourseMindSettings.getInstance();
        AuthService auth = AuthService.getInstance();
        boolean sharedInstanceDiffers;
        try { sharedInstanceDiffers = auth.sharedServerId().filter(id -> !id.equals(next.serverId())).isPresent(); }
        catch (CourseMindException e) { throw new ConfigurationException(e.getMessage()); }
        if (!next.serverId().equals(settings.connection().serverId()) || sharedInstanceDiffers) {
            int answer = Messages.showYesNoDialog("服务实例标识（公网地址）已变化，保存将清除共享登录与上传凭据。旧队列保留原服务器归属，其他 IDE 也需重新登录。是否继续？",
                    "CourseMind 服务实例变更", Messages.getWarningIcon());
            if (answer != Messages.YES) throw new ConfigurationException("未保存，原连接配置保留");
        } else if (auth.sharedServerUrl().filter(url -> !url.equals(settings.getServerUrl()) && !url.equals(next.effectiveUrl())).isPresent()) {
            int answer = Messages.showYesNoDialog("其他 IDE 的共享采集入口与本插件不同。保存将按当前选择同步共享入口，是否继续？",
                    "CourseMind 共享配置冲突", Messages.getWarningIcon());
            if (answer != Messages.YES) throw new ConfigurationException("未覆盖其他 IDE 的共享配置");
        }
        boolean collectEnabled = collectEnabledCheckBox.isSelected();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        boolean completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            try { AuthService.getInstance().applyConnection(next, collectEnabled); }
            catch (RuntimeException e) { failure.set(e); }
        }, "校验并保存 CourseMind 连接", false, null);
        if (!completed || failure.get() != null) throw new ConfigurationException(failure.get() == null ? "保存已取消" : failure.get().getMessage());
        reset();
    }
    @Override public void reset() {
        CourseMindSettings settings = CourseMindSettings.getInstance();
        try { AuthService.getInstance().initializeConnection(); }
        catch (CourseMindException e) { migrationNotice.setText(e.getMessage()); }
        ConnectionProfile p = settings.connection();
        serverField.setText(p.publicUrl()); campusField.setText(p.campusUrl());
        publicNetwork.setSelected(!"campus".equals(p.network())); campusNetwork.setSelected("campus".equals(p.network()));
        collectEnabledCheckBox.setSelected(settings.isCollectEnabled());
        migrationNotice.setText(settings.migrationNotice()); migrationNotice.setVisible(!settings.migrationNotice().isBlank());
        connectionStatus.setText("");
        refreshScopeLabel();
    }
    private void refreshScopeLabel() {
        AuthService auth = AuthService.getInstance();
        String configPath = auth.runtimePaths().configFile().toString();
        Optional<String> shared = auth.sharedServerUrl();
        String own = CourseMindSettings.getInstance().getServerUrl();
        effectiveServer.setText("当前生效：" + CourseMindSettings.getInstance().connection().networkLabel()
                + "\n" + (own.isEmpty() ? "未配置" : own) + (ServerUrl.isHttps(own) ? "" : "\nHTTP 连接，未加密"));
        credentialStatus.setText("上传凭证：" + (auth.sharedConfigHasUploadKey() ? "已存在（有效性由服务端验证）" : "待登录"));
        StringBuilder text = new StringBuilder();
        text.append("本插件配置：").append(own.isEmpty() ? "未配置" : own).append("\n");
        text.append("共享配置：").append(shared.orElse("未读取到 server_url")).append("\n");
        text.append("上传凭据：").append(auth.sharedConfigHasUploadKey() ? "已存在（登录后写入）" : "不存在").append("\n");
        text.append("配置文件：").append(configPath).append("\n");
        text.append("作用域：共享 config.json 同时影响 CodeBuddy IDE、Trae Code 等入口");
        if (shared.isPresent() && !own.isEmpty()
                && !ServerUrl.serverIdOf(shared.get()).equals(ServerUrl.serverIdOf(own))) {
            text.append("\n注意：两处服务器地址不一致，切换前请确认采集归属。");
        }

        scopeLabel.setText(text.toString());
    }

    private void testConnection() {
        Optional<String> error = ServerUrl.validate(draft().effectiveUrl());
        if (error.isPresent()) {
            connectionStatus.setText(error.get());
            return;
        }
        String url = draft().effectiveUrl();
        int generation = ++uiGeneration;
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
            ApplicationManager.getApplication().invokeLater(() -> {
                if (panel == null || generation != uiGeneration) return;
                if (!url.equals(draft().effectiveUrl())) { testButton.setEnabled(true); return; }
                connectionStatus.setText(finalMessage);
                testButton.setEnabled(true);
            }, ModalityState.any());
        });
    }

    @Override
    public void disposeUIResources() {
        uiGeneration++;
        panel = null;
        collectorHookPanel = null;
    }
}
