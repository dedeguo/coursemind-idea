package top.chende.coursemind.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import top.chende.coursemind.api.ApiClient;
import top.chende.coursemind.auth.*;
import top.chende.coursemind.collector.CollectorStatusReader;
import top.chende.coursemind.config.ConnectionProfile;
import top.chende.coursemind.education.*;
import top.chende.coursemind.education.EducationData.*;
import top.chende.coursemind.ui.CourseMindUi;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Native, theme-aware counterpart of UI.html, behind the existing per-window identity gate. */
final class TeachingPanel implements AutoCloseable {
    private record Context(AuthSession session, ConnectionProfile connection, TeachingController.Scope scope) { }
    private record Data(List<Group> groups, List<Task> tasks, Page<Session> sessions) { }
    private record Preview(Task task, List<Pair> pairs, int changed, String action) { }
    private record Conversation(Session session, Page<ChatMessage> records) { }
    private static final String LIST = "list", TASK = "task", CHAT = "chat", LINK = "link";
    private final JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(8))), screens = new JPanel(new CardLayout());
    private final JPanel taskScreen = new JPanel(new BorderLayout()), chatScreen = new JPanel(new BorderLayout()), linkScreen = new JPanel(new BorderLayout());
    private final JComboBox<Object> groups = new JComboBox<>(), linkTasks = new JComboBox<>();
    private final JComboBox<String> linked = new JComboBox<>(new String[]{"全部对话", "未关联任务", "已关联任务"});
    private final DefaultListModel<Task> taskModel = new DefaultListModel<>();
    private final DefaultListModel<Session> sessionModel = new DefaultListModel<>();
    private final JBList<Task> tasks = widthList(taskModel);
    private final JBList<Session> sessions = new JBList<>(sessionModel) {
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override protected void processMouseEvent(MouseEvent event) {
            if (selecting && isEnabled() && SwingUtilities.isLeftMouseButton(event)) {
                if (event.getID() == MouseEvent.MOUSE_PRESSED) {
                    int index = locationToIndex(event.getPoint());
                    if (index >= 0 && getCellBounds(index, index).contains(event.getPoint())) {
                        if (isSelectedIndex(index)) removeSelectionInterval(index, index); else addSelectionInterval(index, index);
                        requestFocusInWindow();
                    }
                }
                return;
            }
            super.processMouseEvent(event);
        }
    };
    private final JPanel listCards = new JPanel(new CardLayout());
    private final JBTextField query = new JBTextField();
    private final JToggleButton taskTab = new JToggleButton("任务列表"), chatTab = new JToggleButton("我的对话");
    private final JButton refresh = icon(AllIcons.Actions.Refresh, "刷新课程与会话"), search = icon(AllIcons.Actions.Search, "搜索");
    private final JButton account = CourseMindUi.link("账号"), selectMode = CourseMindUi.link("批量关联");
    private final JButton prev = icon(AllIcons.Actions.Back, "上一页"), next = icon(AllIcons.Actions.Forward, "下一页");
    private final JButton cancelSelection = CourseMindUi.link("取消选择"), batchLink = new JButton("关联任务");
    private final JButton previewLink = new JButton("预览变更"), cancelLink = new JButton("取消");
    private final JRadioButton addAction = new JRadioButton("添加关联", true), removeAction = new JRadioButton("解除关联");
    private final JCheckBox onlyTask = new JCheckBox("仅看当前任务关联");
    private final JLabel identity = plain(""), count = muted(""), pageCount = muted(""), selectionCount = plain(""), synced = muted("尚未同步"), capture = muted("采集状态待检查");
    private final JTextArea message = CourseMindUi.message(), hint = CourseMindUi.note(""), linkSummary = CourseMindUi.note(""), linkPreview = CourseMindUi.message(), results = CourseMindUi.message();
    private final JPanel hintPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0)), selectionPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    private final TeachingController controller;
    private final BiPredicate<AuthSession, ConnectionProfile> current;
    private final Runnable invalid;
    private final Consumer<String> browser, expire;
    private final BiPredicate<String, String> confirm;
    private Context context;
    private List<Task> allTasks = List.of();
    private List<Session> linkingSessions = List.of();
    private Task selectedTask;
    private Session selectedSession;
    private Preview preview;
    private int taskPage = 1, sessionPage = 1;
    private long sessionTotal;
    private boolean taskHasNext, sessionHasNext, busy, applying, chats, selecting;
    private String screen = LIST, linkBack = LIST;

    TeachingPanel(Project project, Executor delivery, Runnable invalid) {
        this(task -> ApplicationManager.getApplication().executeOnPooledThread(task), delivery,
            (session, connection) -> AuthService.getInstance().isCurrentSession(session, connection), invalid,
            BrowserUtil::browse, sid -> ApplicationManager.getApplication().executeOnPooledThread(() -> AuthService.getInstance().expireSession(sid)), null);
    }
    TeachingPanel(Executor worker, Executor delivery, BiPredicate<AuthSession, ConnectionProfile> current,
                  Runnable invalid, Consumer<String> browser, Consumer<String> expire, BiPredicate<String, String> confirm) {
        this.current = current; this.invalid = invalid; this.browser = browser; this.expire = expire; this.confirm = confirm;
        controller = new TeachingController(worker, delivery, this::setBusy);
        groups.addItem("全部课程与教学班"); groups.setPrototypeDisplayValue("JavaWeb 开发技术 · 计科 2 班"); plainCombo(groups); plainCombo(linkTasks);
        groups.getAccessibleContext().setAccessibleName("课程与教学班筛选"); linked.getAccessibleContext().setAccessibleName("任务关联筛选"); linkTasks.getAccessibleContext().setAccessibleName("选择关联任务");
        query.getAccessibleContext().setAccessibleName("任务或对话搜索");
        tasks.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); sessions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tasks.getAccessibleContext().setAccessibleName("课程任务列表"); sessions.getAccessibleContext().setAccessibleName("本人会话列表");
        tasks.getEmptyText().setText("暂无符合条件的任务"); sessions.getEmptyText().setText("暂无符合条件的对话");
        tasks.setCellRenderer((list, task, i, selected, focus) -> taskCard(list, task, selected));
        sessions.setCellRenderer((list, session, i, selected, focus) -> sessionCard(list, session, selected));
        tasks.setFixedCellHeight(JBUI.scale(116)); sessions.setFixedCellHeight(JBUI.scale(126));
        ButtonGroup tabs = new ButtonGroup(); tabs.add(taskTab); tabs.add(chatTab); taskTab.setSelected(true);
        JPanel navigation = new JPanel(new GridLayout(1, 2)); navigation.add(taskTab); navigation.add(chatTab);
        JButton clearHint = CourseMindUi.link("×"); clearHint.getAccessibleContext().setAccessibleName("清除当前任务提示");
        hintPanel.setBorder(JBUI.Borders.empty(8)); hintPanel.add(hint, BorderLayout.CENTER); hintPanel.add(clearHint, BorderLayout.EAST); hintPanel.setVisible(false);
        root.add(CourseMindUi.stack(6, CourseMindUi.row(identity, account), CourseMindUi.row(CourseMindUi.title("课程任务"), refresh), navigation, hintPanel), BorderLayout.NORTH);
        JPanel listScreen = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        JPanel searchRow = new JPanel(new BorderLayout(JBUI.scale(4), 0)); searchRow.add(query, BorderLayout.CENTER); searchRow.add(search, BorderLayout.EAST);
        linked.setPreferredSize(JBUI.size(102, linked.getPreferredSize().height));
        JPanel searchAndState = new JPanel(new BorderLayout(JBUI.scale(8), 0)); searchAndState.add(searchRow, BorderLayout.CENTER); searchAndState.add(linked, BorderLayout.EAST);
        listScreen.add(CourseMindUi.stack(8, groups, searchAndState, onlyTask, CourseMindUi.row(count, selectMode)), BorderLayout.NORTH);
        listCards.add(listScroll(tasks), "tasks"); listCards.add(listScroll(sessions), "chats"); listScreen.add(listCards, BorderLayout.CENTER);
        JPanel paging = new JPanel(new BorderLayout()); JPanel arrows = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0)); arrows.add(prev); arrows.add(next);
        paging.add(pageCount, BorderLayout.CENTER); paging.add(arrows, BorderLayout.EAST); listScreen.add(paging, BorderLayout.SOUTH);
        screens.add(listScreen, LIST); screens.add(taskScreen, TASK); screens.add(chatScreen, CHAT); screens.add(linkScreen, LINK); root.add(screens, BorderLayout.CENTER);
        selectionPanel.add(CourseMindUi.row(selectionCount, cancelSelection), BorderLayout.CENTER); selectionPanel.add(batchLink, BorderLayout.EAST); selectionPanel.setBorder(JBUI.Borders.empty(8, 0));
        JPanel status = CourseMindUi.row(capture, synced); status.setBorder(JBUI.Borders.customLineTop(JBColor.border()));
        root.add(CourseMindUi.stack(6, selectionPanel, message, status), BorderLayout.SOUTH);
        buildLinkScreen(); root.setBorder(JBUI.Borders.empty(4)); root.setMinimumSize(JBUI.size(0, 0)); root.setVisible(false);
        taskTab.addActionListener(e -> switchTab(false)); chatTab.addActionListener(e -> switchTab(true)); refresh.addActionListener(e -> refresh());
        clearHint.addActionListener(e -> { hint.setText(""); hintPanel.setVisible(false); root.revalidate(); });
        groups.addActionListener(e -> { if (!applying && !busy) { taskPage = sessionPage = 1; selectedTask = null; onlyTask.setSelected(false); refresh(); } });
        linked.addActionListener(e -> { if (!applying && !busy) { sessionPage = 1; refresh(); } });
        onlyTask.addActionListener(e -> { sessionPage = 1; refresh(); });
        search.addActionListener(e -> { taskPage = sessionPage = 1; if (chats) refresh(); else filterTasks(); }); query.addActionListener(e -> { if (search.isEnabled()) search.doClick(); });
        tasks.addListSelectionListener(e -> { if (!applying && !e.getValueIsAdjusting() && tasks.getSelectedValue() != null) { selectedTask = tasks.getSelectedValue(); showTask(); } });
        sessions.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) controls(); });
        sessions.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) {
            int i = sessions.locationToIndex(e.getPoint()); if (i >= 0 && sessions.getCellBounds(i, i).contains(e.getPoint()) && !selecting && e.getClickCount() == 1 && !busy) openSessionDetail(sessionModel.get(i));
        } });
        sessions.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "preview");
        sessions.getActionMap().put("preview", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { if (!selecting && !busy && sessions.getSelectedValue() != null) openSessionDetail(sessions.getSelectedValue()); } });
        prev.addActionListener(e -> { if (chats) { sessionPage--; refresh(); } else { taskPage--; filterTasks(); } }); next.addActionListener(e -> { if (chats) { sessionPage++; refresh(); } else { taskPage++; filterTasks(); } });
        selectMode.addActionListener(e -> select(!selecting)); cancelSelection.addActionListener(e -> select(false)); batchLink.addActionListener(e -> openLinks(List.copyOf(sessions.getSelectedValuesList())));
        controls();
    }
    JComponent component() { return root; }
    void setAccountAction(Runnable action) { account.addActionListener(e -> action.run()); }
    void captureStatus(CollectorStatusReader.Status status) { capture.setText(status.label()); capture.setToolTipText(status.detail()); }
    void showTaskHintForTest(Task task) { setHint(task); }
    void bind(AuthSession session, ConnectionProfile connection) {
        TeachingController.Scope nextScope = session == null || session.environmentId() == null ? null : new TeachingController.Scope(session.sessionId(), connection.serverId(), connection.effectiveUrl(), session.environmentId(), session.accountId(), session.profile().studentId());
        if (Objects.equals(nextScope, context == null ? null : context.scope())) return;
        context = nextScope == null ? null : new Context(session, connection, nextScope); controller.bind(nextScope);
        applying = true; taskModel.clear(); sessionModel.clear(); allTasks = List.of(); groups.removeAllItems(); groups.addItem("全部课程与教学班");
        selectedTask = null; selectedSession = null; linkingSessions = List.of(); preview = null; taskPage = sessionPage = 1; taskHasNext = sessionHasNext = false; sessionTotal = 0;
        query.setText(""); linked.setSelectedIndex(0); onlyTask.setSelected(false); taskScreen.removeAll(); chatScreen.removeAll(); linkTasks.removeAllItems(); linkSummary.setText(""); linkPreview.setText(""); results.setText("");
        hint.setText(""); hintPanel.setVisible(false); message.setText(""); synced.setText("尚未同步"); capture.setText("采集状态待检查"); capture.setToolTipText(null);
        identity.setText(session == null ? "" : session.profile().name() + " · " + session.profile().studentNo()); identity.setToolTipText(identity.getText());
        selecting = false; chats = false; taskTab.setSelected(true); applying = false; show(LIST); root.setVisible(nextScope != null);
    }
    void refresh() {
        Context captured = context; if (captured == null || busy || LINK.equals(screen)) return;
        Integer gid = groups.getSelectedItem() instanceof Group group ? group.id() : null, tid = onlyTask.isSelected() && selectedTask != null ? selectedTask.id() : null;
        Boolean relation = linked.getSelectedIndex() == 0 ? null : linked.getSelectedIndex() == 2; int sp = sessionPage; String q = chats ? query.getText().trim() : ""; message.setText("");
        run(captured, api -> {
            List<Group> allGroups = new ArrayList<>(); List<Task> all = new ArrayList<>();
            for (int page = 1; ; page++) { Page<Group> data = api.teachingGroups(captured.session().accessToken(), page); allGroups.addAll(data.items()); if (!data.hasNext()) break; if (page >= 100) throw new IllegalStateException("课程过多，请使用工作台筛选"); }
            for (int page = 1; ; page++) { Page<Task> data = api.tasks(captured.session().accessToken(), page, gid); all.addAll(data.items()); if (!data.hasNext()) break; if (page >= 100) throw new IllegalStateException("任务过多，请使用工作台筛选"); }
            return new Data(List.copyOf(allGroups), List.copyOf(all), api.sessions(captured.session().accessToken(), sp, tid, q, gid, relation));
        }, data -> {
            applying = true; groups.removeAllItems(); groups.addItem("全部课程与教学班");
            for (Group group : data.groups()) { groups.addItem(group); if (Objects.equals(gid, group.id())) groups.setSelectedItem(group); }
            allTasks = data.tasks(); sessionModel.clear(); data.sessions().items().forEach(sessionModel::addElement); sessionTotal = data.sessions().total(); sessionHasNext = data.sessions().hasNext();
            if (selectedTask != null) { int id = selectedTask.id(); selectedTask = allTasks.stream().filter(t -> t.id() == id).findFirst().orElse(null); }
            applying = false; filterTasks(); synced.setText("刚刚同步"); if (TASK.equals(screen)) { if (selectedTask == null) show(LIST); else showTask(); }
        });
    }
    private void filterTasks() {
        String q = chats ? "" : query.getText().trim().toLowerCase(Locale.ROOT);
        List<Task> matching = allTasks.stream().filter(task -> (task.title() + " " + task.course() + " " + task.markdown()).toLowerCase(Locale.ROOT).contains(q)).toList();
        taskPage = Math.max(1, Math.min(taskPage, Math.max(1, (matching.size() + 19) / 20))); applying = true; taskModel.clear(); matching.stream().skip((long)(taskPage - 1) * 20).limit(20).forEach(taskModel::addElement); applying = false;
        taskHasNext = (long)taskPage * 20 < matching.size(); count.setText(chats ? "最近对话 · " + sessionTotal + " 个对话" : "本学期 · " + matching.size() + " 个任务");
        pageCount.setText("第 " + (chats ? sessionPage : taskPage) + " 页" + ((chats ? sessionModel.isEmpty() : taskModel.isEmpty()) ? " · 暂无匹配内容" : "")); controls();
    }
    private void switchTab(boolean value) {
        if (busy) return; chats = value; select(false); query.setText(""); sessionPage = taskPage = 1; applying = true; linked.setSelectedIndex(0); onlyTask.setSelected(false); applying = false;
        show(LIST); message.setText(""); filterTasks(); refresh();
    }
    private void select(boolean value) { selecting = value; sessions.clearSelection(); sessions.setSelectionMode(value ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION); sessions.repaint(); controls(); }
    private void show(String card) { screen = card; ((CardLayout)screens.getLayout()).show(screens, card); controls(); root.revalidate(); root.repaint(); }
    private void showTask() {
        Task task = selectedTask; if (task == null) { show(LIST); return; }
        JButton back = CourseMindUi.link(chats ? "‹ 返回我的对话" : "‹ 返回任务列表"); back.addActionListener(e -> { tasks.clearSelection(); show(LIST); });
        JTextArea description = CourseMindUi.note(task.markdown() + (task.resources().isEmpty() ? "" : "\n\n资源链接：\n" + String.join("\n", task.resources()))); description.setForeground(JBColor.foreground());
        JButton associate = new JButton("关联已有 AI 对话"); associate.setVisible(task.linkable());
        JButton suggest = CourseMindUi.link("设为当前任务提示"); suggest.setVisible(task.linkable()); suggest.addActionListener(e -> setHint(task));
        JButton web = CourseMindUi.link("在网页查看 ↗"); web.addActionListener(e -> openTask(task));
        associate.addActionListener(e -> { chats = true; chatTab.setSelected(true); onlyTask.setSelected(false); query.setText(""); selecting = true; sessions.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); sessions.clearSelection(); show(LIST); filterTasks(); message.setText("请选择相关对话，再点击底部“关联任务”。"); });
        JPanel body = CourseMindUi.stack(12, back, muted(task.course() + " · " + task.group()), title(task.title()), CourseMindUi.note(task.metadata()), description, associate, suggest, web,
            CourseMindUi.note(task.linkable() ? "关联整个会话，包含后续内容；关联不代表任务完成。" : "任务已关闭、移除或归档，原有对话关联保留。"));
        taskScreen.removeAll(); taskScreen.add(CourseMindUi.scroll(body), BorderLayout.CENTER); show(TASK);
    }
    private void setHint(Task task) { hint.setText("当前任务提示 · " + task.title() + "\n仅提示当前窗口，不会自动关联对话。"); hintPanel.setVisible(true); root.revalidate(); }
    private void openSessionDetail(Session session) {
        Context captured = context; if (captured == null || busy) return;
        run(captured, api -> new Conversation(api.session(captured.session().accessToken(), session.id()), api.sessionPreview(captured.session().accessToken(), session.id())), data -> {
            selectedSession = data.session(); renderSession(data.records());
        });
    }
    private void renderSession(Page<ChatMessage> data) {
        Session session = selectedSession; if (session == null) return;
        JButton back = CourseMindUi.link("‹ 返回我的对话"); back.addActionListener(e -> show(LIST));
        JPanel body = CourseMindUi.stack(10, back, muted(session.source() + " · " + EducationData.time(session.lastAt())), title(session.title()), CourseMindUi.note(taskLabels(session)));
        for (ChatMessage record : data.items()) body.add(CourseMindUi.stack(5, CourseMindUi.title("user".equals(record.role()) ? "你" : "AI"), CourseMindUi.note(record.text())));
        body.add(CourseMindUi.note("显示前 " + data.items().size() + " 条消息，共 " + data.total() + " 条；完整对话可在网页查看。"));
        JButton associate = new JButton("整理任务关联"); associate.addActionListener(e -> openLinks(List.of(selectedSession)));
        JButton web = CourseMindUi.link("在网页查看完整对话 ↗"); web.addActionListener(e -> openSession(session)); body.add(associate); body.add(web);
        chatScreen.removeAll(); chatScreen.add(CourseMindUi.scroll(body), BorderLayout.CENTER); show(CHAT);
    }
    private void buildLinkScreen() {
        ButtonGroup actions = new ButtonGroup(); actions.add(addAction); actions.add(removeAction);
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); actionRow.add(addAction); actionRow.add(removeAction);
        JPanel body = CourseMindUi.stack(12, CourseMindUi.title("关联课程任务"), linkSummary, CourseMindUi.field("选择任务", linkTasks), actionRow, CourseMindUi.note("关联整个会话，包含后续内容。\n关联不会将任务标记为完成。"), linkPreview, results);
        body.setBorder(JBUI.Borders.empty(12)); linkScreen.add(CourseMindUi.scroll(body), BorderLayout.CENTER); JPanel actionsBar = CourseMindUi.row(cancelLink, previewLink); actionsBar.setBorder(JBUI.Borders.empty(8)); linkScreen.add(actionsBar, BorderLayout.SOUTH);
        linkTasks.addActionListener(e -> resetPreview()); addAction.addActionListener(e -> resetPreview()); removeAction.addActionListener(e -> resetPreview());
        cancelLink.addActionListener(e -> { linkingSessions = List.of(); resetPreview(); results.setText(""); if (CHAT.equals(linkBack) && selectedSession != null) openSessionDetail(selectedSession); else { show(LIST); refresh(); } });
        previewLink.addActionListener(e -> { if (preview == null) prepareLinks(); else applyLinks(); });
    }
    private void openLinks(List<Session> chosen) {
        Context captured = context; if (captured == null || chosen.isEmpty() || busy) return;
        run(captured, api -> {
            List<Task> rows = new ArrayList<>();
            for (int page = 1; ; page++) { Page<Task> data = api.tasks(captured.session().accessToken(), page, null); rows.addAll(data.items()); if (!data.hasNext()) break; if (page >= 100) throw new IllegalStateException("任务过多，请使用工作台"); }
            return rows.stream().filter(Task::linkable).toList();
        }, rows -> {
            linkBack = screen; linkingSessions = chosen; linkTasks.removeAllItems(); for (Task task : rows) { linkTasks.addItem(task); if (selectedTask != null && selectedTask.id() == task.id()) linkTasks.setSelectedItem(task); }
            addAction.setSelected(true); resetPreview(); results.setText(""); cancelLink.setText("取消"); linkSummary.setText("为 " + chosen.size() + " 个对话整理任务关联。" + (rows.isEmpty() ? "\n暂无可关联的已发布任务。" : "")); show(LINK);
        });
    }
    private void resetPreview() { preview = null; linkPreview.setText(""); previewLink.setText("预览变更"); controls(); }
    private void prepareLinks() {
        Context captured = context; Task task = linkTasks.getSelectedItem() instanceof Task t ? t : null; if (captured == null || task == null || linkingSessions.isEmpty()) return;
        String action = removeAction.isSelected() ? "remove" : "add"; List<Session> chosen = linkingSessions;
        run(captured, api -> {
            Task fresh = api.task(captured.session().accessToken(), task.id()); if (!fresh.linkable()) throw new IllegalStateException("任务已关闭、移除或归档，请刷新后选择可用任务");
            int changed = 0; List<Pair> pairs = new ArrayList<>();
            for (Session session : chosen) { boolean exists = api.sessionTasks(captured.session().accessToken(), session.id()).stream().anyMatch(t -> t.id() == task.id()); if (exists == "remove".equals(action)) changed++; pairs.add(new Pair(task.id(), session.id())); }
            return new Preview(fresh, List.copyOf(pairs), changed, action);
        }, data -> {
            preview = data; String verb = "add".equals(action) ? "添加" : "解除"; linkPreview.setText("实际" + verb + " " + data.changed() + " 对；" + (chosen.size() - data.changed()) + " 对无需变更。\n" + data.task().course() + " · " + data.task().title()); previewLink.setText("确认" + ("add".equals(action) ? "关联" : "解除")); controls();
        });
    }
    private void applyLinks() {
        Context captured = context; Preview operation = preview; if (captured == null || operation == null) return;
        if (confirm != null && !confirm.test("确认关联变更", linkPreview.getText())) return;
        List<Session> chosen = linkingSessions;
        run(captured, api -> api.sessionLinks(captured.session().accessToken(), operation.action(), operation.pairs()), outcome -> {
            results.setText(String.join("\n", outcome.stream().map(result -> chosen.stream().filter(s -> s.id() == result.sessionId()).map(Session::title).findFirst().orElse("对话") + "：" + result.label()).toList()));
            preview = null; previewLink.setText("预览变更"); cancelLink.setText("返回对话"); linkPreview.setText(""); linkSummary.setText("操作已返回，请查看逐项结果；返回列表后刷新关联标签。"); controls();
        });
    }
    private void openTask(Task task) { Context captured = context; run(captured, api -> { api.task(captured.session().accessToken(), task.id()); return WorkbenchLinks.task(captured.connection().effectiveUrl(), task.id()); }, browser); }
    private void openSession(Session session) { Context captured = context; run(captured, api -> { api.session(captured.session().accessToken(), session.id()); return WorkbenchLinks.session(captured.connection().effectiveUrl(), session.id()); }, browser); }
    private <T> void run(Context captured, Function<ApiClient, T> action, Consumer<T> success) {
        if (captured == null || context == null || !captured.scope().equals(context.scope())) return;
        controller.execute(() -> action.apply(new ApiClient(captured.connection().effectiveUrl())), () -> current.test(captured.session(), captured.connection()), success, text -> {
            applying = true; taskModel.clear(); sessionModel.clear(); allTasks = List.of(); selectedTask = null; selectedSession = null; linkingSessions = List.of(); preview = null;
            taskScreen.removeAll(); chatScreen.removeAll(); linkTasks.removeAllItems(); linkSummary.setText(""); linkPreview.setText(""); results.setText(""); groups.removeAllItems(); groups.addItem("全部课程与教学班"); hint.setText(""); hintPanel.setVisible(false); applying = false;
            show(LIST); message.setText(text + "；请刷新后重试。");
        }, () -> { bind(null, captured.connection()); invalid.run(); }, () -> expire.accept(captured.session().sessionId()));
    }
    private void setBusy(boolean value) { busy = value; controls(); }
    private void controls() {
        boolean ready = context != null && !busy;
        for (JComponent component : List.of(groups, linked, tasks, sessions, query, search, selectMode, onlyTask, cancelSelection, cancelLink, linkTasks, addAction, removeAction)) component.setEnabled(ready);
        refresh.setEnabled(ready && !LINK.equals(screen)); taskTab.setEnabled(ready && !LINK.equals(screen)); chatTab.setEnabled(ready && !LINK.equals(screen));
        linked.setVisible(chats); selectMode.setVisible(chats); onlyTask.setVisible(chats && selectedTask != null); query.getEmptyText().setText(chats ? "搜索正文，回车查询" : "搜索任务，回车查询");
        selectMode.setText(selecting ? "完成选择" : "批量关联"); selectionPanel.setVisible(chats && selecting && LIST.equals(screen)); selectionCount.setText("已选 " + sessions.getSelectedIndices().length + " 个对话");
        batchLink.setEnabled(ready && sessions.getSelectedIndices().length > 0); previewLink.setEnabled(ready && linkTasks.getItemCount() > 0);
        prev.setEnabled(ready && (chats ? sessionPage : taskPage) > 1); next.setEnabled(ready && (chats ? sessionHasNext : taskHasNext));
        taskTab.setSelected(!chats); chatTab.setSelected(chats); ((CardLayout)listCards.getLayout()).show(listCards, chats ? "chats" : "tasks");
    }
    private JPanel taskCard(JList<?> list, Task task, boolean selected) {
        JLabel state = muted(task.removed() ? "已移除" : !task.linkable() ? EducationData.statusLabel(task.status()) : task.timeStatus().equals("ongoing") ? "进行中" : "已发布"); state.setForeground(task.linkable() ? new JBColor(0x267747, 0x8dce9f) : JBColor.GRAY);
        JLabel title = plain(task.title()); title.setFont(title.getFont().deriveFont(Font.BOLD));
        JPanel content = CourseMindUi.stack(6, CourseMindUi.row(muted(task.course()), state), title, muted(singleLine(task.markdown())), CourseMindUi.row(muted(task.group()), muted(task.dueAt() == null ? "不设截止" : EducationData.time(task.dueAt()) + " 截止")));
        return card(list, content, selected, task.title());
    }
    private JPanel sessionCard(JList<?> list, Session session, boolean selected) {
        JLabel title = plain(session.title()); title.setFont(title.getFont().deriveFont(Font.BOLD)); JLabel tags = muted(taskLabels(session)); if (!session.linkedTasks().isEmpty()) tags.setForeground(linkColor());
        JPanel content = CourseMindUi.stack(7, CourseMindUi.row(muted(session.source()), muted(session.recordCount() + " 条消息")), title, muted(EducationData.time(session.lastAt())), tags);
        JPanel body = new JPanel(new BorderLayout(JBUI.scale(6), 0)); body.add(content, BorderLayout.CENTER);
        if (selecting) { JCheckBox check = new JCheckBox(); check.setSelected(selected); check.setEnabled(sessions.isEnabled()); check.setOpaque(false); body.add(check, BorderLayout.WEST); }
        return card(list, body, selected, session.title() + " · " + taskLabels(session));
    }
    private static JPanel card(JList<?> list, JPanel body, boolean selected, String tooltip) {
        JPanel card = new JPanel(new BorderLayout()); card.setBorder(BorderFactory.createCompoundBorder(JBUI.Borders.empty(0, 0, 8, 0), BorderFactory.createCompoundBorder(JBUI.Borders.customLine(selected ? linkColor() : JBColor.border()), JBUI.Borders.empty(10))));
        card.add(body); card.setToolTipText(tooltip); card.setBackground(selected ? new JBColor(0xedf2ff, 0x303e58) : list.getBackground()); transparent(body);
        // Renderer trees are not displayable Swing children. Lay them out before CellRendererPane paints them.
        card.setSize(Math.max(1, list.getWidth()), list.getFixedCellHeight()); layoutRenderer(card); return card;
    }
    private static void layoutRenderer(Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container nested) layoutRenderer(nested); }
    private static void transparent(JPanel parent) { parent.setOpaque(false); for (Component child : parent.getComponents()) if (child instanceof JPanel panel) transparent(panel); }
    private static String taskLabels(Session session) { return session.linkedTasks().isEmpty() ? "未关联任务" : String.join(" · ", session.linkedTasks().stream().map(LinkedTask::title).toList()); }
    private static String singleLine(String value) { String text = value.replaceAll("\\s+", " "); return text.length() > 100 ? text.substring(0, 100) + "…" : text; }
    private static JLabel plain(String text) { JLabel label = new JLabel(text); label.putClientProperty("html.disable", true); label.setMinimumSize(JBUI.size(0, label.getPreferredSize().height)); return label; }
    private static JLabel muted(String text) { JLabel label = plain(text); label.setForeground(JBColor.GRAY); label.setFont(label.getFont().deriveFont(Math.max(10f, label.getFont().getSize2D() - 1))); return label; }
    private static JTextArea title(String text) { JTextArea area = CourseMindUi.note(text); area.setFont(area.getFont().deriveFont(Font.BOLD, area.getFont().getSize2D() + 2)); area.setForeground(JBColor.foreground()); return area; }
    private static Color linkColor() { return JBColor.namedColor("Link.activeForeground", new JBColor(0x315fca, 0x8dafff)); }
    private static JButton icon(javax.swing.Icon icon, String name) { JButton button = new JButton(icon); button.setToolTipText(name); button.getAccessibleContext().setAccessibleName(name); return button; }
    private static <T> JBList<T> widthList(DefaultListModel<T> model) { return new JBList<>(model) { @Override public boolean getScrollableTracksViewportWidth() { return true; } }; }
    private static JScrollPane listScroll(JList<?> list) { JBScrollPane scroll = new JBScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); scroll.setBorder(JBUI.Borders.empty()); scroll.setMinimumSize(JBUI.size(0, 0)); return scroll; }
    private static void plainCombo(JComboBox<Object> combo) { combo.setRenderer(new DefaultListCellRenderer() { @Override public Component getListCellRendererComponent(JList<?> list, Object value, int i, boolean selected, boolean focus) { JLabel label = (JLabel)super.getListCellRendererComponent(list, value, i, selected, focus); label.putClientProperty("html.disable", true); label.setToolTipText(String.valueOf(value)); return label; } }); }
    @Override public void close() { controller.close(); context = null; taskModel.clear(); sessionModel.clear(); allTasks = List.of(); hint.setText(""); results.setText(""); root.setVisible(false); }
}
