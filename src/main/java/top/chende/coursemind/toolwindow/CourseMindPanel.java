package top.chende.coursemind.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.api.ApiClient;
import top.chende.coursemind.auth.AuthService;
import top.chende.coursemind.auth.AuthSession;
import top.chende.coursemind.config.CourseMindSettings;
import top.chende.coursemind.settings.CourseMindConfigurable;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * CourseMind 工具窗口内容：未配置 / 未登录 / 已登录 / 过期 / 读取失败五种状态。
 *
 * <p>M1 只负责身份与服务器状态；collector、Hook 与采集状态在 M2/M3 接入。
 * 所有会触发进程调用或网络请求的操作都在后台线程执行，UI 只在 EDT 更新。
 */
final class CourseMindPanel {

    private static final String CARD_LOADING = "loading";
    private static final String CARD_SETUP = "setup";
    private static final String CARD_LOGIN = "login";
    private static final String CARD_IDENTITY = "identity";
    private static final String CARD_ERROR = "error";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout(0, 8));
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    private final JBLabel statusLabel = new JBLabel("正在读取登录状态…");

    private final JBLabel loginServerLabel = new JBLabel(" ");
    private final JBTextField studentNoField = new JBTextField();
    private final JPasswordField passwordField = new JBPasswordField();
    private final JBLabel loginMessage = new JBLabel(" ");
    private final JButton loginButton = new JButton("登录");

    private final JBLabel identityName = new JBLabel(" ");
    private final JBLabel identityStudentNo = new JBLabel(" ");
    private final JBLabel identityClass = new JBLabel(" ");
    private final JBLabel identityServer = new JBLabel(" ");
    private final JBLabel identityExpiry = new JBLabel(" ");
    private final JBLabel identityMessage = new JBLabel(" ");

    private final JBLabel errorLabel = new JBLabel(" ");

    CourseMindPanel(@NotNull Project project) {
        this.project = project;
        buildUi();
        refreshAsync();
    }

    @NotNull JComponent component() {
        return root;
    }

    private void buildUi() {
        root.setBorder(JBUI.Borders.empty(10));
        root.add(statusLabel, BorderLayout.NORTH);
        root.add(cardPanel, BorderLayout.CENTER);

        // 第一张卡是初始显示，避免读取期间误显示"尚未配置"
        cardPanel.add(new JBLabel("正在读取登录状态…"), CARD_LOADING);
        cardPanel.add(buildSetupCard(), CARD_SETUP);
        cardPanel.add(buildLoginCard(), CARD_LOGIN);
        cardPanel.add(buildIdentityCard(), CARD_IDENTITY);
        cardPanel.add(buildErrorCard(), CARD_ERROR);
    }

    private @NotNull JPanel buildSetupCard() {
        JPanel panel = verticalPanel();
        panel.add(new JBLabel("尚未配置 CourseMind 服务器。"));
        JButton openSettings = new JButton("打开设置");
        openSettings.addActionListener(event -> openSettings());
        panel.add(buttonRow(openSettings));
        return panel;
    }

    private @NotNull JPanel buildLoginCard() {
        studentNoField.setColumns(18);
        passwordField.setColumns(18);
        loginButton.addActionListener(event -> login());

        // 用 GridBag（FormBuilder）而不是 BoxLayout：JTextField 在 BoxLayout 中会被拉伸到整窗高度
        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(loginServerLabel)
                .addLabeledComponent(new JBLabel("学号："), studentNoField)
                .addLabeledComponent(new JBLabel("密码："), passwordField)
                .addComponent(buttonRow(loginButton, settingsButton()))
                .addComponent(loginMessage)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    private @NotNull JPanel buildIdentityCard() {
        JButton test = new JButton("测试连接");
        test.addActionListener(event -> testConnection());
        JButton logout = new JButton("退出登录");
        logout.addActionListener(event -> logout());

        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(identityName)
                .addComponent(identityStudentNo)
                .addComponent(identityClass)
                .addComponent(identityServer)
                .addComponent(identityExpiry)
                .addComponent(buttonRow(test, logout, settingsButton()))
                .addComponent(identityMessage)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    private @NotNull JPanel buildErrorCard() {
        JPanel panel = verticalPanel();
        panel.add(errorLabel);
        JButton retry = new JButton("重新读取");
        retry.addActionListener(event -> refreshAsync());
        panel.add(buttonRow(retry, settingsButton()));
        return panel;
    }

    private @NotNull JButton settingsButton() {
        JButton button = new JButton("设置");
        button.addActionListener(event -> openSettings());
        return button;
    }

    private @NotNull JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private @NotNull JPanel buttonRow(@NotNull JButton... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        for (JButton button : buttons) {
            row.add(button);
        }
        return row;
    }

    private void openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, CourseMindConfigurable.class);
        refreshAsync();
    }

    private void refreshAsync() {
        statusLabel.setText("正在读取登录状态…");
        cards.show(cardPanel, CARD_LOADING);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            AuthService.Snapshot snapshot;
            try {
                snapshot = AuthService.getInstance().refresh();
            } catch (RuntimeException e) {
                snapshot = new AuthService.Snapshot(AuthService.State.ERROR, null, e.getMessage());
            }
            AuthService.Snapshot result = snapshot;
            ApplicationManager.getApplication().invokeLater(() -> applySnapshot(result));
        });
    }

    private void applySnapshot(@NotNull AuthService.Snapshot snapshot) {
        switch (snapshot.state()) {
            case NOT_CONFIGURED -> {
                statusLabel.setText("未配置");
                cards.show(cardPanel, CARD_SETUP);
            }
            case LOGGED_OUT -> {
                statusLabel.setText("未登录");
                loginServerLabel.setText("服务器：" + displayServer());
                studentNoField.setText(CourseMindSettings.getInstance().getLastStudentNo());
                passwordField.setText("");
                loginMessage.setText(snapshot.message() == null ? " " : snapshot.message());
                cards.show(cardPanel, CARD_LOGIN);
            }
            case LOGGED_IN -> {
                statusLabel.setText("已登录");
                renderSession(snapshot.session());
                identityMessage.setText(" ");
                cards.show(cardPanel, CARD_IDENTITY);
            }
            case EXPIRED -> {
                statusLabel.setText("登录已过期");
                loginServerLabel.setText("服务器：" + displayServer());
                if (snapshot.session() != null) {
                    studentNoField.setText(snapshot.session().profile().studentNo());
                }
                passwordField.setText("");
                loginMessage.setText(snapshot.message() == null ? "请重新登录" : snapshot.message());
                cards.show(cardPanel, CARD_LOGIN);
            }
            case ERROR -> {
                statusLabel.setText("读取失败");
                errorLabel.setText(snapshot.message() == null ? "共享登录状态不可用" : snapshot.message());
                cards.show(cardPanel, CARD_ERROR);
            }
        }
    }

    private void renderSession(AuthSession session) {
        if (session == null) {
            return;
        }
        AuthSession.Profile profile = session.profile();
        identityName.setText("姓名：" + profile.name()
                + (profile.gender() == null ? "" : "   性别：" + profile.gender()));
        identityStudentNo.setText("学号：" + profile.studentNo());
        identityClass.setText("班级：" + profile.className()
                + (profile.classroom().isEmpty() ? "" : "   教室：" + profile.classroom()));
        identityServer.setText("服务器：" + displayServer());
        identityExpiry.setText(session.expiresAt()
                .map(exp -> "有效期至：" + TIME_FORMAT.format(exp))
                .orElse("有效期：服务端未返回"));
    }

    private @NotNull String displayServer() {
        String server = CourseMindSettings.getInstance().getServerUrl();
        return server.isEmpty() ? "未配置" : server;
    }

    private void login() {
        String studentNo = studentNoField.getText().trim();
        String password = new String(passwordField.getPassword());
        if (studentNo.isEmpty() || password.isEmpty()) {
            loginMessage.setText("请填写学号和密码");
            return;
        }
        String server = CourseMindSettings.getInstance().getServerUrl();
        if (server.isEmpty()) {
            loginMessage.setText("请先在设置中填写服务器地址");
            return;
        }
        loginButton.setEnabled(false);
        loginMessage.setText("正在登录…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String error = null;
            try {
                AuthService.getInstance().login(server, studentNo, password);
            } catch (CourseMindException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                error = "登录失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            String message = error;
            ApplicationManager.getApplication().invokeLater(() -> {
                loginButton.setEnabled(true);
                if (message == null) {
                    passwordField.setText("");
                    refreshAsync();
                } else {
                    loginMessage.setText(message);
                }
            });
        });
    }

    private void logout() {
        int answer = Messages.showYesNoDialog(project,
                "退出后将清除共享登录状态与上传凭据，另一 IDE 的采集也会停止上传。是否继续？",
                "CourseMind 退出登录", Messages.getWarningIcon());
        if (answer != Messages.YES) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String error = null;
            try {
                AuthService.getInstance().logout();
            } catch (CourseMindException e) {
                error = e.getMessage();
            }
            String message = error;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (message != null) {
                    identityMessage.setText("退出失败：" + message);
                } else {
                    refreshAsync();
                }
            });
        });
    }

    private void testConnection() {
        String server = CourseMindSettings.getInstance().getServerUrl();
        identityMessage.setText("正在连接…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String message;
            try {
                ApiClient.HealthResult health = AuthService.getInstance().testConnection(server);
                message = health.healthy()
                        ? "连接正常" + (health.version() == null ? "" : "（服务端 " + health.version() + "）")
                        : "服务可达，但未返回健康状态";
            } catch (CourseMindException e) {
                message = e.getMessage();
            }
            String result = message;
            ApplicationManager.getApplication().invokeLater(() -> identityMessage.setText(result));
        });
    }
}
