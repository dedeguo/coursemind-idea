package top.chende.coursemind.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.JBColor;
import com.sun.net.httpserver.HttpServer;
import top.chende.coursemind.education.EducationData.ChatMessage;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MarkdownViewTest extends BasePlatformTestCase {
    public void testRichMarkdownCopyLinksAndNoBackgroundImageRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); }); server.start();
        try {
            List<String> copied = new ArrayList<>(), opened = new ArrayList<>();
            String source = """
                ## 参数校验
                **有效参数** 与 `name`，还有 *强调*。

                > 不要忽略空白字符串。

                3. 获取参数
                4. 验证参数

                ```java
                if (name == null || name.isBlank()) {
                    return;
                }
                ```

                | 参数 | 状态码 |
                | --- | --- |
                | 有效 | 200 |

                [文档](https://example.test/docs) [非法](file:///private/data)

                <script>alert('unsafe')</script>
                """ + "\n![远程图片](http://127.0.0.1:" + server.getAddress().getPort() + "/image)\n";
            MarkdownView view = new MarkdownView(source, opened::add, copied::add);
            render(CourseMindUi.scroll(view), 320, 1000, "markdown-task-320");
            JTable table = components(view, JTable.class).get(0);
            assertEquals("有效", table.getValueAt(0, 0)); assertEquals("200", table.getValueAt(0, 1));
            JButton copy = components(view, JButton.class).stream().filter(button -> "复制".equals(button.getText())).findFirst().orElseThrow();
            copy.doClick(); assertEquals("已复制", copy.getText()); assertTrue(copied.get(0).contains("name.isBlank()"));
            String html = String.join("\n", components(view, JEditorPane.class).stream().map(JEditorPane::getText).toList());
            assertTrue(html.contains("<strong>")); assertTrue(html.contains("<h2>"));
            assertFalse(html.contains("<script>")); assertFalse(html.contains("<img")); assertFalse(html.contains("href=\"file:"));
            assertEquals("Images cannot cause automatic HTTP requests", 0, requests.get());
            JEditorPane paragraph = components(view, JEditorPane.class).get(0);
            for (var listener : paragraph.getHyperlinkListeners()) listener.hyperlinkUpdate(new HyperlinkEvent(paragraph, HyperlinkEvent.EventType.ACTIVATED, null, "file:///private"));
            assertTrue(opened.isEmpty());
            for (var listener : paragraph.getHyperlinkListeners()) listener.hyperlinkUpdate(new HyperlinkEvent(paragraph, HyperlinkEvent.EventType.ACTIVATED, null, "https://example.test/docs"));
            assertEquals(List.of("https://example.test/docs"), opened);
            assertFalse(MarkdownView.safeUrl("javascript:alert(1)")); assertFalse(MarkdownView.safeUrl("data:text/html,hi"));
        } finally { server.stop(0); }
    }
    public void testMessageDirectionsTimesAndNarrowDarkLayout() throws Exception {
        MessageBubble ai = new MessageBubble(new ChatMessage("assistant", "**先校验参数**，再返回 JSON。", "2026-10-24T07:01:18Z"), url -> { });
        MessageBubble user = new MessageBubble(new ChatMessage("user", "name 为空时应该返回什么？", "2026-10-24T07:01:05Z"), url -> { });
        JComponent thread = CourseMindUi.scroll(CourseMindUi.stack(18, user, ai));
        render(thread, 320, 600, "markdown-chat-320");
        Component userBody = user.getComponent(0), aiBody = ai.getComponent(0);
        assertTrue("Student bubble is to the right of its avatar", user.getComponent(1).getX() > userBody.getX());
        assertTrue("AI avatar is to the left of the bubble", ai.getComponent(1).getX() < aiBody.getX());
        for (Component body : List.of(userBody, aiBody)) { assertTrue(body.getWidth() > 150); assertTrue(body.getHeight() > 30); }
        assertEquals("2026/10/24 15:01:18", MessageBubble.timestamp("2026-10-24T07:01:18Z"));
        assertEquals("时间未知", MessageBubble.timestamp("invalid"));
        assertTrue(components(user, JLabel.class).stream().anyMatch(label -> label.getText().contains("15:01:05")));
        for (JLabel label : components(user, JLabel.class)) if (label.getText().contains("15:01:05"))
            assertTrue("Full per-message time must fit", label.getWidth() >= label.getPreferredSize().width);
        boolean wasDark = !JBColor.isBright(); LookAndFeel previous = UIManager.getLookAndFeel();
        try {
            UIManager.setLookAndFeel(new com.intellij.ide.ui.laf.darcula.DarculaLaf()); JBColor.setDark(true);
            SwingUtilities.updateComponentTreeUI(thread); render(thread, 320, 600, "markdown-chat-dark-320");
        } finally { UIManager.setLookAndFeel(previous); JBColor.setDark(wasDark); }
    }
    private static <T> List<T> components(Container parent, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : parent.getComponents()) { if (type.isInstance(child)) result.add(type.cast(child)); if (child instanceof Container container) result.addAll(components(container, type)); } return result;
    }
    private static void render(JComponent component, int width, int height, String name) throws Exception {
        component.setSize(width, height); for (int i = 0; i < 5; i++) layout(component);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); Graphics2D g = image.createGraphics();
        try { component.printAll(g); } finally { g.dispose(); }
        Path path = Path.of("build/reports/education", name + ".png"); Files.createDirectories(path.getParent()); ImageIO.write(image, "png", path.toFile());
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container container) layout(container); }
}
