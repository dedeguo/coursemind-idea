package top.chende.coursemind.toolwindow;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ide.ui.laf.darcula.DarculaLaf;
import com.intellij.ui.JBColor;
import top.chende.coursemind.auth.AuthService;
import top.chende.coursemind.auth.AuthSession;
import top.chende.coursemind.config.CourseMindSettings;
import top.chende.coursemind.ui.CourseMindUi;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Geometry regression checks run against the real view, without reading shared auth or networking. */
public class CourseMindPanelLayoutTest extends BasePlatformTestCase {
    public void testNarrowLoginKeepsFieldsUsableAndWrapsLongServer() throws Exception {
        CourseMindSettings settings = CourseMindSettings.getInstance();
        String previous = settings.getServerUrl();
        try {
            settings.setServerUrl("https://example.invalid/very/long/course-mind/server/path/for-layout-regression");
            CourseMindPanel panel = new CourseMindPanel(getProject());
            panel.applySnapshot(new AuthService.Snapshot(AuthService.State.LOGGED_OUT, null, null));
            JComponent view = panel.component();
            render(view, 320, 720, "login-narrow");
            checkFields(view);
            assertVisibleControlsFit(view, view);
            render(view, 600, 900, "login-wide");
            checkFields(view);
        } finally {
            settings.setServerUrl(previous);
        }
    }

    public void testIdentityAndErrorCanRenderWithoutCollectionOrCredentialDetails() throws Exception {
        CourseMindPanel panel = new CourseMindPanel(getProject());
        AuthSession.Profile profile = new AuthSession.Profile(1, "20240105001", "界面测试同学",
                null, "界面测试班级", "", null);
        AuthSession session = new AuthSession("layout-test", "https://example.invalid", "test-only",
                profile, "2026-10-21T00:00:00Z");
        panel.applySnapshot(new AuthService.Snapshot(AuthService.State.LOGGED_IN, session, null));
        render(panel.component(), 640, 720, "identity-wide");
        assertVisibleControlsFit(panel.component(), panel.component());
        JScrollPane identityScroll = findScroll(panel.component());
        identityScroll.getViewport().setViewPosition(new Point(0, 60));
        render(panel.component(), 320, 720, "identity-narrow");
        assertVisibleControlsFit(panel.component(), panel.component());
        JButton change = findButton(panel.component(), "修改密码");
        JButton logout = findButton(panel.component(), "退出登录");
        Rectangle changeBounds = SwingUtilities.convertRectangle(change.getParent(), change.getBounds(), panel.component());
        Rectangle logoutBounds = SwingUtilities.convertRectangle(logout.getParent(), logout.getBounds(), panel.component());
        assertEquals("Account actions must share one row", changeBounds.y, logoutBounds.y);
        assertTrue("Account actions must not overlap", changeBounds.x + changeBounds.width < logoutBounds.x);
        assertFalse(visibleText(panel.component()).contains("test-only"));
        assertFalse(visibleText(panel.component()).contains("有效期"));
        panel.applySnapshot(new AuthService.Snapshot(AuthService.State.ERROR, null,
                "连接暂时不可用，请检查服务器地址与当前网络后重试。".repeat(4)));
        render(panel.component(), 320, 720, "error-narrow");
        assertVisibleControlsFit(panel.component(), panel.component());
    }

    public void testInlinePasswordFormFitsAndClearsAfterIdentityChanges() throws Exception {
        CourseMindPanel panel = new CourseMindPanel(getProject());
        AuthSession.Profile profile = new AuthSession.Profile(1, "20240105001", "界面测试", null, "测试班", "", null);
        panel.applySnapshot(new AuthService.Snapshot(AuthService.State.LOGGED_IN,
                new AuthSession("session-before", "http://example.invalid", "test-only", profile, "now"), null));
        JButton change = findButton(panel.component(), "修改密码");
        assertNotNull(change); change.doClick();
        render(panel.component(), 320, 900, "password-inline");
        checkFields(panel.component()); assertVisibleControlsFit(panel.component(), panel.component());
        assertTrue(visibleText(panel.component()).contains("当前密码"));
        panel.applySnapshot(new AuthService.Snapshot(AuthService.State.LOGGED_OUT, null, null));
        assertFalse(visibleText(panel.component()).contains("当前密码"));
    }
    public void testCompactAndLargeFontFieldsRetainTheirPreferredHeight() throws Exception {
        JTextField input = new JTextField("layout fixture", 18);
        input.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        JComponent view = CourseMindUi.stack(12, CourseMindUi.field("Username", input));
        input.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
        assertTrue("Fixture must cover native fields below the old 20px threshold",
                input.getPreferredSize().height < 20);
        render(view, 320, 180, "input-compact-font");
        checkFields(view);
        input.setFont(input.getFont().deriveFont(48f));
        assertTrue("Fixture must cover scaled fields above the old 50px threshold",
                input.getPreferredSize().height > 50);
        render(view, 320, 180, "input-large-font");
        checkFields(view);
    }

