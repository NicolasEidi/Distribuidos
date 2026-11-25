import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reserva de Salas - cliente TCP/JSON com interface grafica (Swing).
 * Protocolo v2.10.0: https://pedrosalvatori.github.io/protocolo-reserva-salas/
 *
 * O IP e a porta do servidor sao digitados na barra superior; o cliente pode se conectar a qualquer
 * servidor (inclusive trocar de servidor sem reiniciar). O token fica apenas em memoria.
 */
public class AuthClient extends JFrame {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_BYTES = 8192;
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private static final String[] STARTS = {"07:30", "08:20", "09:10", "10:20", "11:10", "12:00",
            "13:00", "13:50", "14:40", "15:50", "16:40", "17:30", "18:40", "19:30", "20:20", "21:20", "22:10"};
    private static final String[] ENDS = {"08:20", "09:10", "10:00", "11:10", "12:00", "12:50",
            "13:50", "14:40", "15:30", "16:40", "17:30", "18:20", "19:30", "20:20", "21:10", "22:10", "23:00"};
    private static final String[] CLASS_CODES = {"M1", "M2", "M3", "M4", "M5", "M6",
            "T1", "T2", "T3", "T4", "T5", "T6", "N1", "N2", "N3", "N4", "N5"};

    // ---------------------------------------------------------------- rede (thread unica de I/O)
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rede");
        t.setDaemon(true);
        return t;
    });
    private volatile String host;
    private volatile int port;
    private Socket socket;
    private InputStream in;
    private BufferedWriter out;
    private volatile String token;
    private volatile String role;
    private volatile boolean connected;
    private String sessionEmail = "";

    // ---------------------------------------------------------------- barra superior
    private final JTextField ipField = Ui.field(11);
    private final JTextField portField = Ui.field(5);
    private final Ui.Btn connectBtn = Ui.button("Conectar", Ui.Kind.PRIMARY);
    private final Ui.Dot connDot = new Ui.Dot();
    private final JLabel connLabel = new JLabel("Desconectado");

    // ---------------------------------------------------------------- rodape
    private final JLabel statusBar = new JLabel("Pronto.");
    private final Ui.LogPane logPane = new Ui.LogPane();
    private final JScrollPane logScroll = Ui.scroll(logPane);
    private final Ui.Btn logToggle = Ui.button("Ocultar mensagens", Ui.Kind.GHOST);

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final CardLayout pages = new CardLayout();
    private final JPanel pagePanel = new JPanel(pages);

    // ---------------------------------------------------------------- login / cadastro
    private final CardLayout authCards = new CardLayout();
    private final JPanel authBody = new JPanel(authCards);
    private final JLabel banner = new JLabel(" ");
    private final JTextField loginEmail = Ui.field(24);
    private final JPasswordField loginPass = Ui.password(24);
    private final JTextField regEmail = Ui.field(24);
    private final JTextField regUser = Ui.field(24);
    private final JPasswordField regPass = Ui.password(24);
    private final Ui.Btn loginBtn = Ui.button("Entrar", Ui.Kind.PRIMARY);
    private final Ui.Btn regBtn = Ui.button("Criar conta", Ui.Kind.PRIMARY);

    // ---------------------------------------------------------------- barra lateral
    private final JLabel sideEmail = new JLabel(" ");
    private final Ui.Chip sideRole = new Ui.Chip("user", new Color(0x1E3A8A), Color.WHITE);
    private final Ui.NavButton navRooms = new Ui.NavButton("Salas e horarios");
    private final Ui.NavButton navRes = new Ui.NavButton("Minhas reservas");
    private final Ui.NavButton navAcct = new Ui.NavButton("Minha conta");
    private final Ui.NavButton navAdmin = new Ui.NavButton("Administracao");

    // ---------------------------------------------------------------- salas
    private final DefaultTableModel roomModel = model("ID", "Nome", "Capacidade", "Local", "Recursos",
            "Status", "Livre no horario?", "Horarios de funcionamento");
    private final JTable roomTable = new JTable(roomModel);
    private final Ui.DateField fDate = new Ui.DateField("");
    private final JComboBox<String> fStart = timeCombo(STARTS, false, true);
    private final JComboBox<String> fEnd = timeCombo(ENDS, true, true);
    private final JTextField fMinCap = Ui.field(5);
    private final Ui.DateField agendaDate = new Ui.DateField(LocalDate.now().plusDays(1).toString());
    private final JCheckBox onlyFree = new JCheckBox("Mostrar so horarios livres", true);
    private final DefaultTableModel agendaModel = model("Horario", "Inicio", "Fim", "Situacao", "Reservado por", "ID da reserva");
    private final JTable agendaTable = new JTable(agendaModel);
    private final JLabel agendaTitle = Ui.heading("Horarios da sala");
    private final List<JsonObject> roomCache = new ArrayList<>();
    private final List<Component> adminRoomButtons = new ArrayList<>();

    // ---------------------------------------------------------------- reservas
    private final DefaultComboBoxModel<RoomItem> roomCombo = new DefaultComboBoxModel<>();
    private final JComboBox<RoomItem> rRoom = Ui.combo(new JComboBox<>(roomCombo));
    private final Ui.DateField rDate = new Ui.DateField(LocalDate.now().plusDays(1).toString());
    private final JComboBox<String> rStart = timeCombo(STARTS, false, false);
    private final JComboBox<String> rEnd = timeCombo(ENDS, true, false);
    private final JTextField rTopic = Ui.field(18);
    private final JTextField rPart = Ui.field(6);
    private final JLabel formTitle = Ui.heading("Nova reserva");
    private final JLabel formHint = Ui.muted("Preencha e clique em \"Criar reserva\".");
    private final Ui.Btn createBtn = Ui.button("Criar reserva", Ui.Kind.PRIMARY);
    private final Ui.Btn saveBtn = Ui.button("Salvar alteracoes", Ui.Kind.SUCCESS);
    private final Ui.Btn cancelResBtn = Ui.button("Cancelar esta reserva", Ui.Kind.DANGER);
    private final Ui.Btn clearBtn = Ui.button("Nova reserva (limpar)", Ui.Kind.GHOST);
    private final Ui.DateField rListDate = new Ui.DateField("");
    private final JComboBox<String> rScope = Ui.combo(new JComboBox<>(new String[]{"mine", "all"}));
    private JPanel rScopeBox;
    private final JTextField rById = Ui.field(6);
    private final DefaultTableModel resModel = model("ID", "Sala (ID)", "Sala", "Usuario", "Data",
            "Inicio", "Fim", "Assunto", "Pessoas");
    private final JTable resTable = new JTable(resModel);
    private String editingId;

    // ---------------------------------------------------------------- conta
    private final JLabel accUser = new JLabel("-");
    private final JLabel accEmail = new JLabel("-");
    private final Ui.Chip accRole = new Ui.Chip("-", new Color(0xE5E7EB), Ui.TEXT);
    private final JLabel accCreated = new JLabel("-");
    private final JTextField newUser = Ui.field(18);
    private final JPasswordField newPass = Ui.password(18);

    // ---------------------------------------------------------------- admin
    private final DefaultTableModel userModel = model("Usuario", "E-mail", "Perfil");
    private final JTable userTable = new JTable(userModel);

    public static void main(String[] args) {
        Ui.install();
        SwingUtilities.invokeLater(() -> new AuthClient().setVisible(true));
    }

    private AuthClient() {
        super("Reserva de Salas - Cliente");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1280, 840);
        setMinimumSize(new Dimension(1020, 660));
        setLocationRelativeTo(null);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        getContentPane().setBackground(Ui.BG);
        setLayout(new BorderLayout());

        ipField.setText("127.0.0.1");
        portField.setText("5000");

        add(buildTopBar(), BorderLayout.NORTH);

        root.setBackground(Ui.BG);
        root.add(buildAuthPanel(), "auth");
        root.add(buildMainPanel(), "main");
        cards.show(root, "auth");
        add(root, BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        connectBtn.addActionListener(e -> connectAction());
        portField.addActionListener(e -> connectAction());
        ipField.addActionListener(e -> connectAction());
        applyConnectedState();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { shutdown(); }
        });
    }

    // ================================================================== MONTAGEM DAS TELAS
    private JComponent buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Ui.HEADER);
        bar.setBorder(new EmptyBorder(10, 18, 10, 18));

        JPanel t = Ui.transparent(new GridLayout(2, 1, 0, 1));
        JLabel a = new JLabel("Reserva de Salas");
        a.setFont(Ui.BASE.deriveFont(Font.BOLD, 18f));
        a.setForeground(Color.WHITE);
        JLabel b = new JLabel("Cliente  -  protocolo v2.10");
        b.setFont(Ui.BASE.deriveFont(12f));
        b.setForeground(new Color(0x94A3B8));
        t.add(a);
        t.add(b);
        bar.add(t, BorderLayout.WEST);

        JPanel conn = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        JLabel l1 = new JLabel("Servidor:");
        l1.setForeground(new Color(0xCBD5E1));
        JLabel l2 = new JLabel(":");
        l2.setForeground(new Color(0xCBD5E1));
        ipField.setToolTipText("IP do servidor");
        portField.setToolTipText("Porta do servidor");
        connLabel.setForeground(new Color(0xE2E8F0));
        connLabel.setFont(Ui.BASE.deriveFont(Font.BOLD, 12f));
        conn.add(l1);
        conn.add(ipField);
        conn.add(l2);
        conn.add(portField);
        conn.add(connectBtn);
        conn.add(Box.createHorizontalStrut(8));
        conn.add(connDot);
        conn.add(connLabel);
        bar.add(conn, BorderLayout.EAST);
        return bar;
    }

    private JComponent buildBottom() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Ui.CARD);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.BORDER));

        statusBar.setFont(Ui.BASE.deriveFont(Font.BOLD, 12f));
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(4, 14, 4, 10));
        bar.add(statusBar, BorderLayout.WEST);

        Ui.Btn clear = Ui.button("Limpar", Ui.Kind.GHOST);
        clear.addActionListener(e -> logPane.clear());
        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(Ui.muted("Mensagens JSON enviadas e recebidas"));
        right.add(clear);
        right.add(logToggle);
        bar.add(right, BorderLayout.EAST);
        p.add(bar, BorderLayout.NORTH);

        logScroll.setPreferredSize(new Dimension(100, 120));
        p.add(logScroll, BorderLayout.CENTER);
        logToggle.addActionListener(e -> {
            boolean show = !logScroll.isVisible();
            logScroll.setVisible(show);
            logToggle.setText(show ? "Ocultar mensagens" : "Mostrar mensagens");
            p.revalidate();
        });
        return p;
    }

    // ---- login / cadastro
    private JComponent buildAuthPanel() {
        loginBtn.addActionListener(e -> doLogin());
        loginPass.addActionListener(e -> doLogin());
        regBtn.addActionListener(e -> doRegister());
        regPass.addActionListener(e -> doRegister());

        JPanel loginForm = Ui.vstack(12, Ui.labeled("E-mail", loginEmail), Ui.labeled("Senha", loginPass),
                fullWidth(loginBtn));
        JPanel regForm = Ui.vstack(12, Ui.labeled("E-mail", regEmail),
                Ui.labeled("Nome de usuario (somente letras minusculas)", regUser),
                Ui.labeled("Senha (letras e numeros, ate 20)", regPass), fullWidth(regBtn));
        authBody.setOpaque(false);
        authBody.add(loginForm, "login");
        authBody.add(regForm, "register");

        Ui.Btn tabLogin = Ui.button("Entrar", Ui.Kind.SECONDARY);
        Ui.Btn tabReg = Ui.button("Criar conta", Ui.Kind.GHOST);
        tabLogin.addActionListener(e -> authCards.show(authBody, "login"));
        tabReg.addActionListener(e -> authCards.show(authBody, "register"));
        JPanel tabs = Ui.transparent(new GridLayout(1, 2, 8, 0));
        tabs.add(tabLogin);
        tabs.add(tabReg);

        banner.setFont(Ui.BASE.deriveFont(Font.BOLD, 12f));

        JLabel hello = Ui.title("Bem-vindo");
        JPanel card = Ui.card(new BorderLayout(0, 14));
        card.add(Ui.vstack(4, hello, Ui.muted("Entre na sua conta para consultar salas e fazer reservas.")),
                BorderLayout.NORTH);
        JPanel mid = Ui.vstack(14, banner, tabs, authBody);
        card.add(mid, BorderLayout.CENTER);
        card.setPreferredSize(new Dimension(430, 410));

        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setBackground(Ui.BG);
        wrap.add(card);
        return wrap;
    }

    private static JPanel fullWidth(Component c) {
        JPanel p = Ui.transparent(new BorderLayout());
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    // ---- area principal (menu lateral + paginas)
    private JComponent buildMainPanel() {
        JPanel side = new JPanel();
        side.setBackground(Ui.SIDEBAR);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(new EmptyBorder(16, 12, 16, 12));
        side.setPreferredSize(new Dimension(236, 100));

        JLabel who = new JLabel("Logado como");
        who.setForeground(new Color(0x94A3B8));
        who.setFont(Ui.BASE.deriveFont(11f));
        sideEmail.setForeground(Color.WHITE);
        sideEmail.setFont(Ui.BASE.deriveFont(Font.BOLD, 13f));
        JPanel roleRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        roleRow.add(sideRole);
        JPanel userBox = Ui.vstack(4, who, sideEmail, roleRow);
        userBox.setBorder(new EmptyBorder(4, 8, 16, 8));
        userBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, userBox.getPreferredSize().height));
        side.add(userBox);

        ButtonGroup g = new ButtonGroup();
        for (Ui.NavButton b : new Ui.NavButton[]{navRooms, navRes, navAcct, navAdmin}) {
            g.add(b);
            side.add(b);
            side.add(Box.createVerticalStrut(4));
        }
        navRooms.addActionListener(e -> showPage("rooms"));
        navRes.addActionListener(e -> showPage("res"));
        navAcct.addActionListener(e -> showPage("acct"));
        navAdmin.addActionListener(e -> showPage("admin"));
        side.add(Box.createVerticalGlue());

        Ui.Btn logout = Ui.button("Sair da conta", Ui.Kind.SECONDARY);
        logout.addActionListener(e -> logoutAction());
        logout.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        side.add(logout);

        pagePanel.setOpaque(false);
        pagePanel.add(Ui.page((JComponent) buildRoomsPage()), "rooms");
        pagePanel.add(Ui.page((JComponent) buildReservationsPage()), "res");
        pagePanel.add(Ui.page((JComponent) buildAccountPage()), "acct");
        pagePanel.add(Ui.page((JComponent) buildAdminPage()), "admin");

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Ui.BG);
        content.setBorder(new EmptyBorder(16, 20, 12, 20));
        content.add(pagePanel, BorderLayout.CENTER);

        JPanel main = new JPanel(new BorderLayout());
        main.add(side, BorderLayout.WEST);
        main.add(content, BorderLayout.CENTER);
        return main;
    }

    private void showPage(String name) {
        pages.show(pagePanel, name);
        switch (name) {
            case "rooms" -> navRooms.setSelected(true);
            case "res" -> {
                navRes.setSelected(true);
                listRooms(false);
                listReservations();
            }
            case "acct" -> {
                navAcct.setSelected(true);
                readUser();
            }
            case "admin" -> {
                navAdmin.setSelected(true);
                adminList();
            }
            default -> { }
        }
    }

    // ---- pagina: salas
    private JComponent buildRoomsPage() {
        // filtros
        Ui.Btn search = Ui.button("Buscar salas", Ui.Kind.PRIMARY);
        Ui.Btn clearF = Ui.button("Limpar filtros", Ui.Kind.GHOST);
        search.addActionListener(e -> listRooms(true));
        clearF.addActionListener(e -> {
            fDate.setText("");
            fStart.setSelectedIndex(0);
            fEnd.setSelectedIndex(0);
            fMinCap.setText("");
            listRooms(true);
        });
        JPanel filterRow = Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT, 12, 4));
        filterRow.add(Ui.labeled("Data (opcional)", fDate));
        filterRow.add(Ui.labeled("Inicio", fStart));
        filterRow.add(Ui.labeled("Fim", fEnd));
        filterRow.add(Ui.labeled("Capacidade minima", fMinCap));
        JPanel btns = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.setBorder(new EmptyBorder(19, 0, 0, 0));
        btns.add(search);
        btns.add(clearF);
        filterRow.add(btns);
        JPanel filterCard = Ui.card(new BorderLayout(0, 6));
        filterCard.add(filterRow, BorderLayout.CENTER);

        // tabela de salas
        Ui.table(roomTable, 5, 6);
        roomTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && roomTable.getSelectedRow() >= 0) autoAgenda();
        });
        Ui.Btn newRoom = Ui.button("+ Nova sala", Ui.Kind.SUCCESS);
        Ui.Btn editRoom = Ui.button("Editar", Ui.Kind.SECONDARY);
        Ui.Btn delRoom = Ui.button("Remover", Ui.Kind.DANGER);
        newRoom.addActionListener(e -> roomDialog(null));
        editRoom.addActionListener(e -> editSelectedRoom());
        delRoom.addActionListener(e -> deleteSelectedRoom());
        adminRoomButtons.add(newRoom);
        adminRoomButtons.add(editRoom);
        adminRoomButtons.add(delRoom);
        JPanel roomHead = Ui.transparent(new BorderLayout());
        roomHead.add(Ui.heading("Salas cadastradas"), BorderLayout.WEST);
        JPanel roomBtns = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        roomBtns.add(newRoom);
        roomBtns.add(editRoom);
        roomBtns.add(delRoom);
        roomHead.add(roomBtns, BorderLayout.EAST);
        JPanel roomCard = Ui.card(new BorderLayout(0, 10));
        roomCard.add(roomHead, BorderLayout.NORTH);
        roomCard.add(Ui.scroll(roomTable, 170), BorderLayout.CENTER);

        // agenda
        Ui.table(agendaTable, 3);
        Ui.Btn seeAgenda = Ui.button("Ver horarios", Ui.Kind.PRIMARY);
        Ui.Btn reserve = Ui.button("Reservar esta sala", Ui.Kind.SUCCESS);
        seeAgenda.addActionListener(e -> showAgenda(false));
        reserve.addActionListener(e -> reserveFromAgenda());
        onlyFree.setOpaque(false);
        onlyFree.setFont(Ui.BASE);
        onlyFree.addActionListener(e -> { if (roomTable.getSelectedRow() >= 0) autoAgenda(); });
        JPanel agendaCtl = Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT, 10, 4));
        agendaCtl.add(Ui.labeled("Dia", agendaDate));
        JPanel chkWrap = Ui.transparent(new BorderLayout());
        chkWrap.setBorder(new EmptyBorder(19, 0, 0, 0));
        chkWrap.add(onlyFree, BorderLayout.CENTER);
        agendaCtl.add(chkWrap);
        JPanel b2 = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        b2.setBorder(new EmptyBorder(19, 0, 0, 0));
        b2.add(seeAgenda);
        b2.add(reserve);
        agendaCtl.add(b2);
        JPanel agendaHead = Ui.transparent(new BorderLayout(0, 4));
        agendaHead.add(agendaTitle, BorderLayout.NORTH);
        agendaHead.add(agendaCtl, BorderLayout.CENTER);
        agendaTitle.setText("Horarios (escolha uma sala)");
        JPanel agendaCard = Ui.card(new BorderLayout(0, 10));
        agendaCard.add(agendaHead, BorderLayout.NORTH);
        agendaCard.add(Ui.scroll(agendaTable, 170), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, roomCard, agendaCard);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        split.setOpaque(false);
        split.setDividerSize(10);

        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setOpaque(false);
        JPanel top = Ui.transparent(new BorderLayout(0, 0));
        top.add(Ui.pageHeader("Salas e horarios",
                        "Para ver quais salas estao livres em um horario, preencha data, inicio e fim. Clique numa sala para ver os horarios do dia."),
                BorderLayout.NORTH);
        top.add(filterCard, BorderLayout.CENTER);
        p.add(top, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    // ---- pagina: reservas
    private JComponent buildReservationsPage() {
        Ui.combo(rRoom);
        rRoom.setPreferredSize(new Dimension(300, 34));

        JPanel times = Ui.transparent(new GridLayout(1, 2, 8, 0));
        times.add(Ui.labeled("Inicio", rStart));
        times.add(Ui.labeled("Fim", rEnd));

        Ui.Btn check = Ui.button("Verificar disponibilidade", Ui.Kind.SECONDARY);
        check.addActionListener(e -> checkAvailability());
        createBtn.addActionListener(e -> createReservation());
        saveBtn.addActionListener(e -> updateReservation());
        cancelResBtn.addActionListener(e -> deleteReservation());
        clearBtn.addActionListener(e -> {
            resTable.clearSelection();
            rTopic.setText("");
            rPart.setText("");
        });

        JPanel editRow = Ui.transparent(new GridLayout(1, 2, 8, 0));
        editRow.add(saveBtn);
        editRow.add(cancelResBtn);
        JPanel topicRow = Ui.transparent(new GridLayout(1, 2, 8, 0));
        topicRow.add(Ui.labeled("Assunto", rTopic));
        topicRow.add(Ui.labeled("Participantes", rPart));
        JPanel form = Ui.vstack(8,
                formTitle, formHint,
                Ui.labeled("Sala", rRoom),
                Ui.labeled("Dia", rDate),
                times,
                topicRow,
                Box.createVerticalStrut(2),
                fullWidth(check), fullWidth(createBtn), editRow, fullWidth(clearBtn));
        JPanel formCard = Ui.card(new BorderLayout());
        formCard.add(form, BorderLayout.NORTH);
        formCard.setPreferredSize(new Dimension(360, formCard.getPreferredSize().height));
        JPanel formScroll = Ui.transparent(new BorderLayout());
        formScroll.add(formCard, BorderLayout.NORTH);

        // lista
        Ui.table(resTable, new int[0]);
        // a coluna "Sala (ID)" continua no modelo (o formulario usa), mas fica oculta na tabela
        resTable.removeColumn(resTable.getColumnModel().getColumn(1));
        int[] widths = {36, 110, 70, 90, 52, 52, 150, 50};
        for (int i = 0; i < widths.length; i++) resTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        resTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) fillFormFromSelectedReservation();
        });
        Ui.Btn search = Ui.button("Buscar", Ui.Kind.PRIMARY);
        search.addActionListener(e -> listReservations());
        Ui.Btn open = Ui.button("Abrir", Ui.Kind.SECONDARY);
        open.addActionListener(e -> readReservationById());
        rScope.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, "all".equals(v) ? "Todas as reservas" : "Somente as minhas", i, s, f);
            }
        });
        rScopeBox = Ui.labeled("Mostrar", rScope);
        JPanel bar = Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT, 10, 4));
        bar.add(Ui.labeled("Filtrar por dia (opcional)", rListDate));
        bar.add(rScopeBox);
        JPanel sb = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sb.setBorder(new EmptyBorder(19, 0, 0, 0));
        sb.add(search);
        bar.add(sb);
        bar.add(Box.createHorizontalStrut(14));
        bar.add(Ui.labeled("Abrir reserva por ID", rById));
        JPanel ob = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ob.setBorder(new EmptyBorder(19, 0, 0, 0));
        ob.add(open);
        bar.add(ob);

        JPanel listCard = Ui.card(new BorderLayout(0, 10));
        listCard.add(bar, BorderLayout.NORTH);
        listCard.add(Ui.scroll(resTable, 260), BorderLayout.CENTER);
        listCard.add(Ui.muted("Clique em uma reserva para editar (sala, dia, horario, assunto, participantes) ou cancelar."),
                BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout(14, 0));
        p.setOpaque(false);
        JPanel center = Ui.transparent(new BorderLayout());
        center.add(Ui.pageHeader("Reservas", "Crie uma reserva ou clique em uma da lista para alterar ou cancelar."),
                BorderLayout.NORTH);
        center.add(listCard, BorderLayout.CENTER);
        p.add(formScroll, BorderLayout.WEST);
        p.add(center, BorderLayout.CENTER);
        updateEditState();
        return p;
    }

    // ---- pagina: conta
    private JComponent buildAccountPage() {
        for (JLabel l : new JLabel[]{accUser, accEmail, accCreated}) l.setFont(Ui.BASE.deriveFont(Font.BOLD, 13f));

        JPanel info = new JPanel(new GridLayout(4, 2, 8, 8));
        info.setOpaque(false);
        info.add(Ui.muted("Usuario"));
        info.add(accUser);
        info.add(Ui.muted("E-mail"));
        info.add(accEmail);
        info.add(Ui.muted("Perfil"));
        JPanel rr = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rr.add(accRole);
        info.add(rr);
        info.add(Ui.muted("Conta criada em"));
        info.add(accCreated);
        JPanel c1 = Ui.card(new BorderLayout(0, 10));
        c1.add(Ui.heading("Meus dados"), BorderLayout.NORTH);
        c1.add(info, BorderLayout.CENTER);

        Ui.Btn upd = Ui.button("Salvar alteracoes", Ui.Kind.PRIMARY);
        upd.addActionListener(e -> updateUser());
        JPanel c2 = Ui.card(new BorderLayout(0, 10));
        c2.add(Ui.heading("Alterar usuario ou senha"), BorderLayout.NORTH);
        JPanel f2 = Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT, 12, 4));
        f2.add(Ui.labeled("Novo usuario (vazio = manter)", newUser));
        f2.add(Ui.labeled("Nova senha (vazio = manter)", newPass));
        JPanel ub = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ub.setBorder(new EmptyBorder(19, 0, 0, 0));
        ub.add(upd);
        f2.add(ub);
        c2.add(f2, BorderLayout.CENTER);

        Ui.Btn del = Ui.button("Remover minha conta", Ui.Kind.DANGER);
        del.addActionListener(e -> deleteUser());
        JPanel c3 = Ui.card(new BorderLayout(0, 10));
        c3.add(Ui.heading("Zona de perigo"), BorderLayout.NORTH);
        JPanel f3 = Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT, 12, 4));
        f3.add(del);
        f3.add(Ui.muted("Apaga sua conta e todas as suas reservas. Nao pode ser desfeito."));
        c3.add(f3, BorderLayout.CENTER);

        JPanel stack = Ui.vstack(12, c1, c2, c3);
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(Ui.pageHeader("Minha conta", "Veja e altere os dados do seu cadastro."), BorderLayout.NORTH);
        JPanel wrap = Ui.transparent(new BorderLayout());
        wrap.add(stack, BorderLayout.NORTH);
        p.add(wrap, BorderLayout.CENTER);
        return p;
    }

    // ---- pagina: administracao
    private JComponent buildAdminPage() {
        Ui.table(userTable, 2);
        Ui.Btn list = Ui.button("Atualizar lista", Ui.Kind.SECONDARY);
        Ui.Btn read = Ui.button("Ver detalhes", Ui.Kind.SECONDARY);
        Ui.Btn upd = Ui.button("Editar", Ui.Kind.PRIMARY);
        Ui.Btn del = Ui.button("Remover", Ui.Kind.DANGER);
        list.addActionListener(e -> adminList());
        read.addActionListener(e -> adminRead());
        upd.addActionListener(e -> adminUpdate());
        del.addActionListener(e -> adminDelete());

        JPanel card = Ui.card(new BorderLayout(0, 10));
        JPanel bar = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.add(list);
        bar.add(read);
        bar.add(upd);
        bar.add(del);
        card.add(bar, BorderLayout.NORTH);
        card.add(Ui.scroll(userTable, 260), BorderLayout.CENTER);
        card.add(Ui.muted("Selecione um usuario na tabela para ver, editar (usuario, senha, perfil) ou remover."),
                BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(Ui.pageHeader("Administracao", "Gerencie os usuarios do sistema (somente administradores)."),
                BorderLayout.NORTH);
        p.add(card, BorderLayout.CENTER);
        return p;
    }

    // ================================================================== ESTADO DA INTERFACE
    private void applyConnectedState() {
        connDot.set(connected ? Ui.SUCCESS : Ui.DANGER);
        connLabel.setText(connected ? "Conectado a " + host + ":" + port : "Desconectado");
        loginBtn.setEnabled(connected);
        regBtn.setEnabled(connected);
        if (connected) {
            banner.setForeground(Ui.SUCCESS);
            banner.setText("Servidor conectado. Entre ou crie sua conta.");
        } else {
            banner.setForeground(Ui.WARN);
            banner.setText("Primeiro conecte-se a um servidor (IP e porta no topo da janela).");
        }
    }

    private void updateEditState() {
        boolean editing = editingId != null;
        formTitle.setText(editing ? "Editando reserva #" + editingId : "Nova reserva");
        formHint.setText(editing ? "Altere os campos e clique em \"Salvar alteracoes\"."
                : "Preencha e clique em \"Criar reserva\".");
        saveBtn.setEnabled(editing);
        cancelResBtn.setEnabled(editing);
        clearBtn.setVisible(editing);
    }

    private void setStatus(String text, Color color) {
        statusBar.setForeground(color);
        statusBar.setText("●  " + text);
    }

    // ================================================================== AUTENTICACAO
    private void doRegister() {
        JsonObject r = req("register");
        r.addProperty("email", regEmail.getText().trim().toLowerCase());
        r.addProperty("user", regUser.getText().trim().toLowerCase());
        r.addProperty("password", new String(regPass.getPassword()));
        call(r, rep -> {
            loginEmail.setText(regEmail.getText().trim().toLowerCase());
            regPass.setText("");
            authCards.show(authBody, "login");
            loginPass.requestFocusInWindow();
            info("Conta criada! Agora entre com seu e-mail e senha.");
        });
    }

    private void doLogin() {
        JsonObject r = req("login");
        sessionEmail = loginEmail.getText().trim().toLowerCase();
        r.addProperty("email", sessionEmail);
        r.addProperty("password", new String(loginPass.getPassword()));
        call(r, rep -> {
            token = str(rep, "token");
            role = str(rep, "role");
            loginPass.setText("");
            enterSession();
        });
    }

    private void enterSession() {
        boolean admin = "admin".equals(role);
        sideEmail.setText(sessionEmail);
        sideRole.setText(admin ? "Administrador" : "Usuario");
        sideRole.setColors(admin ? Ui.PRIMARY : new Color(0x334155), Color.WHITE);
        for (Component b : adminRoomButtons) b.setVisible(admin);
        navAdmin.setVisible(admin);
        rScopeBox.setVisible(admin);
        cards.show(root, "main");
        showPage("rooms");
        listRooms(false);
        info("Login realizado com sucesso.");
    }

    private void leaveSession() {
        token = null;
        role = null;
        editingId = null;
        resTable.clearSelection();
        roomModel.setRowCount(0);
        resModel.setRowCount(0);
        agendaModel.setRowCount(0);
        cards.show(root, "auth");
    }

    private void logoutAction() {
        if (token == null) return;
        call(authReq("logout"), rep -> { });
    }

    // ================================================================== SALAS
    private void listRooms(boolean fromButton) {
        JsonObject r = authReq("list_rooms");
        if (fromButton) {
            optional(r, "date", fDate.getText());
            optional(r, "start_time", (String) fStart.getSelectedItem());
            optional(r, "end_time", (String) fEnd.getSelectedItem());
            optional(r, "min_capacity", fMinCap.getText());
        }
        call(r, rep -> {
            roomModel.setRowCount(0);
            roomCache.clear();
            RoomItem selected = (RoomItem) rRoom.getSelectedItem();
            roomCombo.removeAllElements();
            JsonArray arr = array(rep, "rooms");
            if (arr == null) return;
            for (JsonElement e : arr) {
                JsonObject room = e.getAsJsonObject();
                roomCache.add(room);
                roomModel.addRow(new Object[]{str(room, "room_id"), str(room, "name"), str(room, "capacity"),
                        str(room, "location"), join(array(room, "resources")), str(room, "room_status"),
                        room.has("available") ? ("true".equals(str(room, "available")) ? "sim" : "nao") : "-",
                        join(array(room, "slots"))});
                roomCombo.addElement(new RoomItem(str(room, "room_id"), str(room, "name"), str(room, "capacity")));
            }
            if (selected != null) {
                for (int i = 0; i < roomCombo.getSize(); i++) {
                    if (roomCombo.getElementAt(i).id.equals(selected.id)) rRoom.setSelectedIndex(i);
                }
            }
            int n = arr.size();
            info(n == 0 ? "Nenhuma sala encontrada." : n + (n == 1 ? " sala encontrada." : " salas encontradas."));
        });
    }

    private String selectedRoomId() {
        int row = roomTable.getSelectedRow();
        if (row < 0) {
            warn("Selecione uma sala na tabela.");
            return null;
        }
        return (String) roomModel.getValueAt(roomTable.convertRowIndexToModel(row), 0);
    }

    private String selectedRoomName() {
        int row = roomTable.getSelectedRow();
        return row < 0 ? "" : (String) roomModel.getValueAt(roomTable.convertRowIndexToModel(row), 1);
    }

    /** Carrega a agenda automaticamente ao escolher uma sala (se o dia for valido). */
    private void autoAgenda() {
        try {
            LocalDate.parse(agendaDate.getText().trim());
        } catch (DateTimeParseException e) {
            return;
        }
        showAgenda(true);
    }

    private void showAgenda(boolean auto) {
        String id = selectedRoomId();
        if (id == null) return;
        JsonObject r = authReq("read_room");
        r.addProperty("room_id", id);
        r.addProperty("date", agendaDate.getText().trim());
        String name = selectedRoomName();
        call(r, rep -> {
            agendaModel.setRowCount(0);
            JsonArray arr = array(rep, "agenda");
            boolean today = agendaDate.getText().trim().equals(LocalDate.now(ZONE).toString());
            LocalTime now = LocalTime.now(ZONE);
            int free = 0, total = 0;
            if (arr != null) {
                for (JsonElement e : arr) {
                    JsonObject h = e.getAsJsonObject();
                    boolean isFree = "livre".equals(str(h, "slot_status"));
                    total++;
                    boolean started = today && !LocalTime.parse(str(h, "start_time")).isAfter(now);
                    if (isFree && !started) free++;
                    if (onlyFree.isSelected() && (!isFree || started)) continue; // 9.15: esconde aulas que ja comecaram
                    agendaModel.addRow(new Object[]{str(h, "slot"), str(h, "start_time"), str(h, "end_time"),
                            str(h, "slot_status"), nz(str(h, "user")), nz(str(h, "reservation_id"))});
                }
            }
            agendaTitle.setText("Horarios: " + name);
            if (total == 0) info("A sala " + name + " nao funciona nesse dia.");
            else info(free + " horario(s) livre(s) de " + total + " em " + name + ".");
        }, !auto);
    }

    /** Leva a sala e o horario escolhidos na agenda para o formulario de reserva. */
    private void reserveFromAgenda() {
        String id = selectedRoomId();
        if (id == null) return;
        for (int i = 0; i < roomCombo.getSize(); i++) {
            if (roomCombo.getElementAt(i).id.equals(id)) rRoom.setSelectedIndex(i);
        }
        rDate.setText(agendaDate.getText().trim());
        int row = agendaTable.getSelectedRow();
        if (row >= 0) {
            int m = agendaTable.convertRowIndexToModel(row);
            rStart.setSelectedItem(agendaModel.getValueAt(m, 1));
            rEnd.setSelectedItem(agendaModel.getValueAt(m, 2));
        }
        resTable.clearSelection();
        navRes.setSelected(true);
        pages.show(pagePanel, "res");
        listReservations();
        info("Complete o assunto e o numero de participantes e clique em \"Criar reserva\".");
    }

    private void editSelectedRoom() {
        String id = selectedRoomId();
        if (id == null) return;
        for (JsonObject room : roomCache) {
            if (id.equals(str(room, "room_id"))) {
                roomDialog(room);
                return;
            }
        }
    }

    private void deleteSelectedRoom() {
        String id = selectedRoomId();
        if (id == null) return;
        if (JOptionPane.showConfirmDialog(this, "Remover a sala " + selectedRoomName() + "?\nSo e possivel se ela nao tiver reservas ativas.",
                "Confirmar", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        JsonObject r = authReq("delete_room");
        r.addProperty("room_id", id);
        call(r, rep -> {
            info("Sala removida.");
            listRooms(false);
        });
    }

    /** existing == null: criar sala; senao editar. */
    private void roomDialog(JsonObject existing) {
        boolean edit = existing != null;
        JTextField name = Ui.field(20);
        JTextField cap = Ui.field(6);
        JTextField loc = Ui.field(30);
        JTextField res = Ui.field(30);
        JCheckBox clearRes = new JCheckBox("Apagar todos os recursos");
        JComboBox<String> status = Ui.combo(new JComboBox<>(new String[]{"", "active", "inactive"}));
        status.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                String t = "active".equals(v) ? "Ativa (aceita reservas)" : "inactive".equals(v) ? "Inativa (manutencao)" : "(manter)";
                return super.getListCellRendererComponent(l, t, i, s, f);
            }
        });
        List<String> originalSlots = new ArrayList<>();
        SlotPicker picker = new SlotPicker();
        if (edit) {
            name.setText(str(existing, "name"));
            cap.setText(str(existing, "capacity"));
            loc.setText(str(existing, "location"));
            res.setText(join(array(existing, "resources")));
            JsonArray s = array(existing, "slots");
            List<String> cur = new ArrayList<>();
            if (s != null) for (JsonElement e : s) cur.add(e.getAsString());
            originalSlots.addAll(cur);
            picker.set(cur);
            status.setSelectedItem(str(existing, "room_status"));
        }

        JPanel row1 = Ui.transparent(new GridLayout(1, 2, 10, 0));
        row1.add(Ui.labeled("Nome da sala", name));
        row1.add(Ui.labeled("Capacidade", cap));
        JPanel fields = Ui.vstack(8, row1, Ui.labeled("Localizacao", loc),
                Ui.labeled("Recursos (separados por virgula)", res));
        if (edit) {
            clearRes.setOpaque(false);
            fields = Ui.vstack(8, row1, Ui.labeled("Localizacao", loc),
                    Ui.labeled("Recursos (separados por virgula)", res), clearRes,
                    Ui.labeled("Situacao da sala", status));
        }

        JPanel slotPanel = Ui.card(new BorderLayout(0, 6));
        slotPanel.add(Ui.heading("Horarios de funcionamento"), BorderLayout.NORTH);
        JPanel inner = Ui.transparent(new BorderLayout(0, 6));
        inner.add(picker, BorderLayout.CENTER);
        inner.add(Ui.muted(edit ? "Marque ou desmarque as aulas e clique em OK: os horarios sao atualizados. Clique no nome do dia para marcar todas."
                        : "Marque as aulas em que a sala funciona. Clique no nome do dia para marcar todas."),
                BorderLayout.SOUTH);
        slotPanel.add(inner, BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.add(fields, BorderLayout.NORTH);
        p.add(slotPanel, BorderLayout.CENTER);

        if (JOptionPane.showConfirmDialog(this, p, edit ? "Editar sala " + str(existing, "name") : "Nova sala",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;

        if (!edit) {
            JsonObject r = authReq("create_room");
            r.addProperty("name", name.getText().trim());
            r.addProperty("capacity", cap.getText().trim());
            r.addProperty("location", loc.getText().trim());
            r.add("resources", csv(res.getText()));
            r.add("slots", toArray(picker.get()));
            call(r, rep -> {
                info("Sala criada (ID " + str(rep, "room_id") + ").");
                listRooms(false);
            });
        } else {
            JsonObject r = authReq("update_room");
            r.addProperty("room_id", str(existing, "room_id"));
            r.addProperty("name", diff(name.getText(), str(existing, "name")));
            r.addProperty("capacity", diff(cap.getText(), str(existing, "capacity")));
            r.addProperty("location", diff(loc.getText(), str(existing, "location")));
            r.addProperty("room_status", diff((String) status.getSelectedItem(), str(existing, "room_status")));
            if (clearRes.isSelected()) {
                JsonArray z = new JsonArray();
                z.add("0");
                r.add("resources", z);
            } else if (!res.getText().trim().equals(join(array(existing, "resources")))) {
                r.add("resources", csv(res.getText()));
            }
            // horarios: envia a lista completa nova somente se mudou (vazia = "nao alterar" no protocolo)
            Set<String> chosen = new HashSet<>(picker.get());
            boolean slotsChanged = !chosen.equals(new HashSet<>(originalSlots));
            if (slotsChanged && chosen.isEmpty()) {
                warn("A sala precisa ter pelo menos um horario de funcionamento.");
                return;
            }
            if (slotsChanged) r.add("slots", toArray(picker.get()));

            boolean anything = slotsChanged || r.has("resources")
                    || !r.get("name").getAsString().isEmpty() || !r.get("capacity").getAsString().isEmpty()
                    || !r.get("location").getAsString().isEmpty() || !r.get("room_status").getAsString().isEmpty();
            if (!anything) {
                info("Nenhuma alteracao foi feita.");
                return;
            }
            call(r, rep -> {
                info("Sala atualizada.");
                listRooms(false);
            });
        }
    }

    // ================================================================== RESERVAS
    private void checkAvailability() {
        RoomItem room = (RoomItem) rRoom.getSelectedItem();
        if (room == null) { warn("Nao ha salas carregadas. Abra \"Salas e horarios\" primeiro."); return; }
        JsonObject r = authReq("check_availability");
        r.addProperty("room_id", room.id);
        r.addProperty("date", rDate.getText().trim());
        r.addProperty("start_time", (String) rStart.getSelectedItem());
        r.addProperty("end_time", (String) rEnd.getSelectedItem());
        call(r, rep -> {
            boolean ok = "true".equals(str(rep, "available"));
            if (ok) setStatus("A sala esta LIVRE nesse horario (a consulta nao reserva nada).", Ui.SUCCESS);
            else setStatus("A sala NAO esta disponivel nesse horario.", Ui.DANGER);
            JOptionPane.showMessageDialog(this,
                    ok ? "A sala esta LIVRE nesse horario.\n(Esta consulta nao reserva nada - clique em \"Criar reserva\".)"
                       : "A sala NAO esta disponivel nesse horario.\n(Ocupada, inativa ou fora do funcionamento.)",
                    "Disponibilidade", ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        });
    }

    private void createReservation() {
        RoomItem room = (RoomItem) rRoom.getSelectedItem();
        if (room == null) { warn("Nao ha salas carregadas. Abra \"Salas e horarios\" primeiro."); return; }
        JsonObject r = authReq("create_reservation");
        r.addProperty("room_id", room.id);
        r.addProperty("date", rDate.getText().trim());
        r.addProperty("start_time", (String) rStart.getSelectedItem());
        r.addProperty("end_time", (String) rEnd.getSelectedItem());
        r.addProperty("topic", rTopic.getText().trim());
        r.addProperty("participants", rPart.getText().trim());
        call(r, rep -> {
            info("Reserva criada com sucesso (ID " + str(rep, "reservation_id") + ").");
            rTopic.setText("");
            rPart.setText("");
            listReservations();
        });
    }

    private void listReservations() {
        JsonObject r = authReq("list_reservations");
        if ("admin".equals(role)) r.addProperty("scope", (String) rScope.getSelectedItem());
        optional(r, "date", rListDate.getText());
        call(r, rep -> {
            String keep = editingId;
            resModel.setRowCount(0);
            JsonArray arr = array(rep, "reservations");
            if (arr != null) {
                for (JsonElement e : arr) {
                    JsonObject x = e.getAsJsonObject();
                    resModel.addRow(new Object[]{str(x, "reservation_id"), str(x, "room_id"), str(x, "name"),
                            str(x, "user"), str(x, "date"), str(x, "start_time"), str(x, "end_time"),
                            str(x, "topic"), str(x, "participants")});
                }
            }
            if (keep != null) { // mantem a reserva em edicao selecionada, se ainda existir
                for (int i = 0; i < resModel.getRowCount(); i++) {
                    if (keep.equals(resModel.getValueAt(i, 0))) {
                        int v = resTable.convertRowIndexToView(i);
                        resTable.setRowSelectionInterval(v, v);
                    }
                }
            }
            if (editingId != null && resTable.getSelectedRow() < 0) { editingId = null; updateEditState(); }
            info(arr == null || arr.size() == 0 ? "Nenhuma reserva encontrada." : arr.size() + " reserva(s) listada(s).");
        });
    }

    private void readReservationById() {
        JsonObject r = authReq("read_reservation");
        r.addProperty("reservation_id", rById.getText().trim());
        call(r, rep -> JOptionPane.showMessageDialog(this,
                "Sala: " + str(rep, "name") + " (ID " + str(rep, "room_id") + ")"
                        + "\nReservada por: " + str(rep, "user") + "\nDia: " + str(rep, "date")
                        + "\nHorario: " + str(rep, "start_time") + " ate " + str(rep, "end_time")
                        + "\nAssunto: " + str(rep, "topic") + "\nParticipantes: " + str(rep, "participants")
                        + "\nCriada em: " + str(rep, "created_at"),
                "Reserva #" + str(rep, "reservation_id"), JOptionPane.INFORMATION_MESSAGE));
    }

    private int selectedResRow() {
        int row = resTable.getSelectedRow();
        if (row < 0) {
            warn("Selecione uma reserva na lista.");
            return -1;
        }
        return resTable.convertRowIndexToModel(row);
    }

    private void fillFormFromSelectedReservation() {
        int row = resTable.getSelectedRow();
        if (row < 0) {
            editingId = null;
            updateEditState();
            return;
        }
        int m = resTable.convertRowIndexToModel(row);
        editingId = (String) resModel.getValueAt(m, 0);
        String roomId = (String) resModel.getValueAt(m, 1);
        boolean found = false;
        for (int i = 0; i < roomCombo.getSize(); i++) {
            if (roomCombo.getElementAt(i).id.equals(roomId)) {
                rRoom.setSelectedIndex(i);
                found = true;
            }
        }
        if (!found) {
            RoomItem it = new RoomItem(roomId, (String) resModel.getValueAt(m, 2), "?");
            roomCombo.addElement(it);
            rRoom.setSelectedItem(it);
        }
        rDate.setText((String) resModel.getValueAt(m, 4));
        rStart.setSelectedItem(resModel.getValueAt(m, 5));
        rEnd.setSelectedItem(resModel.getValueAt(m, 6));
        rTopic.setText((String) resModel.getValueAt(m, 7));
        rPart.setText((String) resModel.getValueAt(m, 8));
        updateEditState();
    }

    private void updateReservation() {
        int m = selectedResRow();
        if (m < 0) return;
        RoomItem room = (RoomItem) rRoom.getSelectedItem();
        // so envia o que mudou (os demais ficam "" = nao alterar)
        JsonObject r = authReq("update_reservation");
        r.addProperty("reservation_id", (String) resModel.getValueAt(m, 0));
        r.addProperty("start_time", diff((String) rStart.getSelectedItem(), (String) resModel.getValueAt(m, 5)));
        r.addProperty("end_time", diff((String) rEnd.getSelectedItem(), (String) resModel.getValueAt(m, 6)));
        r.addProperty("topic", diff(rTopic.getText(), (String) resModel.getValueAt(m, 7)));
        r.addProperty("participants", diff(rPart.getText(), (String) resModel.getValueAt(m, 8)));
        if (room != null && !room.id.equals(resModel.getValueAt(m, 1))) r.addProperty("room_id", room.id);
        String d = rDate.getText().trim();
        if (!d.equals(resModel.getValueAt(m, 4))) r.addProperty("date", d);
        call(r, rep -> {
            info("Reserva atualizada.");
            listReservations();
        });
    }

    private void deleteReservation() {
        int m = selectedResRow();
        if (m < 0) return;
        String id = (String) resModel.getValueAt(m, 0);
        if (JOptionPane.showConfirmDialog(this, "Cancelar a reserva #" + id + " e liberar a sala?", "Confirmar",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        JsonObject r = authReq("delete_reservation");
        r.addProperty("reservation_id", id);
        call(r, rep -> {
            info("Reserva cancelada. A sala foi liberada.");
            editingId = null;
            resTable.clearSelection();
            updateEditState();
            listReservations();
        });
    }

    // ================================================================== CONTA
    private void readUser() {
        call(authReq("read_user"), rep -> {
            role = str(rep, "role");
            accUser.setText(str(rep, "user"));
            accEmail.setText(str(rep, "email"));
            boolean admin = "admin".equals(role);
            accRole.setText(admin ? "Administrador" : "Usuario");
            accRole.setColors(admin ? Ui.PRIMARY : new Color(0xE5E7EB), admin ? Color.WHITE : Ui.TEXT);
            accCreated.setText(str(rep, "created_at"));
        }, false);
    }

    private void updateUser() {
        JsonObject r = authReq("update_user");
        r.addProperty("user", newUser.getText().trim().toLowerCase());
        r.addProperty("password", new String(newPass.getPassword()));
        call(r, rep -> {
            newUser.setText("");
            newPass.setText("");
            info("Dados atualizados.");
            readUser();
        });
    }

    private void deleteUser() {
        JPasswordField pw = Ui.password(16);
        if (JOptionPane.showConfirmDialog(this, new Object[]{"Confirme sua senha para remover a conta:", pw},
                "Remover conta", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        JsonObject r = authReq("delete_user");
        r.addProperty("password", new String(pw.getPassword()));
        call(r, rep -> { });
    }

    // ================================================================== ADMIN
    private void adminList() {
        call(authReq("admin_list_users"), rep -> {
            userModel.setRowCount(0);
            JsonArray arr = array(rep, "users");
            if (arr != null) {
                for (JsonElement e : arr) {
                    JsonObject u = e.getAsJsonObject();
                    userModel.addRow(new Object[]{str(u, "user"), str(u, "email"), str(u, "role")});
                }
            }
            info(str(rep, "count") + " usuario(s) cadastrado(s).");
        }, false);
    }

    private String selectedUser() {
        int row = userTable.getSelectedRow();
        if (row < 0) {
            warn("Selecione um usuario na tabela.");
            return null;
        }
        return (String) userModel.getValueAt(userTable.convertRowIndexToModel(row), 0);
    }

    private void adminRead() {
        String u = selectedUser();
        if (u == null) return;
        JsonObject r = authReq("admin_read_user");
        r.addProperty("target_user", u);
        call(r, rep -> JOptionPane.showMessageDialog(this,
                "Usuario: " + str(rep, "user") + "\nE-mail: " + str(rep, "email") + "\nPerfil: " + str(rep, "role")
                        + "\nCriado em: " + str(rep, "created_at"), "Usuario", JOptionPane.INFORMATION_MESSAGE));
    }

    private void adminUpdate() {
        String u = selectedUser();
        if (u == null) return;
        JTextField nu = Ui.field(14);
        JPasswordField np = Ui.password(14);
        JComboBox<String> nr = Ui.combo(new JComboBox<>(new String[]{"", "user", "admin"}));
        nr.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                String t = "admin".equals(v) ? "Administrador" : "user".equals(v) ? "Usuario comum" : "(manter)";
                return super.getListCellRendererComponent(l, t, i, s, f);
            }
        });
        JPanel form = Ui.vstack(8, Ui.labeled("Novo usuario (vazio = manter)", nu),
                Ui.labeled("Nova senha (vazio = manter)", np), Ui.labeled("Perfil", nr));
        if (JOptionPane.showConfirmDialog(this, form, "Editar " + u, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        JsonObject r = authReq("admin_update_user");
        r.addProperty("target_user", u);
        r.addProperty("user", nu.getText().trim().toLowerCase());
        r.addProperty("password", new String(np.getPassword()));
        r.addProperty("role", (String) nr.getSelectedItem());
        call(r, rep -> {
            info("Usuario atualizado.");
            adminList();
        });
    }

    private void adminDelete() {
        String u = selectedUser();
        if (u == null) return;
        if (JOptionPane.showConfirmDialog(this, "Remover o usuario " + u + " e todas as reservas dele?",
                "Confirmar", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        JsonObject r = authReq("admin_delete_user");
        r.addProperty("target_user", u);
        call(r, rep -> {
            info("Usuario removido.");
            adminList();
        });
    }

    // ================================================================== REDE
    private JsonObject req(String op) {
        JsonObject r = new JsonObject();
        r.addProperty("op", op);
        return r;
    }

    private JsonObject authReq(String op) {
        JsonObject r = req(op);
        r.addProperty("token", token == null ? "" : token);
        return r;
    }

    private void call(JsonObject request, Consumer<JsonObject> onOk) {
        call(request, onOk, true);
    }

    /** Envia a requisicao fora da thread da interface; onOk roda na EDT quando o status e 200/201. */
    private void call(JsonObject request, Consumer<JsonObject> onOk, boolean showErrorDialog) {
        io.submit(() -> {
            JsonObject rep = null;
            String error = null;
            try {
                rep = exchange(request);
            } catch (IOException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            JsonObject fRep = rep;
            String fErr = error;
            SwingUtilities.invokeLater(() -> handleReply(request, fRep, fErr, onOk, showErrorDialog));
        });
    }

    private void handleReply(JsonObject request, JsonObject rep, String error, Consumer<JsonObject> onOk,
                             boolean showErrorDialog) {
        if (rep == null) {
            setStatus("Falha de comunicacao: " + error, Ui.DANGER);
            JOptionPane.showMessageDialog(this, "Falha de comunicacao com o servidor:\n" + error,
                    "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String op = str(request, "op");
        String status = nz(str(rep, "status"));
        String message = nz(str(rep, "message"));
        boolean ok = status.equals("200") || status.equals("201");

        // Regras do protocolo: logout termina com resposta (200 ou 401); 401 descarta o token.
        if ("logout".equals(op) && (status.equals("200") || status.equals("401"))) {
            leaveSession();
            setStatus(status.equals("200") ? "Voce saiu da conta." : "A sessao ja nao era valida no servidor.", Ui.MUTED);
            return;
        }
        if (status.equals("401") && !"login".equals(op)) {
            leaveSession();
            setStatus(message, Ui.DANGER);
            JOptionPane.showMessageDialog(this, message + "\nFaca login novamente.", "Sessao encerrada",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if ("delete_user".equals(op) && status.equals("200")) {
            leaveSession();
            setStatus("Sua conta foi removida.", Ui.MUTED);
            return;
        }
        if (!ok) {
            setStatus(message + " (" + status + ")", Ui.DANGER);
            if (showErrorDialog) {
                JOptionPane.showMessageDialog(this, message + "\n\nCodigo: " + status, "Nao foi possivel concluir",
                        status.startsWith("5") ? JOptionPane.ERROR_MESSAGE : JOptionPane.WARNING_MESSAGE);
            }
            return;
        }
        setStatus(message, Ui.SUCCESS);
        onOk.accept(rep);
    }

    private JsonObject exchange(JsonObject request) throws IOException {
        String json = GSON.toJson(request);
        if ((json + "\n").getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException("A requisicao excede 8192 bytes e nao foi enviada.");

        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) { // 1.11: reconecta e reenvia com o mesmo token
            try {
                if (socket == null || socket.isClosed()) reconnect();
                logLine("CLIENTE -> SERVIDOR [" + host + ":" + port + "]: " + json);
                out.write(json);
                out.write('\n');
                out.flush();

                String line = readLine(in);
                if (line == null) throw new EOFException("O servidor fechou a conexao sem responder.");
                logLine("SERVIDOR -> CLIENTE [" + host + ":" + port + "]: " + line);

                JsonElement e;
                try {
                    e = JsonParser.parseString(line);
                } catch (JsonSyntaxException ex) {
                    throw new ProtocolException("Servidor enviou JSON invalido.");
                }
                if (!e.isJsonObject()) throw new ProtocolException("Resposta do servidor nao e um objeto JSON.");
                return e.getAsJsonObject();
            } catch (ProtocolException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                closeSocket();
                if (attempt == 0) logLine("(conexao perdida/fechada; reconectando e reenviando...)");
            }
        }
        throw last;
    }

    private void reconnect() throws IOException {
        if (host == null) throw new IOException("Nao conectado. Informe IP e porta e clique em Conectar.");
        openSocket(host, port);
    }

    private void openSocket(String h, int p) throws IOException {
        closeSocket();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(h, p), 5000);
        socket = s;
        in = s.getInputStream();
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        host = h;
        port = p;
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null;
        in = null;
        out = null;
    }

    private void connectAction() {
        String h = ipField.getText().trim();
        int p;
        try {
            p = Integer.parseInt(portField.getText().trim());
            if (p < 1 || p > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            warn("Porta invalida. Use um numero entre 1 e 65535.");
            return;
        }
        if (h.isEmpty()) {
            warn("Informe o IP do servidor.");
            return;
        }
        connectBtn.setEnabled(false);
        setStatus("Conectando a " + h + ":" + p + "...", Ui.MUTED);
        io.submit(() -> {
            String error = null;
            boolean hadSession = token != null;
            try {
                if (hadSession && socket != null) { // o token pertence ao servidor antigo: encerra a sessao la
                    try {
                        exchange(authReq("logout"));
                    } catch (IOException ignored) { }
                }
                token = null;
                role = null;
                openSocket(h, p);
            } catch (IOException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String fErr = error;
            SwingUtilities.invokeLater(() -> {
                connectBtn.setEnabled(true);
                if (hadSession) leaveSession();
                connected = fErr == null;
                applyConnectedState();
                if (fErr == null) {
                    setStatus("Conectado a " + h + ":" + p + ".", Ui.SUCCESS);
                } else {
                    setStatus("Nao foi possivel conectar: " + fErr, Ui.DANGER);
                    JOptionPane.showMessageDialog(this, "Nao foi possivel conectar a " + h + ":" + p
                                    + "\n\nVerifique se o servidor esta iniciado e se o IP e a porta estao corretos.\n" + fErr,
                            "Conexao", JOptionPane.ERROR_MESSAGE);
                }
            });
        });
    }

    private void shutdown() {
        try {
            if (token != null) {
                Future<?> f = io.submit(() -> {
                    try { exchange(authReq("logout")); } catch (IOException ignored) { }
                });
                f.get(3, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) { }
        closeSocket();
        dispose();
        System.exit(0);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int x = in.read();
            if (x == -1) return null;
            if (x == '\n') break;
            b.write(x); // respostas nao tem limite de tamanho (v2.9.1)
        }
        byte[] raw = b.toByteArray();
        int len = raw.length;
        if (len > 0 && raw[len - 1] == '\r') len--;
        return new String(raw, 0, len, StandardCharsets.UTF_8);
    }

    private static class ProtocolException extends IOException {
        ProtocolException(String m) { super(m); }
    }

    // ================================================================== UTILITARIOS
    private void logLine(String s) {
        SwingUtilities.invokeLater(() -> logPane.add(s));
    }

    private void info(String s) {
        setStatus(s, Ui.SUCCESS);
    }

    private void warn(String s) {
        JOptionPane.showMessageDialog(this, s, "Atencao", JOptionPane.WARNING_MESSAGE);
    }

    private static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    /** Combo de horarios: o valor continua "HH:mm", mas a lista mostra tambem o codigo da aula. */
    private static JComboBox<String> timeCombo(String[] values, boolean isEnd, boolean optional) {
        JComboBox<String> c = new JComboBox<>();
        if (optional) c.addItem("");
        for (String v : values) c.addItem(v);
        c.setFont(Ui.BASE);
        c.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                String t = "(qualquer)";
                if (v != null && !v.toString().isEmpty()) {
                    int k = java.util.Arrays.asList(values).indexOf(v.toString());
                    t = v + (k >= 0 ? "   (" + (isEnd ? "fim da aula " : "aula ") + CLASS_CODES[k] + ")" : "");
                }
                return super.getListCellRendererComponent(l, t, i, s, f);
            }
        });
        return c;
    }

    private static String str(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return null;
        JsonElement e = o.get(k);
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static JsonArray array(JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonArray()) return null;
        return o.getAsJsonArray(k);
    }

    private static String join(JsonArray a) {
        if (a == null) return "";
        List<String> l = new ArrayList<>();
        for (JsonElement e : a) l.add(e.getAsString());
        return String.join(", ", l);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Filtro opcional: vazio = omitir a chave (regra 2.14). */
    private static void optional(JsonObject r, String key, String value) {
        if (value != null && !value.trim().isEmpty()) r.addProperty(key, value.trim());
    }

    /** "" quando o valor digitado e igual ao atual (nao alterar); senao o valor digitado. */
    private static String diff(String typed, String current) {
        String t = typed == null ? "" : typed.trim();
        return t.equals(nz(current)) ? "" : t;
    }

    private static JsonArray csv(String text) {
        JsonArray arr = new JsonArray();
        if (text == null || text.trim().isEmpty()) return arr;
        for (String part : text.split(",", -1)) arr.add(part.trim());
        return arr;
    }

    private static JsonArray toArray(Collection<String> items) {
        JsonArray arr = new JsonArray();
        for (String s : items) arr.add(s);
        return arr;
    }

    // ================================================================== COMPONENTES AUXILIARES
    private static class RoomItem {
        final String id, name, capacity;
        RoomItem(String id, String name, String capacity) {
            this.id = id;
            this.name = name;
            this.capacity = capacity;
        }
        @Override public String toString() { return name + "   (ID " + id + ", " + capacity + " lugares)"; }
    }

    /** Grade de checkboxes: dias (2..7) x aulas (M1..N5). */
    private static class SlotPicker extends JPanel {
        private static final String[] DAYS = {"Seg", "Ter", "Qua", "Qui", "Sex", "Sab"};
        private final JCheckBox[][] boxes = new JCheckBox[6][CLASS_CODES.length];

        SlotPicker() {
            setOpaque(false);
            setLayout(new GridLayout(7, CLASS_CODES.length + 1, 3, 3));
            add(new JLabel(""));
            for (int k = 0; k < CLASS_CODES.length; k++) {
                JLabel l = new JLabel(CLASS_CODES[k], SwingConstants.CENTER);
                l.setFont(Ui.BASE.deriveFont(Font.BOLD, 11f));
                l.setForeground(k < 6 ? new Color(0xB45309) : k < 12 ? new Color(0x1D4ED8) : new Color(0x6D28D9));
                l.setToolTipText(STARTS[k] + " - " + ENDS[k]);
                add(l);
            }
            for (int d = 0; d < 6; d++) {
                final int day = d;
                JButton b = Ui.button(DAYS[d], Ui.Kind.SECONDARY);
                b.setBorder(new EmptyBorder(4, 6, 4, 6));
                b.setToolTipText("Marcar/desmarcar o dia inteiro");
                b.addActionListener(e -> {
                    boolean all = true;
                    for (JCheckBox c : boxes[day]) all &= c.isSelected();
                    for (JCheckBox c : boxes[day]) c.setSelected(!all);
                });
                add(b);
                for (int k = 0; k < CLASS_CODES.length; k++) {
                    boxes[d][k] = new JCheckBox();
                    boxes[d][k].setOpaque(false);
                    boxes[d][k].setHorizontalAlignment(SwingConstants.CENTER);
                    boxes[d][k].setToolTipText((d + 2) + CLASS_CODES[k] + "  " + STARTS[k] + " - " + ENDS[k]);
                    add(boxes[d][k]);
                }
            }
        }

        List<String> get() {
            List<String> out = new ArrayList<>();
            for (int d = 0; d < 6; d++)
                for (int k = 0; k < CLASS_CODES.length; k++)
                    if (boxes[d][k].isSelected()) out.add((d + 2) + CLASS_CODES[k]);
            return out;
        }

        void set(Collection<String> slots) {
            Set<String> s = new HashSet<>(slots);
            for (int d = 0; d < 6; d++)
                for (int k = 0; k < CLASS_CODES.length; k++)
                    boxes[d][k].setSelected(s.contains((d + 2) + CLASS_CODES[k]));
        }
    }
}
