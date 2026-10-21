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
import top.chende.coursemind.hooks.ToolHooks;
import java.util.*;
import javax.swing.JCheckBox;
import top.chende.coursemind.runtime.RuntimePaths;

import javax.swing.JTextArea;
import top.chende.coursemind.ui.CourseMindUi;
import javax.swing.JButton;

import javax.swing.JPanel;

import java.nio.file.Path;

/**
 * 设置页四工具选择、即时 Hook 操作与采集客户端管理。
 *
 * <p>勾选偏好随设置页应用保存；Hook 和部署在预览确认后即时生效。
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
    private final JButton restoreHookButton = new JButton("从最近备份恢复受管条目");
    private final JButton refreshButton = new JButton("检测采集环境");

    private final Map<ToolHooks.Tool, JCheckBox> selections = new LinkedHashMap<>();
    private final Map<ToolHooks.Tool, JTextArea> toolStates = new LinkedHashMap<>();
    private final ToolHooks tools;
    private boolean busy;
    private volatile boolean disposed;
    void dispose() { disposed = true; }
    private final RuntimePaths paths;

    CollectorHookPanel() {
        this(AuthService.getInstance().runtimePaths(), CodeBuddyHooks.defaultSettingsFile());
    }

    CollectorHookPanel(RuntimePaths paths, Path hookFile) {
        this.paths = paths;
        tools = new ToolHooks(Path.of(System.getProperty("user.home", ".")), paths, System.getProperty("os.name", "").toLowerCase().contains("win"));
        build();
    }

    @NotNull JPanel collectorComponent() { return collectorPanel; }

    @NotNull JPanel hookComponent() { return hookPanel; }

    private void build() {
        deployButton.addActionListener(event -> deploy());
        refreshButton.addActionListener(event -> refreshAsync());
        applyHookButton.addActionListener(event -> batch(false, false));
        removeHookButton.addActionListener(event -> batch(true, false));
        restoreHookButton.addActionListener(event -> batch(false, true));
        collectorPanel = CourseMindUi.stack(12, collectorStatus, refreshButton, deployButton,
                CourseMindUi.note("检测为只读操作；部署或更新会修改共享客户端，执行前可预览目标和备份位置。"));
        java.util.List<javax.swing.JComponent> rows = new ArrayList<>();
        for (ToolHooks.Tool tool : ToolHooks.Tool.values()) {
            JCheckBox check = new JCheckBox(tool.title, tool.selected);
            JTextArea state = CourseMindUi.note("尚未检测");
            selections.put(tool, check); toolStates.put(tool, state);
            check.addActionListener(event -> updateCount());
            rows.add(CourseMindUi.stack(6, check, state));
        }
        rows.add(applyHookButton); rows.add(removeHookButton); rows.add(restoreHookButton); rows.add(hookStatus);
        rows.add(CourseMindUi.note("仅处理勾选工具；未安装或执行环境不支持时跳过。预览后即时生效，不随设置页取消撤销。移除仅删除受管命令并备份，保留第三方 Hook。"));
        hookPanel = CourseMindUi.stack(12, rows.toArray(javax.swing.JComponent[]::new));
        updateCount();
    }

    private static String statusText(String text) {
        return text.replace("<html>", "").replace("</html>", "")
                .replace("<br/>", "\n").replace("<b>", "").replace("</b>", "");
    }
    /**
     * 设置对话框是模态的：后台线程必须用 {@link ModalityState#any()}，
     * 否则回调会被压到对话框关闭后才执行（表现为"一直正在检查"或点击无反应）。
     */
    private void onEdt(@NotNull Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(() -> { if (!disposed) runnable.run(); }, ModalityState.any());
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
            Map<ToolHooks.Tool, String> states = new LinkedHashMap<>();
            for (ToolHooks.Tool tool : ToolHooks.Tool.values()) {
                try {
                    ToolHooks.Inspection state = tools.inspect(tool);
                    states.put(tool, state.message() + "\n" + state.file());
                } catch (RuntimeException e) { states.put(tool, "检测失败：" + messageOf(e)); }
            }
            onEdt(() -> states.forEach((tool, text) -> toolStates.get(tool).setText(text)));
            hookText = "检测完成；配置状态不代表采集成功";
            pathText = "";
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
        updateCount();
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

    private String selectedTools() {
        return selections.entrySet().stream().filter(e -> e.getValue().isSelected()).map(e -> e.getKey().source).collect(java.util.stream.Collectors.joining(","));
    }
    boolean selectionModified() { return !selectedTools().equals(top.chende.coursemind.config.CourseMindSettings.getInstance().selectedTools()); }
    void saveSelection() { top.chende.coursemind.config.CourseMindSettings.getInstance().setSelectedTools(selectedTools()); }
    void resetSelection() {
        var stored = Arrays.asList(top.chende.coursemind.config.CourseMindSettings.getInstance().selectedTools().split(","));
        selections.forEach((tool, check) -> check.setSelected(stored.contains(tool.source)));
        updateCount();
    }
    private void updateCount() {
        long count = selections.values().stream().filter(JCheckBox::isSelected).count();
        applyHookButton.setText("预览并配置选中的工具（" + count + "）");
        applyHookButton.setEnabled(count > 0 && !busy);
        removeHookButton.setEnabled(count > 0 && !busy);
        restoreHookButton.setEnabled(count > 0 && !busy);
    }
    private void batch(boolean remove, boolean restore) {
        var selected = selections.entrySet().stream().filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey).toList();
        if (selected.isEmpty() || busy) return;
        busy = true; updateCount();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                java.util.List<ToolHooks.Preview> previews = new ArrayList<>();
                StringBuilder description = new StringBuilder();
                for (ToolHooks.Tool tool : selected) {
                    try {
                        var preview = restore ? tools.previewRestore(tool) : tools.preview(tool, remove); previews.add(preview);
                        description.append(tool.title).append("\n").append(preview.description()).append("\n\n");
                    } catch (RuntimeException e) {
                        String text = "预览失败：" + messageOf(e);
                        onEdt(() -> toolStates.get(tool).setText(text));
                        description.append(tool.title).append("：").append(text).append("\n\n");
                    }
                }
                if (previews.isEmpty() || !confirm(description.toString(), restore ? "恢复选中工具的受管 Hook" : remove ? "移除选中工具的受管 Hook" : "配置或修复选中工具", Messages.getQuestionIcon())) return;
                var results = tools.apply(previews);
                onEdt(() -> {
                    for (var result : results) toolStates.get(result.tool()).setText(result.message());
                    hookStatus.setText("批量操作完成；失败项可保持勾选后重新预览重试。");
                });
            } finally { onEdt(() -> { busy = false; updateCount(); }); }
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
                            answer.set(!disposed && Messages.showYesNoDialog((Project) null, text, title, icon) == Messages.YES),
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
