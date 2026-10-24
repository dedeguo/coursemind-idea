package top.chende.coursemind.toolwindow;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.sun.net.httpserver.HttpServer;
import top.chende.coursemind.auth.AuthSession;
import top.chende.coursemind.config.ConnectionProfile;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class TeachingPanelTest extends BasePlatformTestCase {
    public void testNoGitRequiredHintIsLocalAndAssociationHasExplicitPreview() throws Exception {
        var posts = new ArrayList<String>(); var confirmations = new ArrayList<String>(); var urls = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String task = """
            {"id":2,"group_id":1,"title":"集合练习","course_name":"Java","group_name":"一班","kind":"exercise",
            "status":"published","time_status":"ongoing","open_at":"2026-10-21T01:00:00Z","due_at":null,
            "removed_at":null,"body_markdown":"讨论集合语义","resource_links":[]}
            """;
        var linked = new java.util.concurrent.atomic.AtomicBoolean();
        String session = "{\"id\":3000000000,\"source_session_id\":\"internal-id\",\"title\":\"如何理解集合语义？\",\"source\":\"codebuddy\",\"machine_id\":\"pc\",\"record_count\":2,\"last_at\":\"2026-10-21T02:00:00Z\",\"linked_tasks\":%s}";
        server.createContext("/api/v1/education/", exchange -> {
            String path = exchange.getRequestURI().getPath(), data;
            assertEquals("Bearer private-test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            if (path.endsWith("session-links/batch")) {
                posts.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                linked.set(true);
                data = "{\"results\":[{\"task_id\":2,\"session_id\":3000000000,\"status\":\"added\"}]}";
            } else if (path.endsWith("/teaching-groups")) data = page("{\"id\":1,\"name\":\"一班\",\"course_name\":\"Java\",\"term_name\":\"2026 秋\"}");
            else if (path.endsWith("/sessions/3000000000/tasks")) data = linked.get() ? "[" + task + "]" : "[]";
            else if (path.endsWith("/sessions/3000000000/records")) data = page("{\"record_type\":\"user\",\"prompt\":\"如何理解集合语义？\",\"timestamp\":\"2026-10-21T02:00:00Z\"}");
            else if (path.endsWith("/tasks/2")) data = task;
            else if (path.endsWith("/sessions/3000000000")) data = session.formatted(linked.get() ? "[{\"id\":2,\"title\":\"集合练习\",\"course_name\":\"Java\",\"group_name\":\"一班\"}]" : "[]");
            else if (path.endsWith("/tasks")) data = page(task);
            else data = page(session.formatted(linked.get() ? "[{\"id\":2,\"title\":\"集合练习\",\"course_name\":\"Java\",\"group_name\":\"一班\"}]" : "[]"));
            byte[] body = ("{\"code\":0,\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        TeachingPanel panel = new TeachingPanel(Runnable::run, Runnable::run, (s,c) -> true, () -> { }, urls::add, s -> { },
            (title, text) -> { confirmations.add(text); return true; });
        try {
            ConnectionProfile connection = new ConnectionProfile(url, "", "public");
            panel.bind(new AuthSession("one", url, "private-test-token", new AuthSession.Profile(1,"001","学生",null,"一班","",null), "now", "env-a", 9), connection);
            panel.refresh();
            JList<?> taskList = list(panel.component(), "课程任务列表"), sessions = list(panel.component(), "本人会话列表");
            assertEquals(1, taskList.getModel().getSize());
            render(panel.component(), 320, "tasks-320");
            taskList.setSelectedIndex(0);
            button(panel.component(), "设为当前任务提示").doClick(); assertTrue(posts.isEmpty());
            assertTrue(text(panel.component()).contains("不会自动关联对话"));
            button(panel.component(), "在网页查看 ↗").doClick();
            render(panel.component(), 320, "task-detail-320");
            button(panel.component(), "关联已有 AI 对话").doClick();
            render(panel.component(), 320, "sessions-select-320");
            sessions.dispatchEvent(new java.awt.event.MouseEvent(sessions, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, 10, 10, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertTrue("Checkbox-like rows select without Ctrl", sessions.isSelectedIndex(0));
            sessions.dispatchEvent(new java.awt.event.MouseEvent(sessions, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, 10, 10, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertFalse("Clicking again deselects without Ctrl", sessions.isSelectedIndex(0));
            sessions.setSelectedIndex(0);
            button(panel.component(), "关联任务").doClick();
            button(panel.component(), "预览变更").doClick();
            assertTrue("Preview cannot write links", posts.isEmpty());
            render(panel.component(), 320, "association-preview-320");
            button(panel.component(), "取消").doClick();
            assertTrue("Cancelling a preview cannot write links", posts.isEmpty());
            sessions.setSelectedIndex(0); button(panel.component(), "关联任务").doClick();
            button(panel.component(), "预览变更").doClick();
            radio(panel.component(), "解除关联").doClick();
            assertNotNull("Changing action invalidates the previous preview", button(panel.component(), "预览变更"));
            assertNull(button(panel.component(), "确认关联"));
            radio(panel.component(), "添加关联").doClick(); button(panel.component(), "预览变更").doClick();
            button(panel.component(), "确认关联").doClick();
            assertEquals(1, posts.size()); assertTrue(posts.get(0).contains("3000000000"));
            assertTrue(confirmations.get(0).contains("实际添加 1 对"));
            assertTrue(text(panel.component()).contains("如何理解集合语义？：已添加"));
            button(panel.component(), "返回对话").doClick();
            assertEquals("集合练习", ((top.chende.coursemind.education.EducationData.Session)sessions.getModel().getElementAt(0)).linkedTasks().get(0).title());
            assertEquals(List.of(url + "/education/tasks/2"), urls);
            assertFalse(urls.toString().contains("token"));
            render(panel.component(), 320, "teaching-narrow");
            render(panel.component(), 390, "sessions-390");
            render(panel.component(), 520, "sessions-520");
            button(panel.component(), "取消选择").doClick(); sessions.setSelectedIndex(0);
            sessions.getActionMap().get("preview").actionPerformed(new java.awt.event.ActionEvent(sessions, 0, "preview"));
            assertTrue(text(panel.component()).contains("显示前 1 条消息"));
            button(panel.component(), "在网页查看完整对话 ↗").doClick();
            assertEquals(url + "/education/sessions/3000000000", urls.get(1));
            render(panel.component(), 320, "chat-preview-320");
            LookAndFeel previous = UIManager.getLookAndFeel(); boolean wasDark = !com.intellij.ui.JBColor.isBright();
            try {
                UIManager.setLookAndFeel(new com.intellij.ide.ui.laf.darcula.DarculaLaf()); com.intellij.ui.JBColor.setDark(true);
                SwingUtilities.updateComponentTreeUI(panel.component()); render(panel.component(), 320, "sessions-dark-320");
            } finally { UIManager.setLookAndFeel(previous); com.intellij.ui.JBColor.setDark(wasDark); }
            panel.bind(null, connection); assertFalse(panel.component().isVisible());
            assertFalse(text(panel.component()).contains("讨论集合语义"));
            assertEquals(0, sessions.getModel().getSize()); assertEquals(0, taskList.getModel().getSize());
        } finally { panel.close(); server.stop(0); }
    }
    private static String page(String item) { return "{\"items\":[" + item + "],\"total\":1,\"page\":1,\"page_size\":20}"; }
    private static JList<?> list(Container parent, String name) {
        for (Component c : parent.getComponents()) { if (c instanceof JList<?> l && name.equals(l.getAccessibleContext().getAccessibleName())) return l;
            if (c instanceof Container child) { JList<?> found = list(child, name); if (found != null) return found; } } return null;
    }
    private static JButton button(Container parent, String name) {
        for (Component c : parent.getComponents()) { if (c instanceof JButton b && name.equals(b.getText())) return b;
            if (c instanceof Container child) { JButton found = button(child, name); if (found != null) return found; } } return null;
    }
    private static JRadioButton radio(Container parent, String name) {
        for (Component c : parent.getComponents()) { if (c instanceof JRadioButton b && name.equals(b.getText())) return b;
            if (c instanceof Container child) { JRadioButton found = radio(child, name); if (found != null) return found; } } return null;
    }
    private static String text(Container parent) {
        StringBuilder s = new StringBuilder(); for (Component c : parent.getComponents()) {
            if (c instanceof javax.swing.text.JTextComponent t) s.append(t.getText());
            if (c instanceof Container child) s.append(text(child)); } return s.toString();
    }
    private static void render(JComponent component, int width, String name) throws Exception {
        component.setSize(width, 640); for (int i = 0; i < 5; i++) layout(component);
        BufferedImage image = new BufferedImage(width, 640, BufferedImage.TYPE_INT_RGB); Graphics2D g = image.createGraphics();
        try { component.paint(g); } finally { g.dispose(); }
        Path path = Path.of("build/reports/education"); Files.createDirectories(path); ImageIO.write(image,"png",path.resolve(name+".png").toFile());
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component c : parent.getComponents()) if (c instanceof Container child) layout(child); }
}
