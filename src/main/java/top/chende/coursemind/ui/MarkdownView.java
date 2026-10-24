package top.chende.coursemind.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.*;
import javax.swing.text.html.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Local Markdown rendering. HTML/images cannot create active content or background network requests. */
public final class MarkdownView extends JPanel {
    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true).sanitizeUrls(true).build();
    private static final TextContentRenderer TEXT = TextContentRenderer.builder().build();
    private final Consumer<String> open, copy;

    public MarkdownView(String markdown, Consumer<String> open) {
        this(markdown, open, value -> CopyPasteManager.getInstance().setContents(new StringSelection(value)));
    }
    MarkdownView(String markdown, Consumer<String> open, Consumer<String> copy) {
        super(new BorderLayout()); this.open = open; this.copy = copy; setOpaque(false);
        Node document = PARSER.parse(markdown == null ? "" : markdown);
        document.accept(new AbstractVisitor() {
            @Override public void visit(org.commonmark.node.Image image) {
                String alt = TEXT.render(image).trim(); image.insertBefore(new Text("[图片" + (alt.isBlank() ? "" : "：" + alt) + "]")); image.unlink();
            }
            @Override public void visit(Link link) { if (!safeUrl(link.getDestination())) link.setDestination(""); visitChildren(link); }
        });
        add(blocks(document), BorderLayout.CENTER); setMinimumSize(JBUI.size(0, 0));
    }
    public static boolean safeUrl(String value) {
        try { URI uri = URI.create(value); return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null; }
        catch (RuntimeException ignored) { return false; }
    }
    private JPanel blocks(Node parent) {
        JPanel panel = CourseMindUi.stack(7); panel.setOpaque(false);
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) panel.add(block(child));
        return panel;
    }
    private JComponent block(Node node) {
        if (node instanceof FencedCodeBlock code) return code(code.getInfo(), code.getLiteral());
        if (node instanceof IndentedCodeBlock code) return code("代码", code.getLiteral());
        if (node instanceof BlockQuote) {
            JPanel quote = blocks(node); quote.setBorder(BorderFactory.createCompoundBorder(JBUI.Borders.customLineLeft(accent()), JBUI.Borders.empty(5, 10)));
            return quote;
        }
        if (node instanceof ListBlock list) {
            JPanel rows = CourseMindUi.stack(5); rows.setOpaque(false);
            int number = list instanceof OrderedList ordered && ordered.getMarkerStartNumber() != null ? ordered.getMarkerStartNumber() : 1;
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                JPanel row = new JPanel(new BorderLayout(JBUI.scale(5), 0)); row.setOpaque(false);
                JLabel marker = new JLabel(list instanceof OrderedList ? number++ + "." : "•"); marker.setVerticalAlignment(SwingConstants.TOP);
                row.add(marker, BorderLayout.WEST); row.add(blocks(item), BorderLayout.CENTER); rows.add(row);
            }
            return rows;
        }
        if (node instanceof TableBlock) return table(node);
        if (node instanceof ThematicBreak) return new JSeparator();
        return prose(HTML.render(node));
    }
    private JComponent code(String info, String source) {
        JPanel panel = new JPanel(new BorderLayout()); panel.setBorder(JBUI.Borders.customLine(JBColor.border()));
        String language = info == null || info.isBlank() ? "代码" : info.trim().split("\\s+")[0];
        JLabel caption = new JLabel(language); caption.putClientProperty("html.disable", true); caption.setForeground(JBColor.GRAY);
        JButton copyButton = CourseMindUi.link("复制"); copyButton.getAccessibleContext().setAccessibleName("复制 " + language + " 代码");
        copyButton.addActionListener(event -> { copy.accept(source); copyButton.setText("已复制"); });
        JPanel heading = CourseMindUi.row(caption, copyButton); heading.setBorder(JBUI.Borders.empty(3, 8));
        JTextArea text = new JTextArea(source.stripTrailing()); text.setEditable(false); text.setLineWrap(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, new JLabel().getFont().getSize())); text.setBorder(JBUI.Borders.empty(8));
        text.getAccessibleContext().setAccessibleName(language + " 代码块");
        int height = Math.min(JBUI.scale(220), text.getPreferredSize().height + JBUI.scale(18));
        JScrollPane scroll = boundedScroll(text, height); panel.add(heading, BorderLayout.NORTH); panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }
    private JComponent table(Node table) {
        List<List<String>> rows = new ArrayList<>(); collectRows(table, rows);
        if (rows.isEmpty()) return prose(HTML.render(table));
        String[] headers = rows.remove(0).toArray(String[]::new); String[][] values = rows.stream().map(row -> row.toArray(String[]::new)).toArray(String[][]::new);
        JTable view = new JTable(values, headers) { @Override public boolean isCellEditable(int row, int column) { return false; } };
        view.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); view.setRowHeight(Math.max(JBUI.scale(25), view.getFontMetrics(view.getFont()).getHeight() + JBUI.scale(8)));
        for (int col = 0; col < headers.length; col++) {
            int width = Math.max(JBUI.scale(90), view.getFontMetrics(view.getFont()).stringWidth(headers[col]) + JBUI.scale(24));
            for (String[] row : values) if (col < row.length) width = Math.max(width, Math.min(JBUI.scale(240), view.getFontMetrics(view.getFont()).stringWidth(row[col]) + JBUI.scale(24)));
            view.getColumnModel().getColumn(col).setPreferredWidth(width);
        }
        view.getAccessibleContext().setAccessibleName("Markdown 表格");
        JScrollPane scroll = boundedScroll(view, Math.min(JBUI.scale(240), view.getRowHeight() * values.length + view.getTableHeader().getPreferredSize().height + JBUI.scale(20)));
        scroll.setColumnHeaderView(view.getTableHeader()); return scroll;
    }
    private static void collectRows(Node node, List<List<String>> rows) {
        if (node instanceof TableRow) {
            List<String> cells = new ArrayList<>(); for (Node cell = node.getFirstChild(); cell != null; cell = cell.getNext()) {
                StringBuilder text = new StringBuilder(); for (Node inline = cell.getFirstChild(); inline != null; inline = inline.getNext()) text.append(TEXT.render(inline)); cells.add(text.toString().trim());
            } rows.add(cells);
        } else for (Node child = node.getFirstChild(); child != null; child = child.getNext()) collectRows(child, rows);
    }
    private static JScrollPane boundedScroll(JComponent view, int height) {
        JBScrollPane scroll = new JBScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED) {
            @Override public Dimension getPreferredSize() { return new Dimension(0, height); }
        };
        scroll.setMinimumSize(new Dimension(0, height)); scroll.setBorder(JBUI.Borders.empty()); return scroll;
    }
    private JEditorPane prose(String html) {
        JEditorPane pane = new JEditorPane() {
            @Override public void updateUI() {
                super.updateUI();
                if (getDocument() instanceof HTMLDocument document) document.getStyleSheet().addRule("body{color:" + hex(JBColor.foreground()) + "}a{color:" + hex(accent()) + "}code{color:" + hex(JBColor.GRAY) + "}");
            }
        };
        pane.setEditorKit(new HTMLEditorKit() {
            @Override public ViewFactory getViewFactory() {
                ViewFactory base = super.getViewFactory();
                return element -> {
                    View view = base.create(element);
                    if (view instanceof InlineView) return new InlineView(element) { @Override public float getMinimumSpan(int axis) { return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis); } };
                    return view;
                };
            }
        });
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true); pane.setFont(new JLabel().getFont());
        pane.setEditable(false); pane.setOpaque(false); pane.setBorder(null); pane.setMargin(new Insets(0, 0, 0, 0));
        String color = hex(JBColor.foreground()), muted = hex(JBColor.GRAY), link = hex(accent());
        pane.setText("<html><head><style>body{font-family:sans-serif;font-size:" + pane.getFont().getSize() + "pt;color:" + color
            + ";margin:0}p{margin:0 0 4px 0}h1{font-size:160%;margin:8px 0}h2{font-size:140%;margin:7px 0}h3{font-size:120%;margin:6px 0}h4,h5,h6{font-size:110%;margin:5px 0}code{font-family:monospace;color:" + muted + "}a{color:" + link + "}</style></head><body>" + html + "</body></html>");
        pane.setCaretPosition(0); pane.setMinimumSize(JBUI.size(0, 0));
        pane.addHyperlinkListener(event -> { if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && safeUrl(event.getDescription())) open.accept(event.getDescription()); });
        pane.getAccessibleContext().setAccessibleName("Markdown 正文"); return pane;
    }
    private static Color accent() { return JBColor.namedColor("Link.activeForeground", new JBColor(0x315fca, 0x8dafff)); }
    private static String hex(Color color) { return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()); }
}
