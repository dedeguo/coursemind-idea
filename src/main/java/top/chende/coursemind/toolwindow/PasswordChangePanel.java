package top.chende.coursemind.toolwindow;

import com.intellij.ui.components.JBPasswordField;
import top.chende.coursemind.auth.AuthService;
import top.chende.coursemind.ui.CourseMindUi;
import javax.swing.*;
import java.util.function.BiConsumer;

/** Inline password form; only callback arguments leave this view, never persisted here. */
final class PasswordChangePanel {
    private final JBPasswordField oldPassword = new JBPasswordField();
    private final JBPasswordField newPassword = new JBPasswordField();
    private final JBPasswordField confirmation = new JBPasswordField();
    private final JTextArea message = CourseMindUi.message();
    private final JButton save = new JButton("确认修改");
    private final JButton cancel = new JButton("取消");
    private final JPanel panel;
    PasswordChangePanel(BiConsumer<String, String> submit) {
        panel = CourseMindUi.card(CourseMindUi.stack(12, CourseMindUi.title("修改密码"),
                CourseMindUi.field("当前密码", oldPassword), CourseMindUi.field("新密码（8～128 位）", newPassword),
                CourseMindUi.field("确认新密码", confirmation), cancel, save, message));
        cancel.addActionListener(e -> close());
        save.addActionListener(e -> {
            String oldValue = new String(oldPassword.getPassword());
            String newValue = new String(newPassword.getPassword());
            String confirmValue = new String(confirmation.getPassword());
            try {
                AuthService.validatePassword(oldValue, newValue);
                if (!newValue.equals(confirmValue)) { showError("两次输入的新密码不一致"); return; }
            } catch (RuntimeException error) { showError(error.getMessage()); return; }
            setBusy(true); clearFields(); submit.accept(oldValue, newValue);
        });
        confirmation.addActionListener(e -> { if (save.isEnabled()) save.doClick(); });
        panel.setVisible(false);
    }
    JPanel component() { return panel; }
    void open() { clearFields(); message.setText(""); setBusy(false); panel.setVisible(true); oldPassword.requestFocusInWindow(); }
    void close() { clearFields(); message.setText(""); panel.setVisible(false); }
    void showError(String text) { setBusy(false); message.setText(text); }
    void setBusy(boolean busy) {
        save.setEnabled(!busy); cancel.setEnabled(!busy);
        oldPassword.setEnabled(!busy); newPassword.setEnabled(!busy); confirmation.setEnabled(!busy);
        message.setText(busy ? "正在修改…" : "");
    }
    private void clearFields() { oldPassword.setText(""); newPassword.setText(""); confirmation.setText(""); }
}
