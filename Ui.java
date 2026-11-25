import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Enumeration;

/** Tema e componentes visuais compartilhados pelo cliente e pelo servidor. */
final class Ui {
    private Ui() { }

    static final Color BG = new Color(0xF3F4F6);
    static final Color CARD = Color.WHITE;
    static final Color TEXT = new Color(0x111827);
    static final Color MUTED = new Color(0x6B7280);
    static final Color BORDER = new Color(0xE5E7EB);
    static final Color PRIMARY = new Color(0x2563EB);
    static final Color PRIMARY_DK = new Color(0x1D4ED8);
    static final Color SUCCESS = new Color(0x16A34A);
    static final Color DANGER = new Color(0xDC2626);
    static final Color WARN = new Color(0xD97706);
    static final Color HEADER = new Color(0x1E293B);
    static final Color SIDEBAR = new Color(0x0F172A);

    static final Font BASE = new Font("Segoe UI", Font.PLAIN, 13);

    /** Fonte e L&F do sistema; chamar antes de criar qualquer janela. */
    static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object k = keys.nextElement();
            if (UIManager.get(k) instanceof Font) UIManager.put(k, new FontUIResource(BASE));
        }
        UIManager.put("OptionPane.messageFont", new FontUIResource(BASE));
        UIManager.put("OptionPane.buttonFont", new FontUIResource(BASE));
    }

    // ------------------------------------------------------------ texto
    static JLabel title(String s) {
        JLabel l = new JLabel(s);
        l.setFont(BASE.deriveFont(Font.BOLD, 20f));
        l.setForeground(TEXT);
        return l;
    }

    static JLabel heading(String s) {
        JLabel l = new JLabel(s);
        l.setFont(BASE.deriveFont(Font.BOLD, 14f));
        l.setForeground(TEXT);
        return l;
    }

    static JLabel muted(String s) {
        JLabel l = new JLabel(s);
        l.setFont(BASE.deriveFont(12f));
        l.setForeground(MUTED);
        return l;
    }

    // ------------------------------------------------------------ containers
    static JPanel card(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setBackground(CARD);
        p.setBorder(new CompoundBorder(new LineBorder(BORDER, 1, true), new EmptyBorder(14, 16, 14, 16)));
        return p;
    }

    static JPanel transparent(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setOpaque(false);
        return p;
    }

    static JScrollPane scroll(Component c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(new LineBorder(BORDER, 1));
        sp.getViewport().setBackground(Color.WHITE);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /** Tabela com altura minima propria (a pagina rola em vez de espremer a tabela). */
    static JScrollPane scroll(Component c, int prefHeight) {
        JScrollPane sp = scroll(c);
        sp.setPreferredSize(new Dimension(100, prefHeight));
        sp.setMinimumSize(new Dimension(100, Math.min(prefHeight, 120)));
        return sp;
    }

    /** Painel rolavel: acompanha a largura da janela e so rola na vertical quando falta altura. */
    static class ScrollPage extends JPanel implements Scrollable {
        ScrollPage(LayoutManager lm) {
            super(lm);
            setOpaque(false);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 100; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            Container p = getParent();
            return p instanceof JViewport && p.getHeight() > getPreferredSize().height;
        }
    }

    /** Envolve uma pagina em uma area rolavel sem borda. */
    static JScrollPane page(JComponent content) {
        ScrollPage wrapper = new ScrollPage(new BorderLayout());
        wrapper.add(content, BorderLayout.CENTER);
        JScrollPane sp = new JScrollPane(wrapper, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /** Titulo da pagina + subtitulo, com componentes opcionais alinhados a direita. */
    static JPanel pageHeader(String title, String subtitle, Component... right) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JPanel left = transparent(new GridLayout(2, 1, 0, 2));
        left.add(title(title));
        left.add(muted(subtitle));
        p.add(left, BorderLayout.WEST);
        if (right.length > 0) {
            JPanel r = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            for (Component c : right) r.add(c);
            p.add(r, BorderLayout.EAST);
        }
        p.setBorder(new EmptyBorder(0, 0, 12, 0));
        return p;
    }

    /** Rotulo pequeno acima do campo. */
    static JPanel labeled(String label, Component field) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setFont(BASE.deriveFont(Font.BOLD, 12f));
        l.setForeground(new Color(0x374151));
        p.add(l, BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    /** Empilha componentes na vertical com espacamento. */
    static JPanel vstack(int gap, Component... items) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (int i = 0; i < items.length; i++) {
            if (items[i] instanceof JComponent) {
                JComponent jc = (JComponent) items[i];
                jc.setAlignmentX(Component.LEFT_ALIGNMENT);
                // sem isso o BoxLayout estica os itens na vertical
                jc.setMaximumSize(new Dimension(Integer.MAX_VALUE, jc.getPreferredSize().height));
            }
            p.add(items[i]);
            if (i < items.length - 1) p.add(Box.createVerticalStrut(gap));
        }
        return p;
    }

    /** FlowLayout que quebra os componentes em novas linhas quando a janela fica estreita. */
    static class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension m = layoutSize(target, false);
            m.width -= getHgap() + 1;
            return m;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                Container c = target;
                while (c.getSize().width == 0 && c.getParent() != null) c = c.getParent();
                int targetWidth = c.getSize().width;
                if (c != target) targetWidth -= 48; // margem dos cartoes ancestrais
                if (targetWidth <= 0) targetWidth = Integer.MAX_VALUE;

                int hgap = getHgap(), vgap = getVgap();
                Insets in = target.getInsets();
                int extra = in.left + in.right + hgap * 2;
                int maxWidth = targetWidth == Integer.MAX_VALUE ? targetWidth : targetWidth - extra;

                Dimension dim = new Dimension(0, 0);
                int rowW = 0, rowH = 0;
                for (int i = 0; i < target.getComponentCount(); i++) {
                    Component m = target.getComponent(i);
                    if (!m.isVisible()) continue;
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowW + d.width > maxWidth && rowW > 0) {
                        addRow(dim, rowW, rowH);
                        rowW = 0;
                        rowH = 0;
                    }
                    if (rowW != 0) rowW += hgap;
                    rowW += d.width;
                    rowH = Math.max(rowH, d.height);
                }
                addRow(dim, rowW, rowH);
                dim.width += extra;
                dim.height += in.top + in.bottom + vgap * 2;
                return dim;
            }
        }

        private void addRow(Dimension dim, int rowW, int rowH) {
            dim.width = Math.max(dim.width, rowW);
            if (dim.height > 0) dim.height += getVgap();
            dim.height += rowH;
        }
    }

    // ------------------------------------------------------------ campos
    static <T extends JTextField> T field(T f) {
        f.setBorder(new CompoundBorder(new LineBorder(new Color(0xD1D5DB), 1, true), new EmptyBorder(6, 8, 6, 8)));
        f.setFont(BASE);
        return f;
    }

    static JTextField field(int cols) {
        return field(new JTextField(cols));
    }

    static JPasswordField password(int cols) {
        return field(new JPasswordField(cols));
    }

    static <T extends JComponent> T combo(T c) {
        c.setFont(BASE);
        return c;
    }

    // ------------------------------------------------------------ botoes
    enum Kind { PRIMARY, SECONDARY, DANGER, SUCCESS, GHOST }

    static class Btn extends JButton {
        private final Kind kind;
        private boolean hover;

        Btn(String text, Kind kind) {
            super(text);
            this.kind = kind;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setFont(BASE.deriveFont(Font.BOLD, 13f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(8, 16, 8, 16));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color bg, fg, line = null;
            if (!isEnabled()) {
                bg = new Color(0xF3F4F6);
                fg = new Color(0x9CA3AF);
            } else {
                switch (kind) {
                    case PRIMARY -> { bg = hover ? PRIMARY_DK : PRIMARY; fg = Color.WHITE; }
                    case SUCCESS -> { bg = hover ? new Color(0x15803D) : SUCCESS; fg = Color.WHITE; }
                    case DANGER -> { bg = hover ? new Color(0xB91C1C) : DANGER; fg = Color.WHITE; }
                    case GHOST -> { bg = hover ? new Color(0xE5E7EB) : new Color(0, 0, 0, 0); fg = new Color(0x374151); }
                    default -> { bg = hover ? new Color(0xF3F4F6) : Color.WHITE; fg = TEXT; line = new Color(0xD1D5DB); }
                }
            }
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            if (line != null && isEnabled()) {
                g2.setColor(line);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            }
            g2.dispose();
            setForeground(fg);
            super.paintComponent(g);
        }
    }

    static Btn button(String text, Kind kind) {
        return new Btn(text, kind);
    }

    /** Botao de navegacao da barra lateral. */
    static class NavButton extends JToggleButton {
        private boolean hover;

        NavButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(BASE.deriveFont(Font.BOLD, 14f));
            setForeground(new Color(0xE2E8F0));
            setBorder(new EmptyBorder(11, 16, 11, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (isSelected()) g2.setColor(PRIMARY);
            else if (hover) g2.setColor(new Color(0x1E293B));
            else g2.setColor(new Color(0, 0, 0, 0));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.dispose();
            setForeground(Color.WHITE);
            super.paintComponent(g);
        }
    }

    // ------------------------------------------------------------ indicadores
    static class Dot extends JComponent {
        private Color color = DANGER;

        Dot() { setPreferredSize(new Dimension(12, 12)); }

        void set(Color c) { color = c; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(1, 1, 10, 10);
            g2.dispose();
        }
    }

    /** Etiqueta arredondada (ex.: perfil, contador). */
    static class Chip extends JLabel {
        private Color bg;

        Chip(String text, Color bg, Color fg) {
            super(text, SwingConstants.CENTER);
            this.bg = bg;
            setForeground(fg);
            setOpaque(false);
            setFont(BASE.deriveFont(Font.BOLD, 11f));
            setBorder(new EmptyBorder(3, 10, 3, 10));
        }

        void setColors(Color bg, Color fg) {
            this.bg = bg;
            setForeground(fg);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ------------------------------------------------------------ campo de data
    /** Campo AAAA-MM-DD com botoes de dia anterior/proximo e "hoje". */
    static class DateField extends JPanel {
        private final JTextField tf = field(9);

        DateField(String initial) {
            super(new BorderLayout(4, 0));
            setOpaque(false);
            tf.setText(initial == null ? "" : initial);
            tf.setToolTipText("Formato AAAA-MM-DD");
            JButton prev = small("<");
            JButton next = small(">");
            JButton today = small("Hoje");
            prev.addActionListener(e -> shift(-1));
            next.addActionListener(e -> shift(1));
            today.addActionListener(e -> tf.setText(LocalDate.now().toString()));
            JPanel right = new JPanel(new GridLayout(1, 3, 3, 0));
            right.setOpaque(false);
            right.add(prev);
            right.add(next);
            right.add(today);
            add(tf, BorderLayout.CENTER);
            add(right, BorderLayout.EAST);
        }

        private static JButton small(String t) {
            JButton b = button(t, Kind.SECONDARY);
            b.setBorder(new EmptyBorder(6, 8, 6, 8));
            b.setFont(BASE.deriveFont(Font.BOLD, 12f));
            return b;
        }

        private void shift(int days) {
            LocalDate d;
            try {
                d = LocalDate.parse(tf.getText().trim());
            } catch (DateTimeParseException e) {
                d = LocalDate.now();
            }
            tf.setText(d.plusDays(days).toString());
        }

        String getText() { return tf.getText(); }
        void setText(String s) { tf.setText(s); }
        JTextField input() { return tf; }
    }

    // ------------------------------------------------------------ tabelas
    static void table(JTable t, int... badgeCols) {
        t.setRowHeight(32);
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(true);
        t.setGridColor(new Color(0xF1F5F9));
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setSelectionBackground(new Color(0xDBEAFE));
        t.setSelectionForeground(TEXT);
        t.setFillsViewportHeight(true);
        t.setFont(BASE);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setAutoCreateRowSorter(true);

        JTableHeader h = t.getTableHeader();
        h.setReorderingAllowed(false);
        h.setPreferredSize(new Dimension(100, 34));
        h.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, false, false, r, c);
                l.setBackground(new Color(0xF8FAFC));
                l.setForeground(MUTED);
                l.setFont(BASE.deriveFont(Font.BOLD, 12f));
                l.setBorder(new CompoundBorder(new LineBorder(BORDER, 1), new EmptyBorder(0, 10, 0, 10)));
                return l;
            }
        });
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, s, false, r, c);
                l.setBorder(new EmptyBorder(0, 10, 0, 10));
                if (!s) l.setBackground(r % 2 == 0 ? Color.WHITE : new Color(0xF9FAFB));
                if (!s) l.setForeground(TEXT);
                return l;
            }
        });
        for (int c : badgeCols) t.getColumnModel().getColumn(c).setCellRenderer(new BadgeRenderer());
    }

    /** Colore e traduz valores de status (active, livre, sim, admin...). */
    static class BadgeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, s, false, r, c);
            String raw = v == null ? "" : v.toString();
            String text = raw;
            Color fg = TEXT;
            switch (raw) {
                case "active" -> { text = "Ativa"; fg = SUCCESS; }
                case "inactive" -> { text = "Inativa"; fg = DANGER; }
                case "livre" -> { text = "Livre"; fg = SUCCESS; }
                case "indisponivel" -> { text = "Ocupado"; fg = DANGER; }
                case "sim" -> { text = "Livre"; fg = SUCCESS; }
                case "nao" -> { text = "Ocupada"; fg = DANGER; }
                case "admin" -> { text = "Administrador"; fg = PRIMARY; }
                case "user" -> { text = "Usuario"; fg = MUTED; }
                default -> { fg = MUTED; }
            }
            l.setText(text);
            l.setForeground(fg);
            l.setFont(BASE.deriveFont(Font.BOLD, 12f));
            l.setBorder(new EmptyBorder(0, 10, 0, 10));
            if (!s) l.setBackground(r % 2 == 0 ? Color.WHITE : new Color(0xF9FAFB));
            return l;
        }
    }

    // ------------------------------------------------------------ log colorido
    static class LogPane extends JTextPane {
        LogPane() {
            setEditable(false);
            setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            setBackground(Color.WHITE);
            setBorder(new EmptyBorder(6, 8, 6, 8));
        }

        static Color colorOf(String line) {
            if (line.contains("CLIENTE -> SERVIDOR")) return new Color(0x1D4ED8);
            if (line.contains("SERVIDOR -> CLIENTE")) {
                return (line.contains("\"status\":\"4") || line.contains("\"status\":\"5")) ? DANGER : new Color(0x15803D);
            }
            return MUTED;
        }

        /** Deve ser chamado na EDT. */
        void add(String line) {
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setForeground(a, colorOf(line));
            try {
                getDocument().insertString(getDocument().getLength(), line + "\n", a);
                if (getDocument().getLength() > 200_000) getDocument().remove(0, 50_000);
            } catch (BadLocationException ignored) { }
            setCaretPosition(getDocument().getLength());
        }

        void clear() {
            setText("");
        }
    }

    /** Barra de titulo escura das janelas. */
    static JPanel header(String title, String subtitle) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(HEADER);
        p.setBorder(new EmptyBorder(12, 18, 12, 18));
        JPanel t = transparent(new GridLayout(2, 1, 0, 1));
        JLabel a = new JLabel(title);
        a.setFont(BASE.deriveFont(Font.BOLD, 18f));
        a.setForeground(Color.WHITE);
        JLabel b = new JLabel(subtitle);
        b.setFont(BASE.deriveFont(12f));
        b.setForeground(new Color(0x94A3B8));
        t.add(a);
        t.add(b);
        p.add(t, BorderLayout.WEST);
        return p;
    }
}
