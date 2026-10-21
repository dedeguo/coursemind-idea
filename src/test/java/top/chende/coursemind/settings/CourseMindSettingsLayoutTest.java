package top.chende.coursemind.settings;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import top.chende.coursemind.runtime.RuntimePaths;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Renders real settings sections using inspection fixtures; never touches the user's runtime. */
public class CourseMindSettingsLayoutTest extends BasePlatformTestCase {
    public void testSectionsAndAdvancedOptionsFitNarrowSettings() throws Exception {
        Path root = Path.of("build", "tmp", "CourseMind-layout-" + java.util.UUID.randomUUID()).toAbsolutePath();
        CollectorHookPanel hooks = new CollectorHookPanel(RuntimePaths.of(root), root.resolve("settings.json"));
        hooks.renderInspection("本机版本：0.2.9\n路径：C:\\Users\\界面测试\\AppData\\Local\\CourseMind\\collector.exe\n状态：检查完成（测试数据）",
                "已配置 2 条 CourseMind 条目（测试数据）", "文件：" + root.resolve("settings.json"), false);
        JComponent view = new CourseMindConfigurable().buildContent(hooks);
        view.setSize(460, 860);
        render(view, "settings");
        assertButtonsFit(view, view);
        JButton details = findButton(view, "显示配置详情与共享选项");
        assertNotNull(details);
        details.doClick();
        render(view, "settings-details");
        assertNotNull(findButton(view, "收起配置详情与共享选项"));
        assertButtonsFit(view, view);
        assertFalse("Layout verification must not create or deploy a runtime", Files.exists(root));
    }

    public void testDefaultSelectionsCountAndZeroSelectionAreConsistent() throws Exception {
        Path root=Path.of("build/tmp/r4-select-"+java.util.UUID.randomUUID()).toAbsolutePath();
        CollectorHookPanel hooks=new CollectorHookPanel(RuntimePaths.of(root),root.resolve("settings.json"));
        JPanel view=hooks.hookComponent();
        JButton configure=findButton(view,"预览并配置选中的工具（2）"); assertNotNull(configure);
        java.util.List<JCheckBox> boxes=new java.util.ArrayList<>(); collectBoxes(view,boxes);
        assertEquals(4,boxes.size()); assertTrue(boxes.get(0).isSelected()); assertFalse(boxes.get(1).isSelected());
        assertTrue(boxes.get(2).isSelected()); assertFalse(boxes.get(3).isSelected());
        for (JCheckBox box:boxes) if (box.isSelected()) box.doClick();
        assertEquals("预览并配置选中的工具（0）",configure.getText()); assertFalse(configure.isEnabled());
        assertFalse(Files.exists(root));
    }
    private static void collectBoxes(Container parent, java.util.List<JCheckBox> boxes) {
        for (Component child:parent.getComponents()) {
            if (child instanceof JCheckBox box) boxes.add(box);
            else if (child instanceof Container container) collectBoxes(container,boxes);
        }
    }

    private static void render(JComponent view, String name) throws Exception {
        for (int i = 0; i < 5; i++) layout(view);
        BufferedImage image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try { view.printAll(g); } finally { g.dispose(); }
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

    private static JButton findButton(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container container) {
                JButton found = findButton(container, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void assertButtonsFit(Container parent, JComponent root) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof AbstractButton) {
                Rectangle bounds = SwingUtilities.convertRectangle(child.getParent(), child.getBounds(), root);
                assertTrue("Button outside settings: " + bounds,
                        bounds.x >= 0 && bounds.x + bounds.width <= root.getWidth());
            }
            if (child instanceof Container container) assertButtonsFit(container, root);
        }
    }
}
