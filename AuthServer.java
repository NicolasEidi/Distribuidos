import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Reserva de Salas - servidor TCP/JSON com interface grafica (Swing).
 *
 * Segue o protocolo "Reserva de Salas" v2.10.0 (AsyncAPI):
 * https://pedrosalvatori.github.io/protocolo-reserva-salas/
 *
 * - Transporte: TCP, uma mensagem = um objeto JSON em linha unica terminada em LF, UTF-8.
 * - Uma thread por conexao: varios clientes ao mesmo tempo.
 * - Horarios no codigo da UTFPR (dia + turno + aula, ex.: 2M1).
 * - Verificacao de disponibilidade e gravacao da reserva sob o mesmo lock por sala.
 * - Dados em memoria (apagados ao reiniciar o programa).
 */
public class AuthServer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_BYTES = 8192;
    private static final int SOCKET_TIMEOUT_MS = 300_000;
    private static final long TOKEN_TIMEOUT_MS = 30L * 60L * 1000L;

    // ----------------------------------------------------------------- dicionario
    private static final Pattern USER = Pattern.compile("^[a-z]{1,30}$");
    private static final Pattern PASSWORD = Pattern.compile("^[A-Za-z0-9]{1,20}$");
    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$");
    private static final Pattern TOKEN = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern ID = Pattern.compile("^[0-9]{1,10}$");
    private static final Pattern ROOM_NAME = Pattern.compile("^[A-Za-z0-9 -]{1,30}$");
    private static final Pattern CAPACITY = Pattern.compile("^[1-9][0-9]{0,3}$");
    private static final Pattern LOCATION = Pattern.compile("^[A-Za-z0-9 ,.-]{1,60}$");
    private static final Pattern RESOURCE = Pattern.compile("^[A-Za-z0-9 -]{1,30}$");
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$");
    private static final Pattern TOPIC = Pattern.compile("^[A-Za-z0-9 ,.-]{1,60}$");
    private static final Pattern PARTICIPANTS = Pattern.compile("^[1-9][0-9]{0,3}$");
    private static final Pattern SLOT = Pattern.compile("^[2-7](M[1-6]|T[1-6]|N[1-5])$");

    // Mensagens fixas do protocolo.
    private static final String M_TOKEN_FMT = "Token em formato invalido";
    private static final String M_TOKEN_401 = "Token invalido ou expirado";
    private static final String M_PERM = "Permissao insuficiente";
    private static final String M_DADOS = "Dados em formato invalido";
    private static final String M_NODATA = "Nenhum dado para atualizar";
    private static final String M_ROOM_FMT = "Dados da sala em formato invalido";
    private static final String M_RES_FMT = "Dados da reserva em formato invalido";
    private static final String M_FILTERS = "Filtros em formato invalido";
    private static final String M_OK = "Consulta realizada com sucesso";
    private static final String M_LAST_ADMIN = "Nao e possivel remover o ultimo administrador";
    private static final String M_ROOM_404 = "Sala nao encontrada";
    private static final String M_RES_404 = "Reserva nao encontrada";
    private static final String M_USER_404 = "Usuario nao encontrado";
    private static final String M_BUSY = "Sala ja reservada no periodo solicitado";
    private static final String M_INACTIVE = "Sala indisponivel para reserva";
    private static final String M_CLOSED = "Horario fora do funcionamento da sala";
    private static final String M_ENDED = "Reserva ja encerrada";

    // ----------------------------------------------------------------- tabela UTFPR
    private static final String[] CLASS_CODES = {
            "M1", "M2", "M3", "M4", "M5", "M6",
            "T1", "T2", "T3", "T4", "T5", "T6",
            "N1", "N2", "N3", "N4", "N5"};
    private static final LocalTime[] CLASS_START = {
            LocalTime.of(7, 30), LocalTime.of(8, 20), LocalTime.of(9, 10), LocalTime.of(10, 20),
            LocalTime.of(11, 10), LocalTime.of(12, 0),
            LocalTime.of(13, 0), LocalTime.of(13, 50), LocalTime.of(14, 40), LocalTime.of(15, 50),
            LocalTime.of(16, 40), LocalTime.of(17, 30),
            LocalTime.of(18, 40), LocalTime.of(19, 30), LocalTime.of(20, 20), LocalTime.of(21, 20),
            LocalTime.of(22, 10)};
    private static final LocalTime[] CLASS_END = {
            LocalTime.of(8, 20), LocalTime.of(9, 10), LocalTime.of(10, 0), LocalTime.of(11, 10),
            LocalTime.of(12, 0), LocalTime.of(12, 50),
            LocalTime.of(13, 50), LocalTime.of(14, 40), LocalTime.of(15, 30), LocalTime.of(16, 40),
            LocalTime.of(17, 30), LocalTime.of(18, 20),
            LocalTime.of(19, 30), LocalTime.of(20, 20), LocalTime.of(21, 10), LocalTime.of(22, 10),
            LocalTime.of(23, 0)};

    // ----------------------------------------------------------------- estado em memoria
    private static final Map<String, Account> BY_EMAIL = new ConcurrentHashMap<>();
    private static final Map<String, Account> BY_USER = new ConcurrentHashMap<>();
    private static final Map<String, Account> BY_TOKEN = new ConcurrentHashMap<>();
    private static final Set<String> USED_TOKENS = ConcurrentHashMap.newKeySet();
    private static final Set<String> ADMIN_TOKENS = ConcurrentHashMap.newKeySet(); // whitelist (regra 3.14)
    private static final Object USERS_LOCK = new Object();

    private static final Map<String, Room> ROOMS = new ConcurrentHashMap<>();
    private static final Map<String, Reservation> RESERVATIONS = new ConcurrentHashMap<>();
    private static final Map<String, Object> ROOM_LOCKS = new ConcurrentHashMap<>();
    private static final Object ROOMS_LOCK = new Object();
    private static final AtomicLong NEXT_ROOM_ID = new AtomicLong(1);
    private static final AtomicLong NEXT_RESERVATION_ID = new AtomicLong(1);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    // Admin inicial (regra 3.15): igual em todos os servidores.
    private static final String ADMIN_EMAIL = "admin@admin.com";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "admin";

    // ----------------------------------------------------------------- servidor TCP
    private static volatile ServerSocket listener;
    private static final Set<Socket> CONNECTIONS = ConcurrentHashMap.newKeySet();

    // Ganchos da interface.
    private static volatile Consumer<String> logSink = System.out::println;
    private static volatile Runnable sessionListener = () -> { };

    public static void main(String[] args) {
        createBootstrapAdmin();
        Ui.install();
        SwingUtilities.invokeLater(() -> new ServerWindow().setVisible(true));
    }

    private static void log(String s) {
        logSink.accept(s);
    }

    private static void notifySessions() {
        sessionListener.run();
    }

    private static synchronized void startServer(int port) throws IOException {
        if (listener != null) return;
        ServerSocket ss = new ServerSocket(port);
        listener = ss;
        Thread accept = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    CONNECTIONS.add(s);
                    Thread t = new Thread(new ClientHandler(s), "cliente-" + s.getPort());
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    if (!ss.isClosed()) log("[SERVIDOR] Erro no accept: " + e.getMessage());
                }
            }
        }, "accept");
        accept.setDaemon(true);
        accept.start();
    }

    private static synchronized void stopServer() {
        ServerSocket ss = listener;
        listener = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) { }
        }
        for (Socket s : CONNECTIONS) {
            try { s.close(); } catch (IOException ignored) { }
        }
        CONNECTIONS.clear();
    }

    private static void createBootstrapAdmin() {
        synchronized (USERS_LOCK) {
            for (Account a : BY_USER.values()) {
                if ("admin".equals(a.role)) return; // ja existe admin
            }
            if (BY_USER.containsKey(ADMIN_USER) || BY_EMAIL.containsKey(ADMIN_EMAIL)) {
                log("[AVISO] Conta admin/admin@admin.com existe sem ser admin; admin inicial nao criado.");
                return;
            }
            Account a = new Account(ADMIN_EMAIL, ADMIN_USER, hash(ADMIN_PASSWORD), "admin", now());
            BY_EMAIL.put(a.email, a);
            BY_USER.put(a.user, a);
        }
    }

    // ================================================================== INTERFACE GRAFICA
    private static class ServerWindow extends JFrame {
        private final JTextField portField = Ui.field(6);
        private final Ui.Btn toggle = Ui.button("Iniciar servidor", Ui.Kind.PRIMARY);
        private final Ui.Dot dot = new Ui.Dot();
        private final JLabel status = new JLabel("Servidor parado");
        private final JLabel addresses = Ui.muted("Informe a porta e clique em \"Iniciar servidor\".");
        private final Ui.Chip usersChip = new Ui.Chip("0 online", new Color(0xDCFCE7), new Color(0x166534));
        private final Ui.Chip connChip = new Ui.Chip("0 conexoes", new Color(0xE0E7FF), new Color(0x3730A3));
        private final DefaultTableModel model = new DefaultTableModel(
                new Object[]{"Usuario", "E-mail", "Perfil", "Ultima atividade"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        private final Ui.LogPane logPane = new Ui.LogPane();

        ServerWindow() {
            super("Reserva de Salas - Servidor");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(1100, 700);
            setMinimumSize(new Dimension(820, 520));
            setLocationRelativeTo(null);
            getContentPane().setBackground(Ui.BG);
            setLayout(new BorderLayout());
            add(Ui.header("Servidor de Reservas", "Protocolo v2.10  -  recebe os clientes e controla as reservas"),
                    BorderLayout.NORTH);

            // ---- controle
            portField.setText("5000");
            portField.setPreferredSize(new Dimension(90, 34));
            status.setFont(Ui.BASE.deriveFont(Font.BOLD, 14f));
            JPanel controls = Ui.card(new BorderLayout(16, 0));
            JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
            left.add(Ui.labeled("Porta do servidor", portField));
            JPanel btnWrap = Ui.transparent(new BorderLayout());
            btnWrap.setBorder(BorderFactory.createEmptyBorder(19, 0, 0, 0));
            btnWrap.add(toggle, BorderLayout.CENTER);
            left.add(btnWrap);
            controls.add(left, BorderLayout.WEST);

            JPanel st = Ui.transparent(new GridLayout(2, 1, 0, 3));
            JPanel line1 = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
            line1.add(dot);
            line1.add(status);
            line1.add(connChip);
            st.add(line1);
            st.add(addresses);
            controls.add(st, BorderLayout.CENTER);

            // ---- usuarios logados
            JTable table = new JTable(model);
            Ui.table(table, 2);
            JPanel usersHead = Ui.transparent(new BorderLayout());
            usersHead.add(Ui.heading("Usuarios logados"), BorderLayout.WEST);
            usersHead.add(usersChip, BorderLayout.EAST);
            JPanel usersCard = Ui.card(new BorderLayout(0, 10));
            usersCard.add(usersHead, BorderLayout.NORTH);
            usersCard.add(Ui.scroll(table), BorderLayout.CENTER);
            usersCard.add(Ui.muted("Atualiza sozinho a cada login, logout ou expiracao de sessao."), BorderLayout.SOUTH);

            // ---- log
            Ui.Btn clear = Ui.button("Limpar", Ui.Kind.GHOST);
            clear.addActionListener(e -> logPane.clear());
            JPanel logHead = Ui.transparent(new BorderLayout());
            logHead.add(Ui.heading("Mensagens recebidas e enviadas"), BorderLayout.WEST);
            logHead.add(clear, BorderLayout.EAST);
            JPanel logCard = Ui.card(new BorderLayout(0, 8));
            logCard.add(logHead, BorderLayout.NORTH);
            logCard.add(Ui.scroll(logPane), BorderLayout.CENTER);
            logCard.add(Ui.muted("Azul: cliente -> servidor   |   Verde: resposta de sucesso   |   Vermelho: erro"),
                    BorderLayout.SOUTH);

            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, usersCard, logCard);
            split.setResizeWeight(0.38);
            split.setBorder(null);
            split.setOpaque(false);
            split.setDividerSize(10);

            JPanel body = Ui.transparent(new BorderLayout(0, 12));
            body.setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
            body.add(controls, BorderLayout.NORTH);
            body.add(split, BorderLayout.CENTER);
            add(body, BorderLayout.CENTER);

            logSink = this::appendLog;
            sessionListener = () -> SwingUtilities.invokeLater(this::refreshUsers);

            toggle.addActionListener(e -> toggleServer());
            portField.addActionListener(e -> { if (listener == null) toggleServer(); });
            setIndicator(false);

            new javax.swing.Timer(1000, e -> refreshUsers()).start();

            appendLog("Admin inicial: email=" + ADMIN_EMAIL + "  user=" + ADMIN_USER + "  senha=" + ADMIN_PASSWORD);
        }

        private void setIndicator(boolean running) {
            dot.set(running ? Ui.SUCCESS : Ui.DANGER);
        }

        private void toggleServer() {
            if (listener != null) {
                stopServer();
                toggle.setText("Iniciar servidor");
                portField.setEnabled(true);
                status.setText("Servidor parado");
                addresses.setText("Informe a porta e clique em \"Iniciar servidor\".");
                setIndicator(false);
                appendLog("[SERVIDOR] Servidor parado.");
                refreshUsers();
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "Porta invalida. Use um numero entre 1 e 65535.",
                        "Porta", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                startServer(port);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Nao foi possivel abrir a porta " + port
                        + ".\nProvavelmente ela ja esta em uso.\n\n" + e.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
                return;
            }
            toggle.setText("Parar servidor");
            portField.setEnabled(false);
            status.setText("No ar na porta " + port);
            addresses.setText("Os clientes devem conectar em um destes IPs: " + localAddresses());
            setIndicator(true);
            appendLog("[SERVIDOR] Escutando na porta " + port + ". Aguardando conexoes...");
        }

        private static String localAddresses() {
            List<String> out = new ArrayList<>();
            try {
                Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
                while (nis != null && nis.hasMoreElements()) {
                    NetworkInterface ni = nis.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    Enumeration<InetAddress> as = ni.getInetAddresses();
                    while (as.hasMoreElements()) {
                        InetAddress a = as.nextElement();
                        if (a instanceof Inet4Address) out.add(a.getHostAddress());
                    }
                }
            } catch (SocketException ignored) { }
            out.add("127.0.0.1 (esta maquina)");
            return String.join("   ", out);
        }

        private void refreshUsers() {
            List<Account> list = activeSessions();
            model.setRowCount(0);
            DateTimeFormatter f = DateTimeFormatter.ofPattern("HH:mm:ss");
            for (Account a : list) {
                String last = java.time.Instant.ofEpochMilli(a.lastUse).atZone(ZONE).format(f);
                model.addRow(new Object[]{a.user, a.email, a.role, last});
            }
            usersChip.setText(list.size() + " online");
            int n = CONNECTIONS.size();
            connChip.setText(n + (n == 1 ? " conexao" : " conexoes"));
        }

        private void appendLog(String s) {
            SwingUtilities.invokeLater(() -> logPane.add(s));
        }
    }

    /** Sessoes realmente ativas (token existente e nao expirado). */
    private static List<Account> activeSessions() {
        List<Account> list = new ArrayList<>();
        long t = System.currentTimeMillis();
        for (Account a : BY_USER.values()) {
            synchronized (a) {
                if (a.token == null) continue;
                if (expired(a, t)) {
                    invalidateQuiet(a);
                    continue;
                }
                list.add(a);
            }
        }
        list.sort(Comparator.comparing(a -> a.user));
        return list;
    }

    // ================================================================== CONEXAO
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        ClientHandler(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            String who = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            log("[" + who + "] cliente conectado");

            try (Socket s = socket;
                 InputStream in = s.getInputStream();
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

                s.setSoTimeout(SOCKET_TIMEOUT_MS);

                while (true) {
                    Line line;
                    try {
                        line = readLine(in);
                    } catch (SocketTimeoutException e) {
                        log("[" + who + "] conexao encerrada por 300 s de inatividade (token continua valido)");
                        break;
                    }
                    if (line == null) break;

                    if (line.tooLong) {
                        log("[" + who + "] CLIENTE -> SERVIDOR: <mensagem acima de 8192 bytes>");
                        send(out, response("error", "400", "Mensagem excede o tamanho maximo"), who);
                        continue;
                    }

                    log("[" + who + "] CLIENTE -> SERVIDOR: " + line.text);

                    JsonObject req;
                    try {
                        JsonElement el = JsonParser.parseString(line.text);
                        if (!el.isJsonObject()) {
                            send(out, response("error", "400", "Requisicao invalida"), who);
                            continue;
                        }
                        req = el.getAsJsonObject();
                    } catch (JsonSyntaxException e) {
                        send(out, response("error", "400", "Requisicao invalida"), who);
                        continue;
                    }

                    String op = str(req, "op");
                    if (op == null) {
                        send(out, response("error", "400", "Requisicao invalida"), who);
                        continue;
                    }
                    if (!known(op)) {
                        send(out, response("error", "400", "Operacao desconhecida"), who);
                        continue;
                    }

                    JsonObject rep;
                    try {
                        rep = dispatch(op, req);
                    } catch (Exception e) {
                        e.printStackTrace();
                        rep = response(op + "_response", "500", "Erro interno do servidor");
                    }
                    send(out, rep, who);
                }
            } catch (IOException e) {
                if (!socket.isClosed()) log("[" + who + "] erro de I/O: " + e.getMessage());
            } finally {
                CONNECTIONS.remove(socket);
                log("[" + who + "] cliente desconectado");
            }
        }
    }

    private static boolean known(String op) {
        return Set.of(
                "register", "login", "logout",
                "read_user", "update_user", "delete_user",
                "admin_list_users", "admin_read_user", "admin_update_user", "admin_delete_user",
                "create_room", "list_rooms", "read_room", "update_room", "delete_room",
                "check_availability", "create_reservation", "list_reservations",
                "read_reservation", "update_reservation", "delete_reservation"
        ).contains(op);
    }

    private static JsonObject dispatch(String op, JsonObject r) {
        return switch (op) {
            case "register" -> register(r);
            case "login" -> login(r);
            case "logout" -> logout(r);
            case "read_user" -> readUser(r);
            case "update_user" -> updateUser(r);
            case "delete_user" -> deleteUser(r);
            case "admin_list_users" -> adminListUsers(r);
            case "admin_read_user" -> adminReadUser(r);
            case "admin_update_user" -> adminUpdateUser(r);
            case "admin_delete_user" -> adminDeleteUser(r);
            case "create_room" -> createRoom(r);
            case "list_rooms" -> listRooms(r);
            case "read_room" -> readRoom(r);
            case "update_room" -> updateRoom(r);
            case "delete_room" -> deleteRoom(r);
            case "check_availability" -> checkAvailability(r);
            case "create_reservation" -> createReservation(r);
            case "list_reservations" -> listReservations(r);
            case "read_reservation" -> readReservation(r);
            case "update_reservation" -> updateReservation(r);
            case "delete_reservation" -> deleteReservation(r);
            default -> response("error", "400", "Operacao desconhecida");
        };
    }

    // ================================================================== AUTENTICACAO
    private static JsonObject register(JsonObject r) {
        String email = str(r, "email");
        String user = str(r, "user");
        String password = str(r, "password");
        String op = "register_response";

        if (!validEmail(email) || !match(USER, user) || !match(PASSWORD, password))
            return response(op, "400", "Dados de cadastro em formato invalido");

        synchronized (USERS_LOCK) {
            if (BY_EMAIL.containsKey(email) || BY_USER.containsKey(user))
                return response(op, "409", "Usuario ou email ja cadastrado");

            Account a = new Account(email, user, hash(password), "user", now());
            BY_EMAIL.put(email, a);
            BY_USER.put(user, a);
        }
        return response(op, "201", "Usuario cadastrado com sucesso");
    }

    private static JsonObject login(JsonObject r) {
        String email = str(r, "email");
        String password = str(r, "password");
        String op = "login_response";

        if (!validEmail(email) || !match(PASSWORD, password))
            return response(op, "400", "Email ou senha em formato invalido");

        Account a = BY_EMAIL.get(email);
        if (a == null || !passwordOk(password, a.passwordHash))
            return response(op, "401", "Email ou senha incorretos");

        JsonObject rep;
        synchronized (a) { // verificar sessao e criar token na mesma secao critica (regra 3.11)
            long t = System.currentTimeMillis();
            if (a.token != null) {
                if (expired(a, t)) invalidateQuiet(a);
                else return response(op, "409", "Usuario ja possui sessao ativa");
            }
            a.token = newToken();
            a.lastUse = t;
            BY_TOKEN.put(a.token, a);
            if ("admin".equals(a.role)) ADMIN_TOKENS.add(a.token);

            rep = response(op, "200", "Login realizado com sucesso");
            rep.addProperty("token", a.token);
            rep.addProperty("role", a.role);
        }
        notifySessions();
        return rep;
    }

    private static JsonObject logout(JsonObject r) {
        String token = str(r, "token");
        String op = "logout_response";
        if (!match(TOKEN, token)) return response(op, "400", M_TOKEN_FMT);

        Account a = auth(token, false);
        if (a == null) return response(op, "401", M_TOKEN_401);

        invalidate(a);
        return response(op, "200", "Logout realizado com sucesso");
    }

    // ================================================================== PROPRIO CADASTRO
    private static JsonObject readUser(JsonObject r) {
        String token = str(r, "token");
        String op = "read_user_response";
        if (!match(TOKEN, token)) return response(op, "400", M_TOKEN_FMT);

        Account a = auth(token, true);
        if (a == null) return response(op, "401", M_TOKEN_401);
        return userDetail(op, a);
    }

    private static JsonObject updateUser(JsonObject r) {
        String token = str(r, "token");
        String user = str(r, "user");
        String password = str(r, "password");
        String op = "update_user_response";

        if (r.has("email") || !match(TOKEN, token) || !update(USER, user) || !update(PASSWORD, password))
            return response(op, "400", M_DADOS);
        if (user.isEmpty() && password.isEmpty())
            return response(op, "400", M_NODATA);

        Account a = auth(token, true);
        if (a == null) return response(op, "401", M_TOKEN_401);

        synchronized (USERS_LOCK) {
            if (!user.isEmpty() && !user.equals(a.user)) {
                Account used = BY_USER.get(user);
                if (used != null && used != a)
                    return response(op, "409", "Usuario ja esta em uso");
                BY_USER.remove(a.user, a);
                a.user = user;
                BY_USER.put(a.user, a);
            }
            if (!password.isEmpty()) a.passwordHash = hash(password);
        }
        notifySessions();
        return response(op, "200", "Dados atualizados com sucesso");
    }

    private static JsonObject deleteUser(JsonObject r) {
        String token = str(r, "token");
        String password = str(r, "password");
        String op = "delete_user_response";

        if (!match(TOKEN, token)) return response(op, "400", M_TOKEN_FMT);
        if (!match(PASSWORD, password)) return response(op, "400", "Senha em formato invalido");

        Account a = auth(token, true);
        if (a == null) return response(op, "401", M_TOKEN_401);

        if (!passwordOk(password, a.passwordHash))
            return response(op, "403", "Senha incorreta");

        synchronized (USERS_LOCK) {
            if ("admin".equals(a.role) && adminCount() == 1)
                return response(op, "403", M_LAST_ADMIN);
            removeAccount(a);
        }
        notifySessions();
        return response(op, "200", "Usuario removido com sucesso");
    }

    // ================================================================== ADMIN
    private static JsonObject adminListUsers(JsonObject r) {
        String token = str(r, "token");
        String op = "admin_list_users_response";
        if (!match(TOKEN, token)) return response(op, "400", M_TOKEN_FMT);

        JsonObject err = adminError(op, auth(token, true), token);
        if (err != null) return err;

        List<Account> list = new ArrayList<>(BY_USER.values());
        list.sort(Comparator.comparing(a -> a.user));

        JsonArray users = new JsonArray();
        for (Account a : list) {
            JsonObject u = new JsonObject();
            u.addProperty("user", a.user);
            u.addProperty("email", a.email);
            u.addProperty("role", a.role);
            users.add(u);
        }
        JsonObject rep = response(op, "200", M_OK);
        rep.addProperty("count", String.valueOf(list.size()));
        rep.add("users", users);
        return rep;
    }

    private static JsonObject adminReadUser(JsonObject r) {
        String token = str(r, "token");
        String target = str(r, "target_user");
        String op = "admin_read_user_response";

        if (!match(TOKEN, token) || !match(USER, target)) return response(op, "400", M_DADOS);

        JsonObject err = adminError(op, auth(token, true), token);
        if (err != null) return err;

        Account a = BY_USER.get(target);
        if (a == null) return response(op, "404", M_USER_404);
        return userDetail(op, a);
    }

    private static JsonObject adminUpdateUser(JsonObject r) {
        String token = str(r, "token");
        String targetName = str(r, "target_user");
        String user = str(r, "user");
        String password = str(r, "password");
        String role = str(r, "role");
        String op = "admin_update_user_response";

        if (r.has("email") || !match(TOKEN, token) || !match(USER, targetName)
                || !update(USER, user) || !update(PASSWORD, password) || !updateRole(role))
            return response(op, "400", M_DADOS);
        if (user.isEmpty() && password.isEmpty() && role.isEmpty())
            return response(op, "400", M_NODATA);

        JsonObject err = adminError(op, auth(token, true), token);
        if (err != null) return err;

        synchronized (USERS_LOCK) {
            Account target = BY_USER.get(targetName);
            if (target == null) return response(op, "404", M_USER_404);

            if ("admin".equals(target.role) && "user".equals(role) && adminCount() == 1)
                return response(op, "403", M_LAST_ADMIN);

            if (!user.isEmpty() && !user.equals(target.user)) {
                Account used = BY_USER.get(user);
                if (used != null && used != target)
                    return response(op, "409", "Usuario ja esta em uso");
                BY_USER.remove(target.user, target);
                target.user = user;
                BY_USER.put(target.user, target);
            }
            if (!password.isEmpty()) target.passwordHash = hash(password);
            if (!role.isEmpty()) {
                synchronized (target) {
                    target.role = role;
                    if (target.token != null) { // vale na hora, sem novo login
                        if ("admin".equals(role)) ADMIN_TOKENS.add(target.token);
                        else ADMIN_TOKENS.remove(target.token);
                    }
                }
            }
        }
        notifySessions();
        return response(op, "200", "Dados atualizados com sucesso");
    }

    private static JsonObject adminDeleteUser(JsonObject r) {
        String token = str(r, "token");
        String targetName = str(r, "target_user");
        String op = "admin_delete_user_response";

        if (!match(TOKEN, token) || !match(USER, targetName)) return response(op, "400", M_DADOS);

        JsonObject err = adminError(op, auth(token, true), token);
        if (err != null) return err;

        synchronized (USERS_LOCK) {
            Account target = BY_USER.get(targetName);
            if (target == null) return response(op, "404", M_USER_404);
            if ("admin".equals(target.role) && adminCount() == 1)
                return response(op, "403", M_LAST_ADMIN);
            removeAccount(target);
        }
        notifySessions();
        return response(op, "200", "Usuario removido com sucesso");
    }

    // ================================================================== SALAS
    private static JsonObject createRoom(JsonObject r) {
        String token = str(r, "token");
        String name = str(r, "name");
        String capacityText = str(r, "capacity");
        String location = str(r, "location");
        List<String> resources = parseStrings(r, "resources");
        List<String> slots = parseStrings(r, "slots");
        String op = "create_room_response";

        if (!match(TOKEN, token) || !match(ROOM_NAME, name) || !match(CAPACITY, capacityText)
                || !match(LOCATION, location)
                || !validResources(resources) || !validSlots(slots) || slots.isEmpty())
            return response(op, "400", M_ROOM_FMT);

        JsonObject err = adminError(op, auth(token, true), token);
        if (err != null) return err;

        synchronized (ROOMS_LOCK) {
            if (roomNameExists(name, null))
                return response(op, "409", "Sala ja cadastrada");

            String id = String.valueOf(NEXT_ROOM_ID.getAndIncrement());
            Room room = new Room(id, name, Integer.parseInt(capacityText), location, resources,
                    sortedSlots(slots), "active");
            ROOM_LOCKS.putIfAbsent(id, new Object());
            ROOMS.put(id, room);

            JsonObject rep = response(op, "201", "Sala cadastrada com sucesso");
            rep.addProperty("room_id", id);
            return rep;
        }
    }

    private static JsonObject listRooms(JsonObject r) {
        String token = str(r, "token");
        boolean hasDate = r.has("date"), hasStart = r.has("start_time"), hasEnd = r.has("end_time");
        boolean hasMin = r.has("min_capacity");
        String dateText = hasDate ? str(r, "date") : null;
        String startText = hasStart ? str(r, "start_time") : null;
        String endText = hasEnd ? str(r, "end_time") : null;
        String minText = hasMin ? str(r, "min_capacity") : null;
        String op = "list_rooms_response";

        boolean trio = hasDate && hasStart && hasEnd;
        boolean none = !hasDate && !hasStart && !hasEnd;

        if (!match(TOKEN, token) || (!trio && !none)
                || (trio && !validSchedule(dateText, startText, endText))
                || (hasMin && !match(CAPACITY, minText)))
            return response(op, "400", M_FILTERS);

        Account a = auth(token, true);
        if (a == null) return response(op, "401", M_TOKEN_401);

        int min = hasMin ? Integer.parseInt(minText) : 0;
        LocalDate date = trio ? LocalDate.parse(dateText) : null;
        int si = trio ? startIndex(startText) : -1;
        int ei = trio ? endIndex(endText) : -1;

        List<Room> rooms = new ArrayList<>(ROOMS.values());
        rooms.sort(Comparator.comparingLong(x -> Long.parseLong(x.id)));

        JsonArray arr = new JsonArray();
        for (Room room : rooms) {
            if (room.capacity < min) continue;
            JsonObject item = new JsonObject();
            copyRoomFields(item, room);
            if (trio) {
                boolean available;
                synchronized (roomLock(room.id)) {
                    available = isRoomFree(room, date, si, ei, null);
                }
                item.addProperty("available", String.valueOf(available));
            }
            arr.add(item);
        }
        JsonObject rep = response(op, "200", M_OK);
        rep.addProperty("count", String.valueOf(arr.size()));
        rep.add("rooms", arr);
        return rep;
    }

    private static JsonObject readRoom(JsonObject r) {
        String token = str(r, "token");
        String roomId = str(r, "room_id");
        boolean hasDate = r.has("date");
        String dateText = hasDate ? str(r, "date") : null;
        String op = "read_room_response";

        if (!match(TOKEN, token) || !match(ID, roomId) || (hasDate && !validDate(dateText)))
            return response(op, "400", M_DADOS);

        Account requester = auth(token, true);
        if (requester == null) return response(op, "401", M_TOKEN_401);

        Room room = ROOMS.get(roomId);
        if (room == null) return response(op, "404", M_ROOM_404);

        JsonObject rep = response(op, "200", M_OK);
        copyRoomFields(rep, room);

        if (hasDate) {
            LocalDate date = LocalDate.parse(dateText);
            rep.addProperty("date", dateText);
            rep.add("agenda", agenda(room, date, requester, ADMIN_TOKENS.contains(token)));
        }
        return rep;
    }

    private static JsonArray agenda(Room room, LocalDate date, Account requester, boolean admin) {
        JsonArray arr = new JsonArray();
        int day = dayDigit(date);
        if (day < 0) return arr;

        boolean inactive = !"active".equals(room.roomStatus);
        for (int k = 0; k < CLASS_CODES.length; k++) {
            String code = day + CLASS_CODES[k];
            if (!room.slots.contains(code)) continue;

            Reservation found = null;
            for (Reservation res : RESERVATIONS.values()) {
                if (res.room == room && res.date.equals(date)
                        && res.start.isBefore(CLASS_END[k]) && res.end.isAfter(CLASS_START[k])) {
                    found = res;
                    break;
                }
            }
            JsonObject item = new JsonObject();
            item.addProperty("slot", code);
            item.addProperty("start_time", CLASS_START[k].format(HH_MM));
            item.addProperty("end_time", CLASS_END[k].format(HH_MM));
            item.addProperty("slot_status", (found != null || inactive) ? "indisponivel" : "livre");
            if (found != null) {
                item.addProperty("user", found.owner.user);
                if (admin || found.owner == requester) item.addProperty("reservation_id", found.id);
            }
            arr.add(item);
        }
        return arr;
    }

    private static JsonObject updateRoom(JsonObject r) {
        String token = str(r, "token");
        String roomId = str(r, "room_id");
        String name = str(r, "name");
        String capacityText = str(r, "capacity");
        String location = str(r, "location");
        String roomStatus = str(r, "room_status");
        List<String> resources = r.has("resources") ? parseStrings(r, "resources") : new ArrayList<>();
        List<String> slots = r.has("slots") ? parseStrings(r, "slots") : new ArrayList<>();
        String op = "update_room_response";

        boolean clearResources = resources != null && resources.size() == 1 && "0".equals(resources.get(0));

        if (!match(TOKEN, token) || !match(ID, roomId)
                || !update(ROOM_NAME, name) || !update(CAPACITY, capacityText)
                || !update(LOCATION, location) || !updateRoomStatus(roomStatus)
                || resources == null || slots == null
                || (!clearResources && !validResources(resources)) || !validSlots(slots))
            return response(op, "400", M_ROOM_FMT);

        if (name.isEmpty() && capacityText.isEmpty() && location.isEmpty() && roomStatus.isEmpty()
                && resources.isEmpty() && slots.isEmpty())
            return response(op, "400", M_NODATA);

        JsonObject err = adminError(op, auth(token, true), token);
        if (err != null) return err;

        if (!ROOMS.containsKey(roomId)) return response(op, "404", M_ROOM_404);

        synchronized (roomLock(roomId)) {
            Room room = ROOMS.get(roomId);
            if (room == null) return response(op, "404", M_ROOM_404);

            synchronized (ROOMS_LOCK) {
                if (!name.isEmpty() && roomNameExists(name, roomId))
                    return response(op, "409", "Sala ja cadastrada");
            }
            if (!capacityText.isEmpty()
                    && activeReservationExceeds(room, Integer.parseInt(capacityText)))
                return response(op, "409", "Capacidade menor que participantes de reservas ativas");

            if (!name.isEmpty()) room.name = name;
            if (!capacityText.isEmpty()) room.capacity = Integer.parseInt(capacityText);
            if (!location.isEmpty()) room.location = location;
            if (clearResources) room.resources = new ArrayList<>();
            else if (!resources.isEmpty()) room.resources = new ArrayList<>(resources);
            if (!slots.isEmpty()) room.slots = sortedSlots(slots);
            if (!roomStatus.isEmpty()) room.roomStatus = roomStatus;
        }
        return response(op, "200", "Sala atualizada com sucesso");
    }

    private static JsonObject deleteRoom(JsonObject r) {
        String token = str(r, "token");
        String roomId = str(r, "room_id");
        String op = "delete_room_response";

        if (!match(TOKEN, token) || !match(ID, roomId)) return response(op, "400", M_DADOS);

        JsonObject err = adminError(op, auth(token, true), token);
        if (err != null) return err;

        if (!ROOMS.containsKey(roomId)) return response(op, "404", M_ROOM_404);

        synchronized (roomLock(roomId)) {
            Room room = ROOMS.get(roomId);
            if (room == null) return response(op, "404", M_ROOM_404);

            for (Reservation res : RESERVATIONS.values()) {
                if (res.room == room && !ended(res))
                    return response(op, "409", "Sala possui reservas ativas");
            }
            // apaga as reservas encerradas da sala (regra 9.8)
            for (Reservation res : new ArrayList<>(RESERVATIONS.values())) {
                if (res.room == room) RESERVATIONS.remove(res.id, res);
            }
            ROOMS.remove(roomId, room);
        }
        return response(op, "200", "Sala removida com sucesso");
    }

    // ================================================================== RESERVAS
    private static JsonObject checkAvailability(JsonObject r) {
        String token = str(r, "token");
        String roomId = str(r, "room_id");
        String dateText = str(r, "date");
        String startText = str(r, "start_time");
        String endText = str(r, "end_time");
        String op = "check_availability_response";

        if (!match(TOKEN, token) || !match(ID, roomId) || !validSchedule(dateText, startText, endText))
            return response(op, "400", M_DADOS);

        Account a = auth(token, true);
        if (a == null) return response(op, "401", M_TOKEN_401);

        Room room = ROOMS.get(roomId);
        if (room == null) return response(op, "404", M_ROOM_404);

        boolean available;
        synchronized (roomLock(roomId)) {
            room = ROOMS.get(roomId);
            if (room == null) return response(op, "404", M_ROOM_404);
            available = isRoomFree(room, LocalDate.parse(dateText), startIndex(startText), endIndex(endText), null);
        }
        JsonObject rep = response(op, "200", M_OK);
        rep.addProperty("room_id", roomId);
        rep.addProperty("date", dateText);
        rep.addProperty("start_time", startText);
        rep.addProperty("end_time", endText);
        rep.addProperty("available", String.valueOf(available));
        return rep;
    }

    private static JsonObject createReservation(JsonObject r) {
        String token = str(r, "token");
        String roomId = str(r, "room_id");
        String dateText = str(r, "date");
        String startText = str(r, "start_time");
        String endText = str(r, "end_time");
        String topic = str(r, "topic");
        String participantsText = str(r, "participants");
        String op = "create_reservation_response";

        if (!match(TOKEN, token) || !match(ID, roomId) || !validSchedule(dateText, startText, endText)
                || !match(TOPIC, topic) || !match(PARTICIPANTS, participantsText))
            return response(op, "400", M_RES_FMT);

        LocalDate date = LocalDate.parse(dateText);
        int si = startIndex(startText), ei = endIndex(endText);
        if (!LocalDateTime.of(date, CLASS_START[si]).isAfter(LocalDateTime.now(ZONE)))
            return response(op, "400", M_RES_FMT); // inicio no passado (regra 9.3)

        Account owner = auth(token, true);
        if (owner == null) return response(op, "401", M_TOKEN_401);

        Room room = ROOMS.get(roomId);
        if (room == null) return response(op, "404", M_ROOM_404);

        int participants = Integer.parseInt(participantsText);

        synchronized (roomLock(roomId)) { // verificar + gravar na mesma secao critica
            room = ROOMS.get(roomId);
            if (room == null) return response(op, "404", M_ROOM_404);

            if (participants > room.capacity) return response(op, "400", M_RES_FMT);
            if (!"active".equals(room.roomStatus)) return response(op, "409", M_INACTIVE);
            if (!withinHours(room, date, si, ei)) return response(op, "409", M_CLOSED);
            if (hasConflict(room, date, CLASS_START[si], CLASS_END[ei], null))
                return response(op, "409", M_BUSY);

            String id = String.valueOf(NEXT_RESERVATION_ID.getAndIncrement());
            RESERVATIONS.put(id, new Reservation(id, room, owner, date,
                    CLASS_START[si], CLASS_END[ei], topic, participants, now()));

            JsonObject rep = response(op, "201", "Reserva realizada com sucesso");
            rep.addProperty("reservation_id", id);
            return rep;
        }
    }

    private static JsonObject listReservations(JsonObject r) {
        String token = str(r, "token");
        boolean hasScope = r.has("scope"), hasDate = r.has("date"), hasRoom = r.has("room_id");
        String scope = hasScope ? str(r, "scope") : "mine";
        String dateText = hasDate ? str(r, "date") : null;
        String roomId = hasRoom ? str(r, "room_id") : null;
        String op = "list_reservations_response";

        if (!match(TOKEN, token)
                || (hasScope && !("mine".equals(scope) || "all".equals(scope)))
                || (hasDate && !validDate(dateText))
                || (hasRoom && !match(ID, roomId)))
            return response(op, "400", M_FILTERS);

        Account requester = auth(token, true);
        if (requester == null) return response(op, "401", M_TOKEN_401);

        if ("all".equals(scope) && !ADMIN_TOKENS.contains(token))
            return response(op, "403", M_PERM);

        LocalDate date = hasDate ? LocalDate.parse(dateText) : null;

        List<Reservation> list = new ArrayList<>(RESERVATIONS.values());
        list.sort(Comparator.comparingLong(x -> Long.parseLong(x.id)));

        JsonArray arr = new JsonArray();
        for (Reservation res : list) {
            if ("mine".equals(scope) && res.owner != requester) continue;
            if (date != null && !date.equals(res.date)) continue;
            if (roomId != null && !roomId.equals(res.room.id)) continue;
            JsonObject j = new JsonObject();
            copyReservationFields(j, res, false);
            arr.add(j);
        }
        JsonObject rep = response(op, "200", M_OK);
        rep.addProperty("count", String.valueOf(arr.size()));
        rep.add("reservations", arr);
        return rep;
    }

    private static JsonObject readReservation(JsonObject r) {
        String token = str(r, "token");
        String reservationId = str(r, "reservation_id");
        String op = "read_reservation_response";

        if (!match(TOKEN, token) || !match(ID, reservationId)) return response(op, "400", M_DADOS);

        Account requester = auth(token, true);
        if (requester == null) return response(op, "401", M_TOKEN_401);

        Reservation res = RESERVATIONS.get(reservationId);
        if (res == null) return response(op, "404", M_RES_404);

        if (res.owner != requester && !ADMIN_TOKENS.contains(token))
            return response(op, "403", M_PERM);

        JsonObject rep = response(op, "200", M_OK);
        copyReservationFields(rep, res, true);
        return rep;
    }

    private static JsonObject updateReservation(JsonObject r) {
        String token = str(r, "token");
        String reservationId = str(r, "reservation_id");
        String startText = str(r, "start_time");
        String endText = str(r, "end_time");
        String topic = str(r, "topic");
        String participantsText = str(r, "participants");
        String roomIdText = r.has("room_id") ? str(r, "room_id") : "";
        String dateText = r.has("date") ? str(r, "date") : "";
        String op = "update_reservation_response";

        // 400 de formato
        if (!match(TOKEN, token) || !match(ID, reservationId)
                || startText == null || endText == null
                || !(startText.isEmpty() || startIndex(startText) >= 0)
                || !(endText.isEmpty() || endIndex(endText) >= 0)
                || !update(TOPIC, topic) || !update(PARTICIPANTS, participantsText)
                || !update(ID, roomIdText)
                || dateText == null || !(dateText.isEmpty() || validDate(dateText)))
            return response(op, "400", M_RES_FMT);
        if (!startText.isEmpty() && !endText.isEmpty()
                && !CLASS_END[endIndex(endText)].isAfter(CLASS_START[startIndex(startText)]))
            return response(op, "400", M_RES_FMT);
        if (startText.isEmpty() && endText.isEmpty() && topic.isEmpty() && participantsText.isEmpty()
                && roomIdText.isEmpty() && dateText.isEmpty())
            return response(op, "400", M_NODATA);

        // 401
        Account requester = auth(token, true);
        if (requester == null) return response(op, "401", M_TOKEN_401);

        // 404: primeiro a reserva, depois a sala de destino
        Reservation res = RESERVATIONS.get(reservationId);
        if (res == null) return response(op, "404", M_RES_404);
        Room dest = roomIdText.isEmpty() ? res.room : ROOMS.get(roomIdText);
        if (dest == null) return response(op, "404", M_ROOM_404);

        // 403 de dono (admin tambem nao altera reserva de terceiro)
        if (res.owner != requester) return response(op, "403", M_PERM);

        // 409 encerrada
        if (ended(res)) return response(op, "409", M_ENDED);

        // locks das duas salas em ordem crescente de room_id (regra 5.6)
        Room a = res.room, b = dest;
        if (Long.parseLong(a.id) > Long.parseLong(b.id)) { Room t = a; a = b; b = t; }
        synchronized (roomLock(a.id)) {
            synchronized (a == b ? new Object() : roomLock(b.id)) {
                res = RESERVATIONS.get(reservationId);
                if (res == null) return response(op, "404", M_RES_404);
                if (res.room != a && res.room != b) {
                    // a reserva foi movida entre a verificacao e o lock: refaz com os locks corretos
                    return updateReservation(r);
                }
                dest = roomIdText.isEmpty() ? res.room : ROOMS.get(roomIdText);
                if (dest == null) return response(op, "404", M_ROOM_404);
                if (ended(res)) return response(op, "409", M_ENDED);

                LocalDate newDate = dateText.isEmpty() ? res.date : LocalDate.parse(dateText);
                LocalTime newStart = startText.isEmpty() ? res.start : CLASS_START[startIndex(startText)];
                LocalTime newEnd = endText.isEmpty() ? res.end : CLASS_END[endIndex(endText)];
                String newTopic = topic.isEmpty() ? res.topic : topic;
                int newParticipants = participantsText.isEmpty() ? res.participants : Integer.parseInt(participantsText);

                // 400 que depende do registro
                if (newParticipants > dest.capacity) return response(op, "400", M_RES_FMT);
                if (!newEnd.isAfter(newStart)) return response(op, "400", M_RES_FMT);

                boolean scheduleChanged = dest != res.room || !newDate.equals(res.date)
                        || !newStart.equals(res.start) || !newEnd.equals(res.end);
                boolean startChanged = dest != res.room || !newDate.equals(res.date) || !newStart.equals(res.start);

                LocalDateTime now = LocalDateTime.now(ZONE);
                // alterar nao encerra reserva; encurtar uma reserva em andamento e valido (regra 9.3)
                if (startChanged && !LocalDateTime.of(newDate, newStart).isAfter(now))
                    return response(op, "400", M_RES_FMT);
                if (!LocalDateTime.of(newDate, newEnd).isAfter(now))
                    return response(op, "400", M_RES_FMT);

                // 409: sala inativa -> fora do funcionamento -> ocupada (sempre a sala de destino)
                if (scheduleChanged) {
                    if (!"active".equals(dest.roomStatus)) return response(op, "409", M_INACTIVE);
                    int si = startIndexOf(newStart), ei = endIndexOf(newEnd);
                    if (si < 0 || ei < 0 || !withinHours(dest, newDate, si, ei))
                        return response(op, "409", M_CLOSED);
                    if (hasConflict(dest, newDate, newStart, newEnd, res.id))
                        return response(op, "409", M_BUSY);
                }

                res.room = dest;
                res.date = newDate;
                res.start = newStart;
                res.end = newEnd;
                res.topic = newTopic;
                res.participants = newParticipants;
            }
        }
        return response(op, "200", "Reserva atualizada com sucesso");
    }

    private static JsonObject deleteReservation(JsonObject r) {
        String token = str(r, "token");
        String reservationId = str(r, "reservation_id");
        String op = "delete_reservation_response";

        if (!match(TOKEN, token) || !match(ID, reservationId)) return response(op, "400", M_DADOS);

        Account requester = auth(token, true);
        if (requester == null) return response(op, "401", M_TOKEN_401);

        Reservation res = RESERVATIONS.get(reservationId);
        if (res == null) return response(op, "404", M_RES_404);

        if (res.owner != requester && !ADMIN_TOKENS.contains(token))
            return response(op, "403", M_PERM);

        synchronized (roomLock(res.room.id)) {
            res = RESERVATIONS.get(reservationId);
            if (res == null) return response(op, "404", M_RES_404);
            if (ended(res)) return response(op, "409", M_ENDED);
            RESERVATIONS.remove(reservationId, res);
        }
        return response(op, "200", "Reserva cancelada com sucesso");
    }

    // ================================================================== REGRAS DE HORARIO
    private static int startIndex(String s) {
        if (s == null) return -1;
        for (int i = 0; i < CLASS_START.length; i++) {
            if (CLASS_START[i].format(HH_MM).equals(s)) return i;
        }
        return -1;
    }

    private static int endIndex(String s) {
        if (s == null) return -1;
        for (int i = 0; i < CLASS_END.length; i++) {
            if (CLASS_END[i].format(HH_MM).equals(s)) return i;
        }
        return -1;
    }

    private static int startIndexOf(LocalTime t) {
        for (int i = 0; i < CLASS_START.length; i++) if (CLASS_START[i].equals(t)) return i;
        return -1;
    }

    private static int endIndexOf(LocalTime t) {
        for (int i = 0; i < CLASS_END.length; i++) if (CLASS_END[i].equals(t)) return i;
        return -1;
    }

    /** 2 = segunda ... 7 = sabado; -1 = domingo (nunca funciona). */
    private static int dayDigit(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SUNDAY ? -1 : w.getValue() + 1;
    }

    /** date valida + start no inicio de uma aula + end no fim de uma aula + end > start. */
    private static boolean validSchedule(String date, String start, String end) {
        if (!validDate(date)) return false;
        int si = startIndex(start), ei = endIndex(end);
        if (si < 0 || ei < 0) return false;
        return CLASS_END[ei].isAfter(CLASS_START[si]);
    }

    private static boolean withinHours(Room room, LocalDate date, int si, int ei) {
        int day = dayDigit(date);
        if (day < 0) return false;
        for (int k = si; k <= ei; k++) {
            if (!room.slots.contains(day + CLASS_CODES[k])) return false;
        }
        return true;
    }

    private static boolean hasConflict(Room room, LocalDate date, LocalTime start, LocalTime end, String ignoreId) {
        for (Reservation res : RESERVATIONS.values()) {
            if (res.room != room || !res.date.equals(date)) continue;
            if (ignoreId != null && ignoreId.equals(res.id)) continue;
            if (ended(res)) continue; // so reservas ativas conflitam (regra 5.4)
            if (start.isBefore(res.end) && end.isAfter(res.start)) return true;
        }
        return false;
    }

    private static boolean isRoomFree(Room room, LocalDate date, int si, int ei, String ignoreId) {
        return "active".equals(room.roomStatus)
                && withinHours(room, date, si, ei)
                && !hasConflict(room, date, CLASS_START[si], CLASS_END[ei], ignoreId);
    }

    private static boolean ended(Reservation res) {
        return !LocalDateTime.of(res.date, res.end).isAfter(LocalDateTime.now(ZONE));
    }

    private static List<String> sortedSlots(List<String> slots) {
        List<String> copy = new ArrayList<>(slots);
        copy.sort(Comparator.comparingInt(AuthServer::slotOrder));
        return copy;
    }

    private static int slotOrder(String slot) {
        int day = slot.charAt(0) - '0';
        String cls = slot.substring(1);
        for (int i = 0; i < CLASS_CODES.length; i++) {
            if (CLASS_CODES[i].equals(cls)) return day * 100 + i;
        }
        return day * 100;
    }

    // ================================================================== AUXILIARES DE ESTADO
    private static Account auth(String token, boolean renew) {
        Account a = BY_TOKEN.get(token);
        if (a == null) return null;

        boolean expiredNow = false;
        synchronized (a) {
            long t = System.currentTimeMillis();
            if (!token.equals(a.token)) {
                BY_TOKEN.remove(token, a);
                return null;
            }
            if (expired(a, t)) {
                invalidateQuiet(a);
                expiredNow = true;
            } else {
                if (renew) a.lastUse = t;
                return a;
            }
        }
        if (expiredNow) notifySessions();
        return null;
    }

    /** 401 se nao ha sessao; 403 se o token nao esta na whitelist de admin. */
    private static JsonObject adminError(String op, Account a, String token) {
        if (a == null) return response(op, "401", M_TOKEN_401);
        if (!ADMIN_TOKENS.contains(token)) return response(op, "403", M_PERM);
        return null;
    }

    private static boolean expired(Account a, long t) {
        return t - a.lastUse > TOKEN_TIMEOUT_MS;
    }

    private static void invalidateQuiet(Account a) {
        synchronized (a) {
            if (a.token != null) {
                ADMIN_TOKENS.remove(a.token);
                BY_TOKEN.remove(a.token, a);
                a.token = null;
                a.lastUse = 0L;
            }
        }
    }

    private static void invalidate(Account a) {
        invalidateQuiet(a);
        notifySessions();
    }

    /** Remove a conta, encerra a sessao e apaga todas as reservas dela (regra 3.13). */
    private static void removeAccount(Account a) {
        for (Reservation res : new ArrayList<>(RESERVATIONS.values())) {
            if (res.owner == a) {
                synchronized (roomLock(res.room.id)) {
                    RESERVATIONS.remove(res.id, res);
                }
            }
        }
        BY_EMAIL.remove(a.email, a);
        BY_USER.remove(a.user, a);
        invalidateQuiet(a);
    }

    private static int adminCount() {
        int n = 0;
        for (Account a : BY_USER.values()) if ("admin".equals(a.role)) n++;
        return n;
    }

    private static Object roomLock(String roomId) {
        return ROOM_LOCKS.computeIfAbsent(roomId, k -> new Object());
    }

    private static boolean roomNameExists(String name, String exceptRoomId) {
        for (Room room : ROOMS.values()) {
            if (name.equals(room.name) && (exceptRoomId == null || !exceptRoomId.equals(room.id))) return true;
        }
        return false;
    }

    private static boolean activeReservationExceeds(Room room, int newCapacity) {
        for (Reservation res : RESERVATIONS.values()) {
            if (res.room == room && !ended(res) && res.participants > newCapacity) return true;
        }
        return false;
    }

    // ================================================================== JSON
    private static JsonObject userDetail(String op, Account a) {
        JsonObject rep = response(op, "200", M_OK);
        rep.addProperty("user", a.user);
        rep.addProperty("email", a.email);
        rep.addProperty("role", a.role);
        rep.addProperty("created_at", a.createdAt);
        return rep;
    }

    private static void copyRoomFields(JsonObject j, Room room) {
        j.addProperty("room_id", room.id);
        j.addProperty("name", room.name);
        j.addProperty("capacity", String.valueOf(room.capacity));
        j.addProperty("location", room.location);
        JsonArray resources = new JsonArray();
        for (String s : room.resources) resources.add(s);
        j.add("resources", resources);
        j.addProperty("room_status", room.roomStatus);
        JsonArray slots = new JsonArray();
        for (String s : room.slots) slots.add(s);
        j.add("slots", slots);
    }

    private static void copyReservationFields(JsonObject j, Reservation res, boolean includeCreatedAt) {
        j.addProperty("reservation_id", res.id);
        j.addProperty("room_id", res.room.id);
        j.addProperty("name", res.room.name);
        j.addProperty("user", res.owner.user);
        j.addProperty("date", res.date.toString());
        j.addProperty("start_time", res.start.format(HH_MM));
        j.addProperty("end_time", res.end.format(HH_MM));
        j.addProperty("topic", res.topic);
        j.addProperty("participants", String.valueOf(res.participants));
        if (includeCreatedAt) j.addProperty("created_at", res.createdAt);
    }

    private static JsonObject response(String op, String status, String message) {
        JsonObject j = new JsonObject();
        j.addProperty("op", op);
        j.addProperty("status", status);
        j.addProperty("message", message);
        return j;
    }

    private static String str(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return null;
        JsonElement e = o.get(k);
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    /** Lista de strings JSON; null se ausente, null JSON, nao-array ou com item que nao e string. */
    private static List<String> parseStrings(JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonArray()) return null;
        List<String> list = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(k)) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
            list.add(e.getAsString());
        }
        return list;
    }

    private static boolean validResources(List<String> list) {
        if (list == null) return false;
        for (String s : list) {
            if (!RESOURCE.matcher(s).matches() || "0".equals(s)) return false;
        }
        return true;
    }

    private static boolean validSlots(List<String> list) {
        if (list == null) return false;
        Set<String> seen = new HashSet<>();
        for (String s : list) {
            if (!SLOT.matcher(s).matches() || !seen.add(s)) return false;
        }
        return true;
    }

    private static boolean validEmail(String s) {
        return match(EMAIL, s) && s.length() <= 100;
    }

    private static boolean match(Pattern p, String s) {
        return s != null && p.matcher(s).matches();
    }

    private static boolean update(Pattern p, String s) {
        return s != null && (s.isEmpty() || p.matcher(s).matches());
    }

    private static boolean updateRole(String s) {
        return s != null && (s.isEmpty() || "user".equals(s) || "admin".equals(s));
    }

    private static boolean updateRoomStatus(String s) {
        return s != null && (s.isEmpty() || "active".equals(s) || "inactive".equals(s));
    }

    private static boolean validDate(String s) {
        if (!match(DATE, s)) return false;
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static void send(BufferedWriter out, JsonObject j, String who) throws IOException {
        String json = GSON.toJson(j);
        log("[" + who + "] SERVIDOR -> CLIENTE: " + json);
        out.write(json);
        out.write('\n');
        out.flush();
    }

    private static Line readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int count = 0;
        boolean longLine = false;

        while (true) {
            int x = in.read();
            if (x == -1) return null;
            count++;
            if (count > MAX_BYTES) longLine = true;
            if (x == '\n') break;
            if (!longLine) b.write(x);
        }
        if (longLine) return new Line(null, true);

        byte[] raw = b.toByteArray();
        int len = raw.length;
        if (len > 0 && raw[len - 1] == '\r') len--;
        return new Line(new String(raw, 0, len, StandardCharsets.UTF_8), false);
    }

    private static byte[] hash(String password) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean passwordOk(String password, byte[] stored) {
        return MessageDigest.isEqual(hash(password), stored);
    }

    private static String newToken() {
        byte[] raw = new byte[32];
        while (true) {
            RANDOM.nextBytes(raw);
            StringBuilder s = new StringBuilder(64);
            for (byte b : raw) s.append(String.format("%02x", b & 0xff));
            String token = s.toString();
            if (USED_TOKENS.add(token)) return token;
        }
    }

    private static String now() {
        return LocalDateTime.now(ZONE).format(DATE_TIME);
    }

    // ================================================================== MODELO
    private static class Account {
        final String email;
        volatile String user;
        volatile byte[] passwordHash;
        volatile String role;
        final String createdAt;
        volatile String token;
        volatile long lastUse;

        Account(String email, String user, byte[] passwordHash, String role, String createdAt) {
            this.email = email;
            this.user = user;
            this.passwordHash = passwordHash;
            this.role = role;
            this.createdAt = createdAt;
        }
    }

    private static class Room {
        final String id;
        volatile String name;
        volatile int capacity;
        volatile String location;
        volatile List<String> resources;
        volatile List<String> slots;
        volatile String roomStatus;

        Room(String id, String name, int capacity, String location,
             List<String> resources, List<String> slots, String roomStatus) {
            this.id = id;
            this.name = name;
            this.capacity = capacity;
            this.location = location;
            this.resources = new ArrayList<>(resources);
            this.slots = slots;
            this.roomStatus = roomStatus;
        }
    }

    private static class Reservation {
        final String id;
        volatile Room room;
        final Account owner;
        volatile LocalDate date;
        volatile LocalTime start;
        volatile LocalTime end;
        volatile String topic;
        volatile int participants;
        final String createdAt;

        Reservation(String id, Room room, Account owner, LocalDate date,
                    LocalTime start, LocalTime end, String topic, int participants, String createdAt) {
            this.id = id;
            this.room = room;
            this.owner = owner;
            this.date = date;
            this.start = start;
            this.end = end;
            this.topic = topic;
            this.participants = participants;
            this.createdAt = createdAt;
        }
    }

    private static class Line {
        final String text;
        final boolean tooLong;
        Line(String text, boolean tooLong) {
            this.text = text;
            this.tooLong = tooLong;
        }
    }
}
