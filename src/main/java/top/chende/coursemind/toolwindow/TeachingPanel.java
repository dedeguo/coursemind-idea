package top.chende.coursemind.toolwindow;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import top.chende.coursemind.api.ApiClient;
import top.chende.coursemind.auth.*;
import top.chende.coursemind.config.ConnectionProfile;
import top.chende.coursemind.education.*;
import top.chende.coursemind.education.EducationData.*;
import top.chende.coursemind.ui.CourseMindUi;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Per-project teaching view; no Git/VCS/workspace recognition is required. */
final class TeachingPanel implements AutoCloseable {
    private record Context(AuthSession session, ConnectionProfile connection, TeachingController.Scope scope) { }
    private record Data(List<Group> groups, Page<Task> tasks, Page<Session> sessions) { }
    private record Preview(Task task, List<Pair> pairs, int changed) { }
    private final JPanel root;
    private final JComboBox<Object> groups = new JComboBox<>();
    private final DefaultListModel<Task> taskModel = new DefaultListModel<>();
    private final DefaultListModel<Session> sessionModel = new DefaultListModel<>();
    private final JList<Task> tasks = new JList<>(taskModel);
    private final JList<Session> sessions = new JList<>(sessionModel);
    private final JTextArea detail = CourseMindUi.note("选择任务查看说明、时间和资源链接。");
    private final JTextArea message = CourseMindUi.message(), hint = CourseMindUi.note("本窗口尚未设置任务提示。"), target = CourseMindUi.note("先在课程任务中选择关联目标。");
    private final JTextArea taskCount = CourseMindUi.note(""), sessionCount = CourseMindUi.note(""), results = CourseMindUi.note("");
    private final JTextField query = new JTextField();
    private final JCheckBox onlyTask = new JCheckBox("仅看所选任务关联会话");
    private final JButton refresh = new JButton("刷新课程与会话"), taskWeb = new JButton("在工作台查看任务"), sessionWeb = new JButton("在工作台阅读会话");
    private final JButton suggest = new JButton("设为本窗口任务提示"), clearHint = new JButton("清除窗口提示");
    private final JButton add = new JButton("关联到所选任务"), remove = new JButton("解除所选任务关联"), showLinks = new JButton("查看会话已有任务");
    private final JButton taskPrev = new JButton("上一页任务"), taskNext = new JButton("下一页任务"), sessionPrev = new JButton("上一页会话"), sessionNext = new JButton("下一页会话"), search = new JButton("检索会话正文");
    private final TeachingController controller;
    private final BiPredicate<AuthSession, ConnectionProfile> current;
    private final Runnable invalid;
    private final Consumer<String> browser, expire;
    private final BiPredicate<String, String> confirm;
    private Context context;
    private int taskPage = 1, sessionPage = 1;
    private boolean taskHasNext, sessionHasNext, busy, applying;

