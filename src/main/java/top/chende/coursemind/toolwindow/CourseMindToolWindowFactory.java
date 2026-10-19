package top.chende.coursemind.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
        import java.awt.*;

public final class CourseMindToolWindowFactory
        implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(
            @NotNull Project project,
            @NotNull ToolWindow toolWindow) {

        JPanel panel = new JPanel(new BorderLayout());

        panel.add(
                new JLabel("CourseMind：尚未登录"),
                BorderLayout.NORTH
        );

        JButton loginButton = new JButton("登录");
        panel.add(loginButton, BorderLayout.SOUTH);

        Content content = ContentFactory.getInstance()
                .createContent(panel, "", false);

        toolWindow.getContentManager().addContent(content);
    }
}