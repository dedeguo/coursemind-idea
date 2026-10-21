package top.chende.coursemind.toolwindow;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PasswordChangePanelTest extends BasePlatformTestCase {
    public void testValidationDuplicateSubmissionAndCancelClearPasswords() {
        AtomicInteger submits = new AtomicInteger();
        PasswordChangePanel view = new PasswordChangePanel((oldPassword, newPassword) -> submits.incrementAndGet());
        List<JPasswordField> fields = new ArrayList<>(); collect(view.component(), fields);
        JButton save = button(view.component(), "确认修改");
        JButton cancel = button(view.component(), "取消");
        view.open(); fields.get(0).setText("old"); fields.get(1).setText("short"); fields.get(2).setText("short");
        save.doClick(); assertEquals(0, submits.get());
        fields.get(1).setText("new-password"); fields.get(2).setText("different");
        save.doClick(); assertEquals(0, submits.get());
        fields.get(2).setText("new-password"); save.doClick(); assertEquals(1, submits.get());
        save.doClick(); assertEquals(1, submits.get());
        assertFalse(cancel.isEnabled());
        for (JPasswordField field : fields) assertEquals(0, field.getPassword().length);
        view.showError("旧密码不正确"); assertTrue(save.isEnabled());
        fields.get(0).setText("secret"); cancel.doClick(); assertFalse(view.component().isVisible());
        view.open(); for (JPasswordField field : fields) assertEquals(0, field.getPassword().length);
        view.close();
    }
    private static void collect(Container parent, List<JPasswordField> result) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JPasswordField field) result.add(field);
            if (child instanceof Container container) collect(container, result);
        }
    }
    private static JButton button(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton b && text.equals(b.getText())) return b;
            if (child instanceof Container container) { JButton b = button(container, text); if (b != null) return b; }
        }
        return null;
    }
}
