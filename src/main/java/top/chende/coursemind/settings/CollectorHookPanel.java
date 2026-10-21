package top.chende.coursemind.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import org.jetbrains.annotations.NotNull;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.auth.AuthService;
import top.chende.coursemind.collector.CollectorService;
import top.chende.coursemind.hooks.CodeBuddyHooks;
import top.chende.coursemind.runtime.RuntimePaths;

import javax.swing.JTextArea;
import top.chende.coursemind.ui.CourseMindUi;
import javax.swing.JButton;

import javax.swing.JPanel;

import java.nio.file.Path;

/**
 * 设置页中的「采集器与 CodeBuddy Hook」区块。
 *
 * <p>M2 只做部署与 Hook 配置；采集状态、队列与上传仍由 collector 负责（M3 读取展示）。
 */
final class CollectorHookPanel {

    private JPanel collectorPanel;
    private JPanel hookPanel;
    private final JTextArea collectorStatus = CourseMindUi.note(" ");
    private final JTextArea hookStatus = CourseMindUi.note(" ");
    private final JTextArea hookPath = CourseMindUi.note(" ");
    private final JButton deployButton = new JButton("部署或更新客户端");
    private final JButton applyHookButton = new JButton("预览并应用 CodeBuddy 配置");
    private final JButton removeHookButton = new JButton("移除 CourseMind 条目");
    private final JButton refreshButton = new JButton("检测采集环境");

    private final RuntimePaths paths;
    private final Path hookFile;

    CollectorHookPanel() {
        this(AuthService.getInstance().runtimePaths(), CodeBuddyHooks.defaultSettingsFile());
    }

    CollectorHookPanel(RuntimePaths paths, Path hookFile) {
        this.paths = paths;
        this.hookFile = hookFile;
        build();
    }

    @NotNull JPanel collectorComponent() { return collectorPanel; }

    @NotNull JPanel hookComponent() { return hookPanel; }

    private void build() {
        deployButton.addActionListener(event -> deploy());
        refreshButton.addActionListener(event -> refreshAsync());
        applyHookButton.addActionListener(event -> applyHook());
        removeHookButton.addActionListener(event -> removeHook());
        collectorPanel = CourseMindUi.stack(12, collectorStatus, refreshButton, deployButton,
                CourseMindUi.note("检测为只读操作；部署或更新会修改共享客户端，执行前可预览目标和备份位置。"));
        hookPanel = CourseMindUi.stack(12, CourseMindUi.title("CodeBuddy"), hookStatus,
                applyHookButton, hookPath, removeHookButton,
                CourseMindUi.note("用户级共享配置，影响 IDEA 内 CodeBuddy 和 CodeBuddy IDE。保留第三方条目；配置或移除即时生效，不随设置页取消撤销。"));
    }

