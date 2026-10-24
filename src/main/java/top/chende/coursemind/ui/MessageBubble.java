package top.chende.coursemind.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import top.chende.coursemind.education.EducationData.ChatMessage;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import javax.swing.*;

/** A width-aware chat row: AI on the left, student on the right, with per-message Beijing time. */
public final class MessageBubble extends JPanel {
    private final boolean user;
    private final JPanel content;
    private final JLabel avatar;
    public MessageBubble(ChatMessage message, Consumer<String> openLink) {
        user = "user".equals(message.role()); setOpaque(false);
        getAccessibleContext().setAccessibleName(user ? "用户消息" : "AI 消息");
        JLabel meta = new JLabel((user ? "你" : "AI") + " · " + timestamp(message.timestamp()), user ? SwingConstants.RIGHT : SwingConstants.LEFT);
        meta.setFont(meta.getFont().deriveFont(Math.max(10f, meta.getFont().getSize2D() - 2))); meta.setForeground(JBColor.GRAY);
        meta.setMinimumSize(JBUI.size(0, meta.getPreferredSize().height));
        JPanel bubble = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D)graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(user ? new JBColor(0xeaf4f2, 0x283e38) : new JBColor(0xffffff, 0x2b2d30));
                    g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(12), JBUI.scale(12));
                    g.setColor(user ? new JBColor(0xd7e8e4, 0x36554e) : JBColor.border());
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(12), JBUI.scale(12));
                } finally { g.dispose(); }
                super.paintComponent(graphics);
            }
        };
        bubble.setOpaque(false); bubble.setBorder(JBUI.Borders.empty(10)); bubble.add(new MarkdownView(message.text(), openLink), BorderLayout.CENTER);
        content = CourseMindUi.stack(5, meta, bubble); content.setOpaque(false);
        avatar = new JLabel(user ? "U" : "AI", SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D)graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(user ? new JBColor(0x178274, 0x438e80) : new JBColor(0x637780, 0x5b6f78));
                    g.fillOval(0, 0, getWidth(), getHeight());
                } finally { g.dispose(); }
                super.paintComponent(graphics);
            }
        };
        avatar.setForeground(Color.WHITE); avatar.setFont(avatar.getFont().deriveFont(Font.BOLD, 10f));
        add(content); add(avatar); setLayout(new LayoutManager() {
            @Override public void addLayoutComponent(String name, Component component) { }
            @Override public void removeLayoutComponent(Component component) { }
            @Override public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, 0); }
            @Override public Dimension preferredLayoutSize(Container parent) { return preferred(); }
            @Override public void layoutContainer(Container parent) {
                int width = bodyWidth(), gap = JBUI.scale(31), x = user ? Math.max(0, getWidth() - gap - width) : gap;
                content.setBounds(x, 0, width, preferred().height);
                avatar.setBounds(user ? Math.max(0, getWidth() - JBUI.scale(24)) : 0, JBUI.scale(21), JBUI.scale(24), JBUI.scale(24)); content.doLayout();
            }
        });
    }
    private int bodyWidth() { int available = Math.max(1, (getWidth() > 0 ? getWidth() : JBUI.scale(320)) - JBUI.scale(31)); return Math.max(1, (int)(available * (user ? .88 : .96))); }
    private Dimension preferred() { content.setSize(bodyWidth(), Math.max(1, content.getHeight())); return new Dimension(Math.max(0, getWidth()), Math.max(JBUI.scale(48), content.getPreferredSize().height)); }
    public static String timestamp(String value) {
        try { return DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.parse(value)); }
        catch (RuntimeException ignored) { return "时间未知"; }
    }
}
