package top.chende.coursemind.toolwindow;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import top.chende.coursemind.collector.CollectorStatusReader;
import top.chende.coursemind.stats.*;
import top.chende.coursemind.ui.CourseMindUi;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Only renders supplied state. No networking, inferred scores, or example conversation text. */
final class UsagePanel {
    private final JButton toggle = CourseMindUi.link("今日 AI 使用 ▴");
    private final JButton refresh = CourseMindUi.link("刷新");
    private final JPanel content = new JPanel(new BorderLayout());
    private final JPanel preview = new JPanel(new BorderLayout());
    private final JTextArea collection = CourseMindUi.note("采集状态待检测；最近同步：未知");
    private final JPanel panel;
    private boolean expanded = true;
    UsagePanel(Runnable onRefresh) {
        panel = CourseMindUi.card(CourseMindUi.stack(12, CourseMindUi.row(toggle, refresh), content));
        toggle.setFont(toggle.getFont().deriveFont(Font.BOLD));
        toggle.setHorizontalAlignment(SwingConstants.LEFT);
        refresh.addActionListener(event -> onRefresh.run());
        toggle.addActionListener(event -> {
            expanded = !expanded; content.setVisible(expanded);
            toggle.setText(expanded ? "今日 AI 使用 ▴" : "今日 AI 使用 ▾");
            toggle.getAccessibleContext().setAccessibleDescription(expanded ? "已展开" : "已折叠");
            panel.revalidate(); panel.repaint();
        });
        panel.setVisible(false);
    }
    JPanel component() { return panel; }
    void clear() { preview.removeAll(); content.removeAll(); panel.setVisible(false); collection.setText("采集状态待检测；最近同步：未知"); }
    void status(CollectorStatusReader.Status status) {
        String shared = status.sharedUploadAt().isBlank() ? "" : "\n本机共享上传确认：" + status.sharedUploadAt();
        collection.setText(status.label() + "\n" + status.detail() + shared);
        panel.revalidate(); panel.repaint();
    }
    void render(StatsController.State state) {
        if (state.phase() == StatsController.Phase.SIGNED_OUT) { clear(); return; }
        panel.setVisible(true); refresh.setEnabled(state.phase() != StatsController.Phase.LOADING);
        preview.removeAll(); preview.setVisible(false);
        JPanel body;
        StudentStats stats = state.stats();
        if (stats == null) {
            String text = switch (state.phase()) {
                case LOADING -> "正在加载今日 AI 使用…";
                case EXPIRED -> "登录已过期，请重新登录";
                case FORBIDDEN -> "没有查看统计的权限，请联系教师或管理员";
                default -> "今日 AI 使用暂不可用\n" + state.message();
            };
            body = CourseMindUi.stack(12, CourseMindUi.note(text));
        } else {
            DateTimeFormatter clockTime = DateTimeFormatter.ofPattern("HH:mm").withZone(stats.range().zone());
            String date = stats.range().start().format(DateTimeFormatter.ofPattern("M月d日")) + " · " + stats.range().zone();
            String notice = state.phase() == StatsController.Phase.ERROR
                    ? "更新失败：" + state.message() + "\n以下为旧数据，更新于 " + clockTime.format(state.fetchedAt())
                    : state.phase() == StatsController.Phase.LOADING ? "正在更新，暂显示上次结果" : "";
            JPanel metrics = new JPanel(new GridLayout(2, 2, JBUI.scale(10), JBUI.scale(10)));
            StudentStats.Summary summary = stats.summary();
            metrics.add(metric(Long.toString(summary.promptCount()), "提问次数"));
            metrics.add(metric(Long.toString(summary.sessionCount()), "会话数"));
            metrics.add(metric(format(summary.avgTurns(), "0.0"), "平均轮次"));
            metrics.add(metric(format(summary.multiTurnRatio() * 100, "0.#") + "%", "多轮会话占比"));
            JPanel topics = new JPanel(new BorderLayout());
            java.util.List<JComponent> topicRows = new java.util.ArrayList<>();
            if (stats.topics().isEmpty()) topicRows.add(CourseMindUi.note("暂无主题数据"));
            for (StudentStats.Topic topic : stats.topics()) topicRows.add(metadata(topic.name(), topic.count() + " 次"));
            topics.add(CourseMindUi.stack(8, topicRows.toArray(JComponent[]::new)), BorderLayout.NORTH);
            java.util.List<JComponent> sessions = new java.util.ArrayList<>();
            if (stats.sessions().isEmpty()) sessions.add(CourseMindUi.note("暂无会话"));
            for (StudentStats.Session session : stats.sessions()) {
                JButton open = CourseMindUi.link(session.title()); open.putClientProperty("html.disable", true);
                open.setHorizontalAlignment(SwingConstants.LEFT); open.setToolTipText(session.title());
                open.setMinimumSize(new Dimension(0, open.getPreferredSize().height));
                open.addActionListener(event -> showSession(session, stats.range().zone()));
                sessions.add(CourseMindUi.stack(4,
                        metadata(sessionTime(session.lastActiveAt(), stats.range().zone()) + " · " + source(session.source()), session.turns() + " 轮"),
                        open, new JSeparator()));
            }
            JTextArea message = CourseMindUi.note(notice); message.setVisible(!notice.isBlank());
            body = CourseMindUi.stack(12, CourseMindUi.note(date), message, metrics,
                    stats.empty() ? CourseMindUi.note("今日暂无已上传的 AI 对话记录") : CourseMindUi.note("统计范围：今日已上传的 AI 对话记录"),
                    new JSeparator(), metadata("主要主题", "AI 自动归类"), topics,
                    new JSeparator(), CourseMindUi.title("最近会话"), CourseMindUi.stack(10, sessions.toArray(JComponent[]::new)), preview);
        }
        content.removeAll();
        content.add(CourseMindUi.stack(12, body, new JSeparator(), collection), BorderLayout.NORTH);
        content.setVisible(expanded); panel.revalidate(); panel.repaint();
    }
    private void showSession(StudentStats.Session session, ZoneId zone) {
        JButton close = CourseMindUi.link("关闭"); close.addActionListener(event -> { preview.removeAll(); preview.setVisible(false); panel.revalidate(); panel.repaint(); });
        preview.removeAll();
        preview.add(CourseMindUi.card(CourseMindUi.stack(10, CourseMindUi.row(CourseMindUi.title("会话信息"), close),
                CourseMindUi.note(session.title()),
                CourseMindUi.note("来源：" + source(session.source()) + "\n主题：" + session.topic() + "\n轮次：" + session.turns()
                        + "\n开始：" + sessionTime(session.startedAt(), zone) + "\n最近活动：" + sessionTime(session.lastActiveAt(), zone)),
                CourseMindUi.note("暂不提供对话正文"))), BorderLayout.NORTH);
        preview.setVisible(true); panel.revalidate(); panel.repaint();
    }
    private static JComponent metric(String value, String label) {
        JBLabel number = new JBLabel(value, SwingConstants.CENTER);
        number.setFont(number.getFont().deriveFont(Font.BOLD, number.getFont().getSize2D() * 1.8f));
        JTextArea caption = CourseMindUi.note(label);
        JPanel box = CourseMindUi.stack(4, number, caption); box.setBorder(JBUI.Borders.empty(12));
        box.setBackground(JBColor.namedColor("CourseMind.metricBackground", new JBColor(0xf7f8fa, 0x2b2d30)));
        return box;
    }
    private static JPanel metadata(String left, String right) {
        JBLabel label = new JBLabel(); label.putClientProperty("html.disable", true); label.setText(left);
        label.setToolTipText(left); label.setForeground(JBColor.GRAY); label.setMinimumSize(new Dimension(0, label.getPreferredSize().height));
        JBLabel value = new JBLabel(right); value.setForeground(JBColor.GRAY);
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(8), 0)); row.add(label, BorderLayout.CENTER); row.add(value, BorderLayout.EAST); return row;
    }
    private static String format(double value, String pattern) { return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value); }
    static String sessionTime(String raw, ZoneId zone) {
        if (raw == null || raw.isBlank()) return "时间未知";
        try { return OffsetDateTime.parse(raw).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm")); }
        catch (RuntimeException e) {
            try { return LocalDateTime.parse(raw).format(DateTimeFormatter.ofPattern("HH:mm")) + "（服务端时间）"; }
            catch (RuntimeException ignored) { return "时间未知"; }
        }
    }
    static String source(String source) {
        return switch (source) { case "codebuddy" -> "CodeBuddy"; case "trae-code" -> "Trae"; case "claude-code" -> "Claude Code"; case "workbuddy" -> "WorkBuddy"; case "unknown" -> "未知来源"; default -> source; };
    }
}