    private static String statusText(String text) {
        return text.replace("<html>", "").replace("</html>", "")
                .replace("<br/>", "\n").replace("<b>", "").replace("</b>", "");
    }
    /**
     * 设置对话框是模态的：后台线程必须用 {@link ModalityState#any()}，
     * 否则回调会被压到对话框关闭后才执行（表现为"一直正在检查"或点击无反应）。
     */
    private static void onEdt(@NotNull Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any());
    }

    void refreshAsync() {
        collectorStatus.setText("正在检查采集器…");
        hookStatus.setText("正在检查 Hook…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String collectorText;
            String hookText;
            String pathText;
            boolean canDeploy;
            try {
                CollectorService.State state = new CollectorService(paths).inspect();
                collectorText = "<html>本机版本：" + orUnknown(state.localVersion())
                        + "　插件内置：" + (state.bundledVersion().isEmpty() ? "无" : state.bundledVersion())
                        + "<br/>路径：" + state.collectorPath()
                        + "<br/>状态：" + state.message() + "</html>";
                canDeploy = state.canApply();
            } catch (RuntimeException e) {
                collectorText = "<html>检查失败：" + messageOf(e) + "</html>";
                canDeploy = false;
            }
            try {
                CodeBuddyHooks hooks = new CodeBuddyHooks(hookFile, paths.collectorExe());
                CodeBuddyHooks.Status status = hooks.inspect();
                pathText = "文件：" + status.file();
                if (!status.valid()) {
                    hookText = "<html>状态：<b>无法解析</b><br/>" + status.error() + "</html>";
                } else if (!status.exists()) {
                    hookText = "<html>状态：未安装（应用后会新建该文件）</html>";
                } else {
                    hookText = "<html>状态：已配置 " + status.managedEntries() + " 条 CourseMind 条目"
                            + "（" + (status.managedEventsPresent().isEmpty()
                            ? "无受管事件" : String.join("、", status.managedEventsPresent())) + "）"
                            + (status.otherEvents().isEmpty()
                            ? "<br/>第三方事件：无"
                            : "<br/>保留第三方事件：" + String.join("、", status.otherEvents()))
                            + "</html>";
                }
            } catch (RuntimeException e) {
                pathText = "文件：" + hookFile;
                hookText = "<html>检查失败：" + messageOf(e) + "</html>";
            }
            String collectorFinal = collectorText;
            String hookFinal = hookText;
            String pathFinal = pathText;
            boolean deployFinal = canDeploy;
            onEdt(() -> renderInspection(collectorFinal, hookFinal, pathFinal, deployFinal));
        });
    }

    void renderInspection(String collectorText, String hookText, String pathText, boolean canDeploy) {
        collectorStatus.setText(statusText(collectorText));
        hookStatus.setText(statusText(hookText));
        hookPath.setText(pathText);
        deployButton.setEnabled(canDeploy);
        applyHookButton.setEnabled(true);
        removeHookButton.setEnabled(true);
    }

    private void deploy() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CollectorService service = new CollectorService(paths);
            CollectorService.State state = service.inspect();
            String plan = "<html>将部署插件内置采集器<br/>"
                    + "本机：" + orUnknown(state.localVersion()) + "　内置：" + state.bundledVersion()
                    + "<br/>目标：" + state.collectorPath()
                    + "<br/><br/>旧版本会备份到 " + paths.backupsDir() + "</html>";
            if (!confirm(plan, "部署采集器", Messages.getQuestionIcon())) {
                return;
            }
            String result;
            try {
                CollectorService.DeployResult deployed = service.deploy();
                result = deployed.changed()
                        ? "部署完成；" + deployed.selfCheckMessage()
                        + (deployed.backup() == null ? "" : "；旧版本已备份：" + deployed.backup())
                        : "无需部署：" + deployed.state().message();
            } catch (CourseMindException e) {
                result = "部署失败：" + e.getMessage();
            }
            String message = result;
            onEdt(() -> {
                Messages.showInfoMessage(message, "部署采集器");
                refreshAsync();
            });
        });
    }

    private void applyHook() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CodeBuddyHooks hooks = new CodeBuddyHooks(hookFile, paths.collectorExe());
            CodeBuddyHooks.Plan plan;
            try {
                plan = hooks.plan();
            } catch (CourseMindException e) {
                showLater("无法应用 Hook 配置", e.getMessage());
                return;
            }
            String text = "<html>" + String.join("<br/>", plan.lines()) + "</html>";
            if (!confirm(text, "应用 CodeBuddy Hook 配置", Messages.getQuestionIcon())) {
                return;
            }
            try {
                CodeBuddyHooks.ApplyResult result = hooks.apply();
                showLater("CodeBuddy Hook", result.message()
                        + (result.backup() == null ? "" : "\n备份：" + result.backup()));
            } catch (CourseMindException e) {
                showLater("应用 Hook 失败", e.getMessage());
            }
            onEdt(this::refreshAsync);
        });
    }

    private void removeHook() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CodeBuddyHooks hooks = new CodeBuddyHooks(hookFile, paths.collectorExe());
            CodeBuddyHooks.Plan plan;
            try {
                plan = hooks.planRemove();
            } catch (CourseMindException e) {
                showLater("无法移除 Hook 配置", e.getMessage());
                return;
            }
            String text = "<html>" + String.join("<br/>", plan.lines()) + "</html>";
            if (!confirm(text, "移除 CourseMind Hook 条目", Messages.getWarningIcon())) {
                return;
            }
            try {
                CodeBuddyHooks.ApplyResult result = hooks.remove();
                showLater("CodeBuddy Hook", result.message()
                        + (result.backup() == null ? "" : "\n备份：" + result.backup()));
            } catch (CourseMindException e) {
                showLater("移除 Hook 失败", e.getMessage());
            }
            onEdt(this::refreshAsync);
        });
    }

    private void showLater(String title, String message) {
        onEdt(() -> Messages.showInfoMessage(message, title));
    }

    /** 在 EDT 上弹确认框并同步等待结果（调用方必须是非 EDT 线程）。 */
    private boolean confirm(String text, String title, javax.swing.Icon icon) {
        java.util.concurrent.atomic.AtomicBoolean answer = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            ApplicationManager.getApplication().invokeAndWait(() ->
                            answer.set(Messages.showYesNoDialog((Project) null, text, title, icon) == Messages.YES),
                    ModalityState.any());
        } catch (RuntimeException e) {
            return false;
        }
        return answer.get();
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "未安装" : value;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
