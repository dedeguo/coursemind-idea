package top.chende.coursemind.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/** Shared, theme-aware layout primitives for the tool window and settings. */
public final class CourseMindUi {
    private CourseMindUi() { }

    /** GridBag keeps each row at its preferred height, even in a tall tool window. */
    public static JPanel stack(int gap, JComponent... children) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < children.length; i++) {
            c.gridy = i;
            c.insets = JBUI.insets(i == 0 ? 0 : gap, 0, 0, 0);
            panel.add(children[i], c);
        }
        c.gridy = children.length;
        c.weighty = 1;
        c.insets = JBUI.emptyInsets();
        JPanel filler = new JPanel();
        filler.setPreferredSize(new Dimension(0, 0));
        panel.add(filler, c);
        return panel;
    }

    public static JBLabel badge(String text, int size, boolean round) {
        JBLabel label = new JBLabel(text, SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D graphics = (Graphics2D) g.create();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    graphics.setColor(round
                            ? JBColor.namedColor("CourseMind.avatarBackground", new JBColor(0xedf2ff, 0x303e58))
                            : JBColor.namedColor("CourseMind.brandBackground", new JBColor(0x315fca, 0x426fd1)));
                    int arc = round ? getHeight() : JBUI.scale(8);
                    graphics.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                } finally { graphics.dispose(); }
                super.paintComponent(g);
            }
        };
        label.setPreferredSize(JBUI.size(size));
        label.setMinimumSize(JBUI.size(size));
        label.setForeground(round
                ? JBColor.namedColor("Link.activeForeground", new JBColor(0x315fca, 0x8dafff))
                : Color.WHITE);
        return label;
    }

    public static JPanel card(JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(16)));
        panel.add(content, BorderLayout.NORTH);
        return panel;
    }

    public static JBLabel title(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    public static JButton link(String text) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setBorder(JBUI.Borders.empty(4, 0));
        button.setForeground(JBColor.namedColor("Link.activeForeground", new JBColor(0x315fca, 0x8dafff)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    /** Opposite edges, same baseline; actions keep their natural width. */
    public static JPanel row(JComponent left, JComponent right) {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        panel.add(left, BorderLayout.WEST);
        panel.add(right, BorderLayout.EAST);
        return panel;
    }

    public static JPanel detailRow(String caption, JBLabel value) {
        JBLabel label = new JBLabel(caption);
        label.setForeground(JBColor.GRAY);
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        value.setMinimumSize(new Dimension(0, value.getPreferredSize().height));
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        panel.add(label, BorderLayout.WEST);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    /** Plain text with wrapping: paths, errors and server values cannot inject HTML. */
    public static JTextArea note(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setOpaque(false);
        area.setFont(new JBLabel().getFont());
        area.setForeground(JBColor.GRAY);
        area.setBorder(null);
        return area;
    }

    /** Transient messages take no space before the first result or error arrives. */
    public static JTextArea message() {
        JTextArea area = new JTextArea() {
            @Override public void setText(String text) {
                super.setText(text);
                setVisible(text != null && !text.isBlank());
            }
        };
        area.setEditable(false);
        area.setLineWrap(true);
        area.setOpaque(false);
        area.setFont(new JBLabel().getFont());
        area.setBorder(null);
        area.setText("");
        return area;
    }

    public static JPanel field(String label, JComponent input) {
        JBLabel caption = new JBLabel(label);
        caption.setLabelFor(input);
        return stack(6, caption, input);
    }

    public static JPanel section(String title, JComponent body) {
        JPanel heading = new JPanel(new BorderLayout());
        heading.add(title(title), BorderLayout.NORTH);
        heading.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.emptyBottom(8)));
        JPanel result = stack(12, heading, body);
        result.setBorder(JBUI.Borders.emptyBottom(16));
        return result;
    }

    public static JComponent scroll(JComponent content) {
        // The viewport owns width; long paths must not force horizontal scrolling.
        JPanel view = new WidthTrackingPanel();
        view.add(content, BorderLayout.NORTH);
        JBScrollPane scroll = new JBScrollPane(view,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        return scroll;
    }

    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        private WidthTrackingPanel() { super(new BorderLayout()); }
        @Override public Dimension getMinimumSize() { return new Dimension(0, 0); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
            return JBUI.scale(16);
        }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
            return Math.max(JBUI.scale(16), r.height - JBUI.scale(16));
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
