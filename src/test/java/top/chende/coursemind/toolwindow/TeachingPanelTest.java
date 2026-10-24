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
        String session = "{\"id\":3000000000,\"source_session_id\":\"学习会话\",\"source\":\"codebuddy\",\"machine_id\":\"pc\",\"record_count\":2,\"last_at\":\"2026-10-21T02:00:00Z\"}";
        server.createContext("/api/v1/education/", exchange -> {
            String path = exchange.getRequestURI().getPath(), data;
            assertEquals("Bearer private-test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            if (path.endsWith("session-links/batch")) {
                posts.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                data = "{\"results\":[{\"task_id\":2,\"session_id\":3000000000,\"status\":\"added\"}]}";
            } else if (path.endsWith("/teaching-groups")) data = page("{\"id\":1,\"name\":\"一班\",\"course_name\":\"Java\",\"term_name\":\"2026 秋\"}");
            else if (path.endsWith("/sessions/3000000000/tasks")) data = "[]";
            else if (path.endsWith("/tasks/2")) data = task;
            else if (path.endsWith("/sessions/3000000000")) data = session;
            else if (path.endsWith("/tasks")) data = page(task);
            else data = page(session);
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
            assertEquals(1, taskList.getModel().getSize()); taskList.setSelectedIndex(0); sessions.setSelectedIndex(0);
            button(panel.component(), "设为本窗口任务提示").doClick(); assertTrue(posts.isEmpty());
            assertTrue(text(panel.component()).contains("仅作提示，不会自动创建关联"));
            button(panel.component(), "关联到所选任务").doClick();
            assertEquals(1, posts.size()); assertTrue(posts.get(0).contains("3000000000"));
            assertTrue(confirmations.get(0).contains("实际添加 1 对"));
            assertTrue(text(panel.component()).contains("学习会话：已添加"));
            button(panel.component(), "在工作台查看任务").doClick();
            assertEquals(List.of(url + "/education/tasks/2"), urls);
            assertFalse(urls.toString().contains("token"));
            render(panel.component(), 320, "teaching-narrow");
            panel.bind(null, connection); assertFalse(panel.component().isVisible());
            assertFalse(text(panel.component()).contains("讨论集合语义"));
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
    private static String text(Container parent) {
        StringBuilder s = new StringBuilder(); for (Component c : parent.getComponents()) {
            if (c instanceof javax.swing.text.JTextComponent t) s.append(t.getText());
            if (c instanceof Container child) s.append(text(child)); } return s.toString();
    }
    private static void render(JComponent component, int width, String name) throws Exception {
        component.setSize(width, 1000); layout(component);
        BufferedImage image = new BufferedImage(width, 1000, BufferedImage.TYPE_INT_RGB); Graphics2D g = image.createGraphics();
        try { component.paint(g); } finally { g.dispose(); }
        Path path = Path.of("build/reports/education"); Files.createDirectories(path); ImageIO.write(image,"png",path.resolve(name+".png").toFile());
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component c : parent.getComponents()) if (c instanceof Container child) layout(child); }
}