    TeachingPanel(Project project, Executor delivery, Runnable invalid) {
        this(task -> ApplicationManager.getApplication().executeOnPooledThread(task), delivery,
            (session, connection) -> AuthService.getInstance().isCurrentSession(session, connection), invalid,
            BrowserUtil::browse, sid -> ApplicationManager.getApplication().executeOnPooledThread(() -> AuthService.getInstance().expireSession(sid)),
            (title, text) -> Messages.showYesNoDialog(project, text, title, Messages.getQuestionIcon()) == Messages.YES);
    }
    TeachingPanel(Executor worker, Executor delivery, BiPredicate<AuthSession, ConnectionProfile> current,
                  Runnable invalid, Consumer<String> browser, Consumer<String> expire, BiPredicate<String, String> confirm) {
        this.current = current; this.invalid = invalid; this.browser = browser; this.expire = expire; this.confirm = confirm;
        controller = new TeachingController(worker, delivery, this::setBusy);
        groups.addItem("全部课程 / 教学班"); groups.setPrototypeDisplayValue("课程 · 教学班 · 学期");
        groups.setRenderer(new DefaultListCellRenderer() { @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            label.putClientProperty("html.disable", true); return label;
        } });
        groups.getAccessibleContext().setAccessibleName("课程与教学班筛选");
        tasks.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessions.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tasks.getAccessibleContext().setAccessibleName("课程任务列表"); sessions.getAccessibleContext().setAccessibleName("本人会话列表");
        plainRenderer(tasks); plainRenderer(sessions);
        query.getAccessibleContext().setAccessibleName("会话正文关键词");
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("课程任务", CourseMindUi.stack(10, groups, listScroll(tasks, 120), taskCount,
            CourseMindUi.row(taskPrev, taskNext), listScroll(detail, 145), taskWeb, suggest, clearHint, hint));
        tabs.addTab("我的会话", CourseMindUi.stack(10, target, query, search, onlyTask, listScroll(sessions, 150), sessionCount,
            CourseMindUi.row(sessionPrev, sessionNext), sessionWeb, showLinks, add, remove,
            CourseMindUi.note("关联整个会话（含后续内容），同时确认教学归属；关联不代表任务完成。"), listScroll(results, 95)));
        root = CourseMindUi.card(CourseMindUi.stack(12, CourseMindUi.title("课程任务与 AI 会话"), refresh, message, tabs));
        root.setVisible(false);
        refresh.addActionListener(e -> refresh());
        groups.addActionListener(e -> { if (!applying && !busy) { taskPage = 1; onlyTask.setSelected(false); tasks.clearSelection(); refresh(); } });
        tasks.addListSelectionListener(e -> { if (!applying && !e.getValueIsAdjusting()) { updateDetail(); if (onlyTask.isSelected()) { sessionPage = 1; refresh(); } } });
        sessions.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) controls(); });
        taskPrev.addActionListener(e -> { taskPage--; onlyTask.setSelected(false); refresh(); }); taskNext.addActionListener(e -> { taskPage++; onlyTask.setSelected(false); refresh(); });
        sessionPrev.addActionListener(e -> { sessionPage--; refresh(); }); sessionNext.addActionListener(e -> { sessionPage++; refresh(); });
        search.addActionListener(e -> { sessionPage = 1; refresh(); }); query.addActionListener(e -> { if (search.isEnabled()) search.doClick(); });
        onlyTask.addActionListener(e -> { sessionPage = 1; refresh(); });
        suggest.addActionListener(e -> { Task task = tasks.getSelectedValue(); if (task != null) hint.setText("本窗口任务提示：" + task.course() + " · " + task.title() + "\n仅作提示，不会自动创建关联。"); });
        clearHint.addActionListener(e -> hint.setText("本窗口尚未设置任务提示。"));
        taskWeb.addActionListener(e -> openTask()); sessionWeb.addActionListener(e -> openSession());
        showLinks.addActionListener(e -> showLinks()); add.addActionListener(e -> preview("add")); remove.addActionListener(e -> preview("remove"));
        controls();
    }
    JComponent component() { return root; }
    void showTaskHintForTest(Task task) {
        hint.setText("本窗口任务提示：" + task.course() + " · " + task.title() + "\n仅作提示，不会自动创建关联。");
    }

    void bind(AuthSession session, ConnectionProfile connection) {
        TeachingController.Scope next = session == null || session.environmentId() == null ? null
            : new TeachingController.Scope(session.sessionId(), connection.serverId(), connection.effectiveUrl(), session.environmentId(), session.accountId(), session.profile().studentId());
        if (Objects.equals(next, context == null ? null : context.scope())) return;
        context = next == null ? null : new Context(session, connection, next);
        controller.bind(next); applying = true;
        taskModel.clear(); sessionModel.clear(); groups.removeAllItems(); groups.addItem("全部课程 / 教学班");
        taskPage = sessionPage = 1; taskHasNext = sessionHasNext = false; query.setText(""); onlyTask.setSelected(false);
        hint.setText("本窗口尚未设置任务提示。"); detail.setText("选择任务查看说明、时间和资源链接。");
        target.setText("先在课程任务中选择关联目标。"); results.setText(""); message.setText(""); taskCount.setText(""); sessionCount.setText("");
        applying = false; root.setVisible(next != null); controls();
    }
    void refresh() {
        Context captured = context; if (captured == null || busy) return;
        Integer gid = groups.getSelectedItem() instanceof Group g ? g.id() : null;
        Task selected = tasks.getSelectedValue(); Integer tid = onlyTask.isSelected() && selected != null ? selected.id() : null;
        int tp = taskPage, sp = sessionPage; String q = query.getText().trim();
        message.setText(""); results.setText("");
        run(captured, api -> {
            List<Group> all = new ArrayList<>();
            for (int page = 1; ; page++) { Page<Group> data = api.teachingGroups(captured.session().accessToken(), page); all.addAll(data.items()); if (!data.hasNext()) break; if (page >= 100) throw new IllegalStateException("课程过多，请使用工作台筛选"); }
            return new Data(List.copyOf(all), api.tasks(captured.session().accessToken(), tp, gid), api.sessions(captured.session().accessToken(), sp, tid, q));
        }, data -> {
            applying = true; groups.removeAllItems(); groups.addItem("全部课程 / 教学班");
            for (Group g : data.groups()) { groups.addItem(g); if (Objects.equals(gid, g.id())) groups.setSelectedItem(g); }
            taskModel.clear(); sessionModel.clear(); for (Task t : data.tasks().items()) { taskModel.addElement(t); if (selected != null && t.id() == selected.id()) tasks.setSelectedValue(t, false); }
            data.sessions().items().forEach(sessionModel::addElement);
            taskHasNext = data.tasks().hasNext(); sessionHasNext = data.sessions().hasNext();
            taskCount.setText("第 " + tp + " 页 · 共 " + data.tasks().total() + " 项" + (taskModel.isEmpty() ? " · 暂无任务" : ""));
            sessionCount.setText("第 " + sp + " 页 · 共 " + data.sessions().total() + " 个会话" + (sessionModel.isEmpty() ? " · 暂无符合条件的会话" : ""));
            applying = false; updateDetail(); controls();
        });
    }
    private <T> void run(Context captured, Function<ApiClient, T> action, Consumer<T> success) {
        if (captured == null || context == null || !captured.scope().equals(context.scope())) return;
        controller.execute(() -> action.apply(new ApiClient(captured.connection().effectiveUrl())),
            () -> current.test(captured.session(), captured.connection()), success,
            text -> {
                applying = true; taskModel.clear(); sessionModel.clear(); groups.removeAllItems(); groups.addItem("全部课程 / 教学班");
                onlyTask.setSelected(false); hint.setText("本窗口尚未设置任务提示。"); taskCount.setText(""); sessionCount.setText(""); results.setText("");
                applying = false; updateDetail(); message.setText(text + "；请刷新后重试。"); controls();
            },
            () -> { bind(null, captured.connection()); invalid.run(); }, () -> expire.accept(captured.session().sessionId()));
    }
    private void preview(String action) {
        Context captured = context; Task task = tasks.getSelectedValue(); List<Session> chosen = List.copyOf(sessions.getSelectedValuesList());
        if (captured == null || task == null || chosen.isEmpty()) return;
        message.setText(""); results.setText("");
        run(captured, api -> {
            Task fresh = api.task(captured.session().accessToken(), task.id());
            if (!fresh.linkable()) throw new IllegalStateException("任务已关闭、移除或归档，请刷新后选择可用任务");
            int changed = 0; List<Pair> pairs = new ArrayList<>();
            for (Session session : chosen) {
                boolean exists = api.sessionTasks(captured.session().accessToken(), session.id()).stream().anyMatch(t -> t.id() == task.id());
                if (exists == "remove".equals(action)) changed++;
                pairs.add(new Pair(task.id(), session.id()));
            }
            return new Preview(fresh, List.copyOf(pairs), changed);
        }, preview -> {
            String verb = "add".equals(action) ? "添加" : "解除";
            String text = preview.task().course() + " · " + preview.task().group() + " · " + preview.task().title()
                + "\n已选 " + chosen.size() + " 个会话，实际" + verb + " " + preview.changed() + " 对。"
                + "\n整个会话（含后续内容）；关联同时确认教学归属，不代表任务完成。\n是否确认？";
            if (!confirm.test("确认" + verb + "关联", text)) return;
            run(captured, api -> api.sessionLinks(captured.session().accessToken(), action, preview.pairs()), outcome -> {
                Map<Long, String> names = new HashMap<>(); chosen.forEach(s -> names.put(s.id(), s.sourceId()));
                results.setText(String.join("\n", outcome.stream().map(r -> names.getOrDefault(r.sessionId(), "会话") + "：" + r.label()).toList()));
                message.setText("操作已返回，请查看逐项结果；工作台刷新后可见。");
            });
        });
    }
    private void showLinks() {
        Context captured = context; List<Session> chosen = List.copyOf(sessions.getSelectedValuesList());
        run(captured, api -> {
            List<String> lines = new ArrayList<>();
            for (Session s : chosen) { List<Task> linked = api.sessionTasks(captured.session().accessToken(), s.id());
                lines.add(s.sourceId() + "：" + (linked.isEmpty() ? "尚未关联可见任务" : String.join("、", linked.stream().map(t -> t.course() + " · " + t.title()).toList()))); }
            return String.join("\n", lines);
        }, results::setText);
    }
    private void openTask() {
        Context captured = context; Task task = tasks.getSelectedValue(); if (task == null) return;
        run(captured, api -> { api.task(captured.session().accessToken(), task.id()); return WorkbenchLinks.task(captured.connection().effectiveUrl(), task.id()); }, browser);
    }
    private void openSession() {
        Context captured = context; Session session = sessions.getSelectedValue(); if (session == null) return;
        run(captured, api -> { api.session(captured.session().accessToken(), session.id()); return WorkbenchLinks.session(captured.connection().effectiveUrl(), session.id()); }, browser);
    }
    private void updateDetail() {
        Task task = tasks.getSelectedValue();
        detail.setText(task == null ? "选择任务查看说明、时间和资源链接。" : task.detail()); detail.setCaretPosition(0);
        target.setText(task == null ? "先在课程任务中选择关联目标。" : "关联目标：" + task.course() + " · " + task.group() + " · " + task.title());
        controls();
    }
    private void setBusy(boolean value) { busy = value; controls(); }
    private void controls() {
        boolean ready = context != null && !busy; Task task = tasks.getSelectedValue(); int count = sessions.getSelectedIndices().length;
        groups.setEnabled(ready); tasks.setEnabled(ready); sessions.setEnabled(ready); query.setEnabled(ready); search.setEnabled(ready); refresh.setEnabled(ready);
        taskPrev.setEnabled(ready && taskPage > 1); taskNext.setEnabled(ready && taskHasNext); sessionPrev.setEnabled(ready && sessionPage > 1); sessionNext.setEnabled(ready && sessionHasNext);
        taskWeb.setEnabled(ready && task != null); suggest.setEnabled(ready && task != null); clearHint.setEnabled(ready);
        sessionWeb.setEnabled(ready && count == 1); showLinks.setEnabled(ready && count > 0); onlyTask.setEnabled(ready && task != null);
        add.setEnabled(ready && task != null && task.linkable() && count > 0); remove.setEnabled(add.isEnabled());
    }
    private static JScrollPane listScroll(JComponent view, int height) {
        JScrollPane scroll = new JScrollPane(view); scroll.setPreferredSize(new Dimension(220, height)); scroll.setMinimumSize(new Dimension(0, height)); return scroll;
    }
    private static void plainRenderer(JList<?> list) {
        list.setCellRenderer(new DefaultListCellRenderer() { @Override public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(l, value, i, selected, focus); label.putClientProperty("html.disable", true); label.setToolTipText(String.valueOf(value)); return label;
        } });
    }
    @Override public void close() { controller.close(); context = null; taskModel.clear(); sessionModel.clear(); hint.setText(""); results.setText(""); root.setVisible(false); }
}
