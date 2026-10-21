package top.chende.coursemind.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import top.chende.coursemind.ui.CourseMindUi;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import top.chende.coursemind.CourseMindException;

import top.chende.coursemind.auth.AuthService;
import top.chende.coursemind.auth.AuthSession;
import top.chende.coursemind.config.CourseMindSettings;
import top.chende.coursemind.settings.CourseMindConfigurable;

import javax.swing.JTextArea;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

import java.awt.BorderLayout;
import java.awt.CardLayout;

import java.awt.Font;

/**
 * CourseMind 工具窗口内容：未配置 / 未登录 / 已登录 / 过期 / 读取失败五种状态。
 *
 * <p>M1 只负责身份与服务器状态；collector、Hook 与采集状态在 M2/M3 接入。
 * 所有会触发进程调用或网络请求的操作都在后台线程执行，UI 只在 EDT 更新。
 */
final class CourseMindPanel implements com.intellij.openapi.Disposable {

    private static final String CARD_LOADING = "loading";
    private static final String CARD_LOGIN = "login";
    private static final String CARD_IDENTITY = "identity";
    private static final String CARD_ERROR = "error";

    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout(0, 8));
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    private final JTextArea statusLabel = CourseMindUi.note("正在读取登录状态…");

    private final JTextArea loginServerLabel = CourseMindUi.note(" ");
    private final JBTextField studentNoField = new JBTextField();
    private final JPasswordField passwordField = new JBPasswordField();
    private final JTextArea loginMessage = CourseMindUi.message();
    private final JButton loginButton = new JButton("登录");

    private final JBLabel identityName = new JBLabel(" ");
    private final JBLabel identityStudentNo = new JBLabel(" ");
    private final JBLabel identityClass = new JBLabel(" ");
    private final JBLabel identityAvatar = CourseMindUi.badge("C", 44, true);
    private final JTextArea identityMessage = CourseMindUi.message();

    private final PasswordChangePanel passwordChange = new PasswordChangePanel(this::changePassword);
    private boolean refreshRunning;
    private boolean refreshAgain;
    private boolean operationRunning;
    private boolean disposed;
    private String displayedKey = "";
    private String displayedSessionId;

    private final JTextArea errorLabel = CourseMindUi.note(" ");

    CourseMindPanel(@NotNull Project project) {
        this.project = project;
        buildUi();
    }

    @NotNull JComponent component() {
        return root;
    }

    private void buildUi() {
        root.setBorder(JBUI.Borders.empty(16));
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        JPanel brand = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(8), 0));
        brand.add(CourseMindUi.badge("C", 24, false));
        brand.add(CourseMindUi.title("CourseMind"));
        header.add(brand, BorderLayout.WEST);
        JButton settings = settingsButton();
        settings.getAccessibleContext().setAccessibleName("打开 CourseMind 设置");
        header.add(settings, BorderLayout.EAST);
        header.setBorder(JBUI.Borders.emptyBottom(12));
        root.add(header, BorderLayout.NORTH);
        cardPanel.add(CourseMindUi.note("正在读取登录状态…"), CARD_LOADING);
        cardPanel.add(buildLoginCard(), CARD_LOGIN);
        cardPanel.add(buildIdentityCard(), CARD_IDENTITY);
        cardPanel.add(buildErrorCard(), CARD_ERROR);
        root.add(CourseMindUi.scroll(CourseMindUi.stack(12, statusLabel, cardPanel)), BorderLayout.CENTER);
    }

    private @NotNull JPanel buildLoginCard() {
        studentNoField.setColumns(18);
        passwordField.setColumns(18);
        studentNoField.getEmptyText().setText("请输入分配的用户名");
        studentNoField.getAccessibleContext().setAccessibleName("用户名");
        passwordField.getAccessibleContext().setAccessibleName("密码");
        loginButton.addActionListener(event -> login());
        passwordField.addActionListener(event -> { if (loginButton.isEnabled()) login(); });
        studentNoField.addActionListener(event -> passwordField.requestFocusInWindow());
        JButton connection = new JButton("修改连接设置");
        connection.addActionListener(event -> openSettings());
        JPanel form = CourseMindUi.card(CourseMindUi.stack(12,
                CourseMindUi.field("用户名", studentNoField),
                CourseMindUi.field("密码", passwordField),
                loginButton, loginMessage, new javax.swing.JSeparator(),
                CourseMindUi.note("当前连接"), loginServerLabel, connection));
        return CourseMindUi.stack(16, CourseMindUi.title("欢迎使用 CourseMind"),
                CourseMindUi.note("使用教师分配的用户名和密码登录"), form,
                CourseMindUi.note("忘记密码请联系教师重置"));
    }

    private @NotNull JPanel buildIdentityCard() {
        identityName.putClientProperty("html.disable", true);
        identityName.setFont(identityName.getFont().deriveFont(Font.BOLD));
        identityName.setMinimumSize(new java.awt.Dimension(0, identityName.getPreferredSize().height));
        identityStudentNo.setMinimumSize(new java.awt.Dimension(0, identityStudentNo.getPreferredSize().height));
        identityAvatar.setHorizontalAlignment(JBLabel.CENTER);
        identityAvatar.setFont(identityAvatar.getFont().deriveFont(Font.BOLD,
                identityAvatar.getFont().getSize2D() * 1.5f));
        JPanel person = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        person.add(identityAvatar, BorderLayout.WEST);
        identityStudentNo.setForeground(JBColor.GRAY);
        person.add(CourseMindUi.stack(6, identityName, identityStudentNo), BorderLayout.CENTER);
        JButton logout = CourseMindUi.link("退出登录");
        logout.addActionListener(event -> logout());
        JButton change = CourseMindUi.link("修改密码");
        change.setHorizontalAlignment(JButton.LEFT);
        change.addActionListener(event -> passwordChange.open());
        JBLabel accountState = new JBLabel("● 已登录");
        accountState.setForeground(JBColor.namedColor("CourseMind.successForeground", new JBColor(0x267747, 0x8dce9f)));
        JPanel card = CourseMindUi.card(CourseMindUi.stack(14, person,
                new javax.swing.JSeparator(), CourseMindUi.detailRow("班级", identityClass),
                CourseMindUi.detailRow("账号状态", accountState), new javax.swing.JSeparator(),
                CourseMindUi.row(change, logout), identityMessage));
        JPanel footer = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        JBLabel footerText = new JBLabel("CourseMind · 让学习过程看得见");
        footerText.setForeground(JBColor.GRAY);
        footer.add(footerText);
        return CourseMindUi.stack(16, card, passwordChange.component(), footer);
    }

    private @NotNull JPanel buildErrorCard() {
        JButton retry = new JButton("重新读取");
        retry.addActionListener(event -> refreshAsync());
        return CourseMindUi.card(CourseMindUi.stack(12,
                CourseMindUi.title("暂时无法读取登录状态"), errorLabel, retry,
                CourseMindUi.note("可重试或通过右上角设置检查连接。")));
    }

    private @NotNull JButton settingsButton() {
        JButton button = new JButton(AllIcons.General.Settings);
        button.setToolTipText("打开 CourseMind 设置");
        button.getAccessibleContext().setAccessibleName("打开 CourseMind 设置");
        button.addActionListener(event -> openSettings());
        return button;
    }
    private void openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, CourseMindConfigurable.class);
        refreshAsync();
    }

    void refreshAsync() {
        if (disposed) return;
        if (operationRunning || refreshRunning) { refreshAgain = true; return; }
        refreshRunning = true;
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
            onEdt(() -> {
                refreshRunning = false;
                applySnapshot(result);
                if (refreshAgain) { refreshAgain = false; refreshAsync(); }
            });
        });
    }

    void applySnapshot(@NotNull AuthService.Snapshot snapshot) {
        String nextSessionId = snapshot.session() == null ? null : snapshot.session().sessionId();
        String nextKey = snapshot.state() + ":" + nextSessionId;
        boolean changed = !nextKey.equals(displayedKey);
        if (changed) {
            passwordField.setText(""); passwordChange.close(); identityMessage.setText("");
            identityName.setText(""); identityStudentNo.setText(""); identityClass.setText("");
            identityName.setToolTipText(null); identityClass.setToolTipText(null); identityAvatar.setText("C");
        }
        displayedKey = nextKey; displayedSessionId = nextSessionId;
        statusLabel.setVisible(snapshot.state() != AuthService.State.LOGGED_IN
                && snapshot.state() != AuthService.State.LOGGED_OUT);
        switch (snapshot.state()) {
            case NOT_CONFIGURED -> {
                statusLabel.setText("未配置");
                loginServerLabel.setText(displayServer());
                loginButton.setEnabled(false);
                if (changed) passwordField.setText("");
                loginMessage.setText("请通过连接设置填写服务器地址后登录");
                cards.show(cardPanel, CARD_LOGIN);
            }
            case LOGGED_OUT -> {
                statusLabel.setText("未登录");
                loginButton.setEnabled(true);
                loginServerLabel.setText(displayServer());
                if (changed) studentNoField.setText(CourseMindSettings.getInstance().getLastStudentNo());
                if (changed) passwordField.setText("");
                loginMessage.setText(snapshot.message() == null ? " " : snapshot.message());
                cards.show(cardPanel, CARD_LOGIN);
            }
            case LOGGED_IN -> {
                statusLabel.setText("已登录");
                renderSession(snapshot.session());
                cards.show(cardPanel, CARD_IDENTITY);
            }
            case EXPIRED -> {
                statusLabel.setText("登录已过期");
                loginButton.setEnabled(true);
                loginServerLabel.setText(displayServer());
                if (snapshot.session() != null) {
                    studentNoField.setText(snapshot.session().profile().studentNo());
                }
                if (changed) passwordField.setText("");
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
        identityName.setText(profile.name());
        identityName.setToolTipText(profile.name());
        identityStudentNo.setText("学号 " + profile.studentNo());
        identityClass.setText(profile.className().isBlank() ? "未知" : profile.className());
        identityClass.setToolTipText(profile.className());
        identityAvatar.setText(profile.name().isBlank() ? "C" : profile.name().substring(0, 1));
    }

    private @NotNull String displayServer() {
        String server = CourseMindSettings.getInstance().getServerUrl();
        return "当前网络：" + CourseMindSettings.getInstance().connection().networkLabel() + "\n" + (server.isEmpty() ? "未配置" : server);
    }

    private void login() {
        String studentNo = studentNoField.getText().trim();
        String password = new String(passwordField.getPassword());
        if (studentNo.isEmpty() || password.isEmpty()) {
            loginMessage.setText("请填写用户名和密码");
            return;
        }
        String server = CourseMindSettings.getInstance().getServerUrl();
        if (server.isEmpty()) {
            loginMessage.setText("请先在设置中填写服务器地址");
            return;
        }
        if (operationRunning) return;
        operationRunning = true;
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
            onEdt(() -> {
                operationRunning = false;
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
        if (operationRunning) return;
        String expectedSession = displayedSessionId;
        int answer = Messages.showYesNoDialog(project,
                "退出后将清除共享登录状态与上传凭据，另一 IDE 的采集也会停止上传。是否继续？",
                "CourseMind 退出登录", Messages.getWarningIcon());
        if (answer != Messages.YES) {
            return;
        }
        operationRunning = true;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String error = null;
            try {
                AuthService.getInstance().logout(expectedSession);
            } catch (CourseMindException e) {
                error = e.getMessage();
            }
            String message = error;
            onEdt(() -> {
                operationRunning = false;
                if (message != null) {
                    identityMessage.setText("退出失败：" + message);
                } else {
                    refreshAsync();
                }
            });
        });
    }

    private void changePassword(String oldPassword, String newPassword) {
        if (operationRunning) { passwordChange.showError("其他身份操作正在进行，请稍后重试"); return; }
        String expected = displayedSessionId;
        operationRunning = true;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String error = null;
            try { AuthService.getInstance().changePassword(expected, oldPassword, newPassword); }
            catch (RuntimeException e) { error = e.getMessage(); }
            String failure = error;
            onEdt(() -> {
                operationRunning = false;
                if (failure != null) {
                    passwordChange.showError(failure);
                    if (refreshAgain) refreshAsync();
                } else {
                    applySnapshot(AuthService.getInstance().snapshot());
                    passwordChange.close(); identityMessage.setText("密码修改成功");
                    refreshAsync();
                }
            });
        });
    }
    void start() {
        AuthService.getInstance().subscribe(() -> onEdt(this::refreshAsync), this);
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(
                com.intellij.openapi.application.ApplicationActivationListener.TOPIC,
                new com.intellij.openapi.application.ApplicationActivationListener() {
                    @Override public void applicationActivated(com.intellij.openapi.wm.IdeFrame frame) {
                        if (frame.getProject() == project) onEdt(CourseMindPanel.this::refreshAsync);
                    }
                });
        refreshAsync();
    }
    @Override public void dispose() { disposed = true; passwordField.setText(""); passwordChange.close(); }
    /** 工具窗口在模态对话框打开时也要能刷新，因此显式使用 {@link ModalityState#any()}。 */
    private void onEdt(@NotNull Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(() -> { if (!disposed && !project.isDisposed()) runnable.run(); }, ModalityState.any());
    }
}