    private static JButton findButton(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton b && text.equals(b.getText())) return b;
            if (child instanceof Container container) { JButton b = findButton(container, text); if (b != null) return b; }
        }
        return null;
    }
    public void testDarkThemeAndShortViewportRemainScrollable() throws Exception {
        LookAndFeel previous = UIManager.getLookAndFeel();
        boolean wasDark = !JBColor.isBright();
        try {
            UIManager.setLookAndFeel(new DarculaLaf());
            JBColor.setDark(true);
            CourseMindPanel panel = new CourseMindPanel(getProject());
            panel.applySnapshot(new AuthService.Snapshot(AuthService.State.NOT_CONFIGURED, null, null));
            render(panel.component(), 320, 360, "login-dark-short");
            assertVisibleControlsFit(panel.component(), panel.component());
            JScrollPane scroll = findScroll(panel.component());
            assertNotNull(scroll);
            assertTrue("Short windows need a scrollable login form", scroll.getVerticalScrollBar().isVisible());
        } finally {
            UIManager.setLookAndFeel(previous);
            JBColor.setDark(wasDark);
        }
    }

    private static JScrollPane findScroll(Container parent) {
        if (parent instanceof JScrollPane scroll) return scroll;
        for (Component child : parent.getComponents()) {
            if (child instanceof Container container) {
                JScrollPane found = findScroll(container);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void render(JComponent view, int width, int height, String name) throws Exception {
        view.setSize(width, height);
        // JTextArea wrapping affects preferred height only after its viewport has a width.
        for (int i = 0; i < 5; i++) layout(view);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try { view.printAll(graphics); } finally { graphics.dispose(); }
        Path output = Path.of("build", "reports", "r1-ui", name + ".png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child.isVisible() && child instanceof Container container) layout(container);
        }
    }

    private static void checkFields(Container parent) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JTextField) {
                // Native font metrics and borders differ across Windows/Linux and UI scales.
                // A usable single-line field retains its preferred height without compression or stretching.
                int preferredHeight = child.getPreferredSize().height;
                assertTrue("Input must have a positive preferred height: " + child, preferredHeight > 0);
                assertEquals("Input must retain its theme/font preferred height: " + child,
                        preferredHeight, child.getHeight());
                assertTrue("Input must remain usable in a narrow window", child.getWidth() >= 160);
            }
            if (child instanceof Container container) checkFields(container);
        }
    }

    private static void assertVisibleControlsFit(Container parent, JComponent root) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JLabel || child instanceof AbstractButton || child instanceof JTextArea) {
                assertTrue("Text clipped vertically: " + child, child.getHeight() >= child.getPreferredSize().height);
            }
            if (child instanceof AbstractButton || child instanceof JTextField || child instanceof JTextArea) {
                Rectangle bounds = SwingUtilities.convertRectangle(child.getParent(), child.getBounds(), root);
                assertTrue("Control outside left edge: " + bounds, bounds.x >= 0);
                assertTrue("Control outside right edge: " + bounds, bounds.x + bounds.width <= root.getWidth());
                assertTrue("Control has no height", bounds.height > 0);
            }
            if (child instanceof Container container) assertVisibleControlsFit(container, root);
        }
    }

    private static String visibleText(Container parent) {
        StringBuilder result = new StringBuilder();
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JLabel label) result.append(label.getText());
            if (child instanceof JTextArea area) result.append(area.getText());
            if (child instanceof Container container) result.append(visibleText(container));
        }
        return result.toString();
    }
}
