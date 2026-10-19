package top.chende.coursemind.actions;

import com.intellij.openapi.actionSystem.*;
        import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public final class ShowProjectAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        // 只有打开项目时，菜单才可用
        event.getPresentation().setEnabled(
                event.getProject() != null
        );
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        String message =
                "项目名称：" + project.getName() + "\n" +
                        "项目目录：" + project.getBasePath();

        Messages.showInfoMessage(
                project,
                message,
                "CourseMind"
        );
    }
}