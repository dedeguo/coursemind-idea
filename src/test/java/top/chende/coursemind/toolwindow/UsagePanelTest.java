package top.chende.coursemind.toolwindow;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import top.chende.coursemind.stats.*;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;

public class UsagePanelTest extends BasePlatformTestCase {
    public void testMetricsCollapsePreviewAndClear() throws Exception {
        UsagePanel view = new UsagePanel(()->{});
        var stats = StudentStats.parse(StudentStatsTest.fixture());
        view.render(new StatsController.State(StatsController.Phase.READY,stats,Instant.parse("2026-10-21T09:00:00Z"),""));
        JComponent card=view.component(); render(card,"summary");
        assertTrue(text(card).contains("100%")); assertTrue(text(card).contains("3.0"));
        JButton session=button(card,"布局测试会话"); assertNotNull(session); session.doClick();
        render(card,"session-preview"); assertTrue(text(card).contains("暂不提供对话正文"));
        JButton collapse=button(card,"今日 AI 使用 ▴"); collapse.doClick();
        assertFalse(text(card).contains("提问次数"));
        button(card,"今日 AI 使用 ▾").doClick(); assertTrue(text(card).contains("提问次数"));
        view.render(new StatsController.State(StatsController.Phase.ERROR,stats,Instant.parse("2026-10-21T09:00:00Z"),"offline"));
        assertTrue(text(card).contains("旧数据")); render(card,"offline-stale");
        view.clear(); assertFalse(card.isVisible());
    }
    public void testUnavailableIsNotZeroAndUnknownSourceIsNotTrae() throws Exception {
        UsagePanel view=new UsagePanel(()->{});
        view.render(new StatsController.State(StatsController.Phase.ERROR,null,null,"offline"));
        render(view.component(),"unavailable"); assertFalse(text(view.component()).contains("提问次数"));
        assertEquals("未知来源",UsagePanel.source("unknown")); assertEquals("new-tool",UsagePanel.source("new-tool"));
        assertEquals("16:16",UsagePanel.sessionTime("2026-10-21T08:16:00Z",ZoneId.of("Asia/Shanghai")));
    }
    public void testResizeAfterScrollingKeepsEveryTextRowAtFullHeight() throws Exception {
        UsagePanel view = new UsagePanel(() -> {});
        view.render(new StatsController.State(StatsController.Phase.READY,
                StudentStats.parse(StudentStatsTest.fixture()), Instant.parse("2026-10-21T09:00:00Z"), ""));
        enlargeFonts(view.component());
        JScrollPane scroll = (JScrollPane) top.chende.coursemind.ui.CourseMindUi.scroll(view.component());
        int wideHeight = 0;
        for (int width : new int[]{720, 300, 560, 280, 720}) {
            scroll.setSize(width, 420);
            for (int i = 0; i < 3; i++) layout(scroll);
            assertTextHeight(scroll.getViewport().getView());
            assertEquals(scroll.getViewport().getWidth(), scroll.getViewport().getView().getWidth());
            int height = scroll.getViewport().getView().getPreferredSize().height;
            if (wideHeight == 0) wideHeight = height;
            if (width == 280) assertTrue("Wrapped text must increase the scrollable height", height > wideHeight);
            scroll.getViewport().setViewPosition(new Point(0, 100));
        }
        assertEquals("Returning to the original width must restore height", wideHeight,
                scroll.getViewport().getView().getPreferredSize().height);
    }
    private static void enlargeFonts(Component component) {
        if (component.getFont() != null) component.setFont(component.getFont().deriveFont(component.getFont().getSize2D() * 1.5f));
        if (component instanceof Container container) for (Component child : container.getComponents()) enlargeFonts(child);
    }
    private static void assertTextHeight(Component component) {
        if (!component.isVisible()) return;
        if (component instanceof JLabel || component instanceof AbstractButton || component instanceof JTextArea) {
            assertTrue("Text clipped vertically: " + component, component.getHeight() >= component.getPreferredSize().height);
        }
        if (component instanceof Container container) for (Component child : container.getComponents()) assertTextHeight(child);
    }
    private static void render(JComponent view,String name) throws Exception {
        view.setSize(320,1100); for(int i=0;i<6;i++) layout(view);
        BufferedImage image=new BufferedImage(320,1100,BufferedImage.TYPE_INT_RGB); Graphics2D g=image.createGraphics();
        try {view.printAll(g);} finally {g.dispose();}
        Path output=Path.of("build","reports","r3-ui",name+".png"); Files.createDirectories(output.getParent()); ImageIO.write(image,"png",output.toFile());
        check(view,view);
    }
    private static void layout(Container parent) {parent.doLayout(); for(Component child:parent.getComponents()) if(child.isVisible() && child instanceof Container c) layout(c);}
    private static void check(Container parent,JComponent root) {
        for(Component child:parent.getComponents()) {
            if(!child.isVisible()) continue;
            if(child instanceof AbstractButton || child instanceof JTextArea) {
                Rectangle r=SwingUtilities.convertRectangle(child.getParent(),child.getBounds(),root);
                assertTrue("Control extends past card: "+r,r.x>=0 && r.x+r.width<=root.getWidth());
            }
            if(child instanceof Container c) check(c,root);
        }
    }
    private static JButton button(Container parent,String title) {
        for(Component child:parent.getComponents()) {
            if(child instanceof JButton b && title.equals(b.getText())) return b;
            if(child instanceof Container c) {JButton b=button(c,title);if(b!=null)return b;}
        } return null;
    }
    private static String text(Container parent) {
        StringBuilder result=new StringBuilder(); for(Component child:parent.getComponents()) {
            if(!child.isVisible())continue;
            if(child instanceof JLabel label)result.append(label.getText());
            if(child instanceof JTextArea area)result.append(area.getText());
            if(child instanceof Container c)result.append(text(c));
        }return result.toString();
    }
}
