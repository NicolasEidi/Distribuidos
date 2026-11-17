import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Reserva de Salas - servidor CLI TCP/JSON em memoria.
 *
 * Operacoes implementadas:
 * - Autenticacao: register, login, logout
 * - Proprio cadastro: read_user, update_user, delete_user
 * - Administrador: admin_list_users, admin_read_user, admin_update_user, admin_delete_user
 * - Salas: create_room, list_rooms, read_room, update_room, delete_room
 * - Reservas: check_availability, create_reservation, list_reservations,
 *             read_reservation, update_reservation, delete_reservation
 *
 * O servidor segue o protocolo Reserva de Salas v2.6.0, com persistencia simplificada
 * em memoria para o trabalho academico.
 */
public class AuthServer {
    private static final Gson GSON = new Gson();
    private static final int MAX_BYTES = 8192;
    private static final int SOCKET_TIMEOUT_MS = 300_000;
    private static final long TOKEN_TIMEOUT_MS = 30L * 60L * 1000L;

    // Dicionario do protocolo.
    private static final Pattern USER = Pattern.compile("^[a-z]{1,30}$");
    private static final Pattern PASSWORD = Pattern.compile("^[A-Za-z0-9]{1,20}$");
    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$");
    private static final Pattern TOKEN = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern ROOM_ID = Pattern.compile("^[0-9]{1,10}$");
    private static final Pattern ROOM_NAME = Pattern.compile("^[A-Za-z0-9 -]{1,30}$");
    private static final Pattern CAPACITY = Pattern.compile("^[1-9][0-9]{0,3}$");
    private static final Pattern LOCATION = Pattern.compile("^[A-Za-z0-9 ,.-]{1,60}$");
    private static final Pattern RESERVATION_ID = Pattern.compile("^[0-9]{1,10}$");
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    private static final Pattern TIME = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
    private static final Pattern TOPIC = Pattern.compile("^[A-Za-z0-9 ,.-]{1,60}$");
    private static final Pattern PARTICIPANTS = Pattern.compile("^[1-9][0-9]{0,3}$");

    // Usuarios e sessoes em memoria.
    private static final Map<String, Account> BY_EMAIL = new ConcurrentHashMap<>();
    private static final Map<String, Account> BY_USER = new ConcurrentHashMap<>();
    private static final Map<String, Account> BY_TOKEN = new ConcurrentHashMap<>();
    private static final Set<String> USED_TOKENS = ConcurrentHashMap.newKeySet();
    private static final Set<String> ADMIN_TOKENS = ConcurrentHashMap.newKeySet(); // Whitelist de admin (v2.5)
    private static final Object USERS_LOCK = new Object();

    // Salas e reservas em memoria.
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

    // Bootstrap admin inicial conforme regra v2.6.0
    private static final String ADMIN_EMAIL = "admin@admin.com";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "admin";

    public static void main(String[] args) throws IOException {
        createBootstrapAdmin();

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.print("Qual porta o servidor deve usar? ");
        int port;
        try {
            port = Integer.parseInt(console.readLine());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Porta invalida. Use um numero entre 1 e 65535.");
            return;
        }

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("Servidor iniciado na porta " + port + ".");
            System.out.println("Autenticacao: register, login, logout");
            System.out.println("Proprio cadastro: read_user, update_user, delete_user");
            System.out.println("Administrador: admin_list_users, admin_read_user, admin_update_user, admin_delete_user");
            System.out.println("Salas: create_room, list_rooms, read_room, update_room, delete_room");
            System.out.println("Reservas: check_availability, create_reservation, list_reservations, read_reservation, update_reservation, delete_reservation");
            System.out.println("Admin inicial: email=" + ADMIN_EMAIL + " user=" + ADMIN_USER + " senha=" + ADMIN_PASSWORD);
            System.out.println();
            System.out.println("Comandos locais do servidor:");
            System.out.println("  usuarios  - lista todos os usuarios cadastrados");
            System.out.println("  ajuda     - mostra os comandos locais");
            System.out.println();
            System.out.println("Logs de protocolo: JSON recebido e enviado serao exibidos no console.");
            System.out.println("Aguardando conexoes...\n");

            startConsoleCommands(console);

            while (true) {
                Socket client = server.accept();
                new Thread(new ClientHandler(client)).start();
            }
        } catch (BindException e) {
            System.err.println("A porta " + port + " ja esta em uso.");
        }
    }

    private static void createBootstrapAdmin() {
        synchronized (USERS_LOCK) {
            if (BY_USER.containsKey(ADMIN_USER)) return;
            Account a = new Account(ADMIN_EMAIL, ADMIN_USER, hash(ADMIN_PASSWORD), "admin", now());
            BY_EMAIL.put(a.email, a);
            BY_USER.put(a.user, a);
        }
    }

    private static void startConsoleCommands(BufferedReader console) {
        Thread consoleThread = new Thread(() -> {
            try {
                String command;
                while ((command = console.readLine()) != null) {
                    command = command.trim().toLowerCase(Locale.ROOT);
                    switch (command) {
                        case "usuarios", "users", "listar_usuarios" -> printRegisteredUsers();
                        case "ajuda", "help", "?" -> printServerHelp();
                        case "" -> {}
                        default -> System.out.println("[SERVIDOR] Comando desconhecido: " + command + ". Digite 'ajuda'.");
                    }
                }
            } catch (IOException e) {
                System.err.println("[SERVIDOR] Console local encerrado: " + e.getMessage());
            }
        }, "server-console");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private static void printServerHelp() {
        System.out.println();
        System.out.println("=== COMANDOS LOCAIS DO SERVIDOR ===");
        System.out.println("usuarios  - lista todos os usuarios cadastrados em memoria");
        System.out.println("ajuda     - mostra esta ajuda");
        System.out.println("====================================");
        System.out.println();
    }

    private static void printRegisteredUsers() {
        List<Account> users;
        synchronized (USERS_LOCK) {
            users = new ArrayList<>(BY_USER.values());
        }
        users.sort(Comparator.comparing(a -> a.user));
        System.out.println();
        System.out.println("=== USUARIOS CADASTRADOS NO SERVIDOR ===");
        System.out.println("Total: " + users.size());
        if (users.isEmpty()) {
            System.out.println("(nenhum usuario cadastrado)");
        } else {
            int i = 1;
            for (Account a : users) {
                System.out.println(i++ + ". user=" + a.user + " | email=" + a.email + " | role=" + a.role + " | criado_em=" + a.createdAt);
            }
        }
        System.out.println("========================================");
        System.out.println();
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        ClientHandler(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            String who = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            System.out.println("Cliente conectado: " + who);

            try (Socket s = socket;
                 InputStream in = s.getInputStream();
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

                s.setSoTimeout(SOCKET_TIMEOUT_MS);

                while (true) {
                    Line line;
                    try {
                        line = readLine(in);
                    } catch (SocketTimeoutException e) {
                        System.out.println("Conexao encerrada por 300 s de inatividade: " + who);
                        break;
                    }

                    if (line == null) break;

                    if (line.tooLong) {
                        System.out.println("[" + who + "] CLIENTE -> SERVIDOR | JSON RECEBIDO: <mensagem excedeu 8192 bytes>");
                        send(out, response("error", "400", "Mensagem excede o tamanho maximo"), who);
                        continue;
                    }

                    System.out.println("[" + who + "] CLIENTE -> SERVIDOR | JSON RECEBIDO: " + line.text);

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

                    try {
                        JsonObject rep = dispatch(op, req);
                        send(out, rep, who);
                        System.out.println("[" + who + "] " + op + " -> " + rep.get("status").getAsString());
                    } catch (Exception e) {
                        send(out, response(op + "_response", "500", "Erro interno do servidor"), who);
                        System.err.println("Erro interno em " + op + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.err.println("Problema de I/O com " + who + ": " + e.getMessage());
            } finally {
                System.out.println("Cliente desconectado: " + who);
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

    private static JsonObject register(JsonObject r) {
        String email = str(r, "email");
        String user = str(r, "user");
        String password = str(r, "password");

        if (!match(EMAIL, email) || !match(USER, user) || !match(PASSWORD, password))
            return response("register_response", "400", "Dados de cadastro em formato invalido");

        synchronized (USERS_LOCK) {
            if (BY_EMAIL.containsKey(email) || BY_USER.containsKey(user))
                return response("register_response", "409", "Usuario ou email ja cadastrado");

            Account a = new Account(email, user, hash(password), "user", now());
            BY_EMAIL.put(email, a);
            BY_USER.put(user, a);
        }

        return response("register_response", "201", "Usuario cadastrado com sucesso");
    }

    private static JsonObject login(JsonObject r) {
        String email = str(r, "email");
        String password = str(r, "password");

        if (!match(EMAIL, email) || !match(PASSWORD, password))
            return response("login_response", "400", "Email ou senha em formato invalido");

        Account a = BY_EMAIL.get(email);
        if (a == null || !passwordOk(password, a.passwordHash))
            return response("login_response", "401", "Email ou senha incorretos");

        synchronized (a) {
            long t = System.currentTimeMillis();
            if (a.token != null) {
                if (expired(a, t)) invalidate(a);
                else return response("login_response", "409", "Usuario ja possui sessao ativa");
            }

            a.token = newToken();
            a.lastUse = t;
            BY_TOKEN.put(a.token, a);
            if ("admin".equals(a.role)) {
                ADMIN_TOKENS.add(a.token);
            }

            JsonObject rep = response("login_response", "200", "Login realizado com sucesso");
            rep.addProperty("token", a.token);
            rep.addProperty("role", a.role);
            return rep;
        }
    }

    private static JsonObject logout(JsonObject r) {
        String token = str(r, "token");
        if (!match(TOKEN, token))
            return response("logout_response", "400", "Token em formato invalido");

        Account a = auth(token, false);
        if (a == null)
            return response("logout_response", "401", "Token invalido ou expirado");

        invalidate(a);
        return response("logout_response", "200", "Logout realizado com sucesso");
    }

    private static JsonObject readUser(JsonObject r) {
        String token = str(r, "token");
        if (!match(TOKEN, token))
            return response("read_user_response", "400", "Token em formato invalido");

        Account a = auth(token, true);
        if (a == null)
            return response("read_user_response", "401", "Token invalido ou expirado");

        return userDetail("read_user_response", a);
    }

    private static JsonObject updateUser(JsonObject r) {
        String token = str(r, "token");
        String user = str(r, "user");
        String password = str(r, "password");

        if (r.has("email") || !match(TOKEN, token) || !update(USER, user) || !update(PASSWORD, password))
            return response("update_user_response", "400", "Dados em formato invalido");

        if (user.isEmpty() && password.isEmpty())
            return response("update_user_response", "400", "Nenhum dado para atualizar");

        Account a = auth(token, true);
        if (a == null)
            return response("update_user_response", "401", "Token invalido ou expirado");

        synchronized (USERS_LOCK) {
            if (!user.isEmpty() && !user.equals(a.user)) {
                Account used = BY_USER.get(user);
                if (used != null && used != a)
                    return response("update_user_response", "409", "Usuario ja esta em uso");

                BY_USER.remove(a.user, a);
                a.user = user;
                BY_USER.put(a.user, a);
            }

            if (!password.isEmpty()) a.passwordHash = hash(password);
        }

        return response("update_user_response", "200", "Dados atualizados com sucesso");
    }

    private static JsonObject deleteUser(JsonObject r) {
        String token = str(r, "token");
        String password = str(r, "password");

        if (!match(TOKEN, token) || !match(PASSWORD, password))
            return response("delete_user_response", "400", "Senha em formato invalido");

        Account a = auth(token, true);
        if (a == null)
            return response("delete_user_response", "401", "Token invalido ou expirado");

        if (!passwordOk(password, a.passwordHash))
            return response("delete_user_response", "403", "Senha incorreta");

        synchronized (USERS_LOCK) {
            if ("admin".equals(a.role) && adminCount() == 1)
                return response("delete_user_response", "403", "Nao e possivel remover o ultimo administrador");

            deleteAllReservationsOf(a);
            BY_EMAIL.remove(a.email, a);
            BY_USER.remove(a.user, a);
            invalidate(a);
        }

        return response("delete_user_response", "200", "Usuario removido com sucesso");
    }

    private static JsonObject adminListUsers(JsonObject r) {
        String token = str(r, "token");
        if (!match(TOKEN, token))
            return response("admin_list_users_response", "400", "Token em formato invalido");

        Account admin = auth(token, true);
        if (admin == null || !ADMIN_TOKENS.contains(token) || !"admin".equals(admin.role))
            return response("admin_list_users_response", "403", "Permissao insuficiente");

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

        JsonObject rep = response("admin_list_users_response", "200", "Consulta realizada com sucesso");
        rep.addProperty("count", String.valueOf(list.size()));
        rep.add("users", users);
        return rep;
    }

    private static JsonObject adminReadUser(JsonObject r) {
        String token = str(r, "token");
        String target = str(r, "target_user");

        if (!match(TOKEN, token) || !match(USER, target))
            return response("admin_read_user_response", "400", "Dados em formato invalido");

        Account admin = auth(token, true);
        if (admin == null || !ADMIN_TOKENS.contains(token) || !"admin".equals(admin.role))
            return response("admin_read_user_response", "403", "Permissao insuficiente");

        Account a = BY_USER.get(target);
        if (a == null)
            return response("admin_read_user_response", "404", "Usuario nao encontrado");

        return userDetail("admin_read_user_response", a);
    }

    private static JsonObject adminUpdateUser(JsonObject r) {
        String token = str(r, "token");
        String targetName = str(r, "target_user");
        String user = str(r, "user");
        String password = str(r, "password");
        String role = str(r, "role");

        if (r.has("email") || !match(TOKEN, token) || !match(USER, targetName)
                || !update(USER, user) || !update(PASSWORD, password) || !updateRole(role))
            return response("admin_update_user_response", "400", "Dados em formato invalido");

        if (user.isEmpty() && password.isEmpty() && role.isEmpty())
            return response("admin_update_user_response", "400", "Nenhum dado para atualizar");

        Account admin = auth(token, true);
        if (admin == null || !ADMIN_TOKENS.contains(token) || !"admin".equals(admin.role))
            return response("admin_update_user_response", "403", "Permissao insuficiente");

        synchronized (USERS_LOCK) {
            Account target = BY_USER.get(targetName);
            if (target == null)
                return response("admin_update_user_response", "404", "Usuario nao encontrado");

            if (!user.isEmpty() && !user.equals(target.user)) {
                Account used = BY_USER.get(user);
                if (used != null && used != target)
                    return response("admin_update_user_response", "409", "Usuario ja esta em uso");
            }

            if ("admin".equals(target.role) && "user".equals(role) && adminCount() == 1)
                return response("admin_update_user_response", "403", "Nao e possivel remover o ultimo administrador");

            if (!user.isEmpty() && !user.equals(target.user)) {
                BY_USER.remove(target.user, target);
                target.user = user;
                BY_USER.put(target.user, target);
            }
            if (!password.isEmpty()) target.passwordHash = hash(password);
            if (!role.isEmpty()) {
                target.role = role;
                if ("admin".equals(role) && target.token != null) {
                    ADMIN_TOKENS.add(target.token);
                } else if ("user".equals(role) && target.token != null) {
                    ADMIN_TOKENS.remove(target.token);
                }
            }
        }

        return response("admin_update_user_response", "200", "Dados atualizados com sucesso");
    }

    private static JsonObject adminDeleteUser(JsonObject r) {
        String token = str(r, "token");
        String targetName = str(r, "target_user");

        if (!match(TOKEN, token) || !match(USER, targetName))
            return response("admin_delete_user_response", "400", "Dados em formato invalido");

        Account admin = auth(token, true);
        if (admin == null || !ADMIN_TOKENS.contains(token) || !"admin".equals(admin.role))
            return response("admin_delete_user_response", "403", "Permissao insuficiente");

        synchronized (USERS_LOCK) {
            Account target = BY_USER.get(targetName);
            if (target == null)
                return response("admin_delete_user_response", "404", "Usuario nao encontrado");

            if ("admin".equals(target.role) && adminCount() == 1)
                return response("admin_delete_user_response", "403", "Nao e possivel remover o ultimo administrador");

            deleteAllReservationsOf(target);
            BY_EMAIL.remove(target.email, target);
            BY_USER.remove(target.user, target);
            invalidate(target);
        }

        return response("admin_delete_user_response", "200", "Usuario removido com sucesso");
    }

    private static JsonObject createRoom(JsonObject r) {
        String token = str(r, "token");
        String name = str(r, "name");
        String capacityText = str(r, "capacity");
        String location = str(r, "location");
        List<String> resources = resources(r, "resources");

        if (!match(TOKEN, token) || !match(ROOM_NAME, name) || !match(CAPACITY, capacityText)
                || !match(LOCATION, location) || resources == null)
            return response("create_room_response", "400", "Dados da sala em formato invalido");

        Account a = auth(token, true);
        if (a == null || !ADMIN_TOKENS.contains(token) || !"admin".equals(a.role))
            return response("create_room_response", "403", "Permissao insuficiente");

        synchronized (ROOMS_LOCK) {
            if (roomNameExists(name, null))
                return response("create_room_response", "409", "Sala ja cadastrada");

            String id = String.valueOf(NEXT_ROOM_ID.getAndIncrement());
            Room room = new Room(id, name, Integer.parseInt(capacityText), location, resources, "active");
            ROOMS.put(id, room);
            ROOM_LOCKS.putIfAbsent(id, new Object());

            JsonObject rep = response("create_room_response", "201", "Sala cadastrada com sucesso");
            rep.addProperty("room_id", id);
            return rep;
        }
    }

    private static JsonObject listRooms(JsonObject r) {
        String token = str(r, "token");
        boolean hasDate = r.has("date");
        boolean hasStart = r.has("start_time");
        boolean hasEnd = r.has("end_time");
        boolean hasMin = r.has("min_capacity");

        String dateText = hasDate ? str(r, "date") : null;
        String startText = hasStart ? str(r, "start_time") : null;
        String endText = hasEnd ? str(r, "end_time") : null;
        String minText = hasMin ? str(r, "min_capacity") : null;

        boolean trioComplete = hasDate && hasStart && hasEnd;
        boolean trioAbsent = !hasDate && !hasStart && !hasEnd;

        if (!match(TOKEN, token)
                || (!trioComplete && !trioAbsent)
                || (trioComplete && !validDateTimeTriple(dateText, startText, endText))
                || (hasMin && !match(CAPACITY, minText)))
            return response("list_rooms_response", "400", "Filtros em formato invalido");

        Account a = auth(token, true);
        if (a == null)
            return response("list_rooms_response", "401", "Token invalido ou expirado");

        int min = hasMin ? Integer.parseInt(minText) : 0;
        LocalDate date = trioComplete ? parseDate(dateText) : null;
        LocalTime start = trioComplete ? parseTime(startText) : null;
        LocalTime end = trioComplete ? parseTime(endText) : null;

        List<Room> rooms = new ArrayList<>(ROOMS.values());
        rooms.sort(Comparator.comparingLong(room -> Long.parseLong(room.id)));

        JsonArray arr = new JsonArray();
        for (Room room : rooms) {
            if (room.capacity < min) continue;

            JsonObject item = roomJson(room);
            if (trioComplete) {
                boolean available = "active".equals(room.roomStatus)
                        && isAvailable(room.id, date, start, end, null);
                item.addProperty("available", String.valueOf(available));
            }
            arr.add(item);
        }

        JsonObject rep = response("list_rooms_response", "200", "Consulta realizada com sucesso");
        rep.addProperty("count", String.valueOf(arr.size()));
        rep.add("rooms", arr);
        return rep;
    }

    private static JsonObject readRoom(JsonObject r) {
        String token = str(r, "token");
        String roomId = str(r, "room_id");

        if (!match(TOKEN, token) || !match(ROOM_ID, roomId))
            return response("read_room_response", "400", "Dados em formato invalido");

        Account a = auth(token, true);
        if (a == null)
            return response("read_room_response", "401", "Token invalido ou expirado");

        Room room = ROOMS.get(roomId);
        if (room == null)
            return response("read_room_response", "404", "Sala nao encontrada");

        JsonObject rep = response("read_room_response", "200", "Consulta realizada com sucesso");
        copyRoomFields(rep, room);
        return rep;
    }

    private static JsonObject updateRoom(JsonObject r) {
        String token = str(r, "token");
        String roomId = str(r, "room_id");
        String name = str(r, "name");
        String capacityText = str(r, "capacity");
        String location = str(r, "location");
        String roomStatus = str(r, "room_status");
        List<String> resources = resources(r, "resources");

        if (!match(TOKEN, token) || !match(ROOM_ID, roomId)
                || !update(ROOM_NAME, name) || !update(CAPACITY, capacityText)
                || !update(LOCATION, location) || !updateRoomStatus(roomStatus)
                || resources == null)
            return response("update_room_response", "400", "Dados da sala em formato invalido");

        Account a = auth(token, true);
        if (a == null || !ADMIN_TOKENS.contains(token) || !"admin".equals(a.role))
            return response("update_room_response", "403", "Permissao insuficiente");

        Room room = ROOMS.get(roomId);
        if (room == null)
            return response("update_room_response", "404", "Sala nao encontrada");

        Object lock = roomLock(roomId);
        synchronized (lock) {
            room = ROOMS.get(roomId);
            if (room == null)
                return response("update_room_response", "404", "Sala nao encontrada");

            synchronized (ROOMS_LOCK) {
                if (!name.isEmpty() && !name.equals(room.name) && roomNameExists(name, roomId))
                    return response("update_room_response", "409", "Sala ja cadastrada");
            }

            int newCapacity = capacityText.isEmpty() ? room.capacity : Integer.parseInt(capacityText);

            if (futureReservationExceedsCapacity(roomId, newCapacity))
                return response("update_room_response", "409", "Sala ja cadastrada");

            if (!name.isEmpty()) room.name = name;
            if (!capacityText.isEmpty()) room.capacity = newCapacity;
            if (!location.isEmpty()) room.location = location;
            room.resources = new ArrayList<>(resources);
            if (!roomStatus.isEmpty()) room.roomStatus = roomStatus;
        }

        return response("update_room_response", "200", "Sala atualizada com sucesso");
    }

    private static JsonObject deleteRoom(JsonObject r) {
        String token = str(r, "token");
        String roomId = str(r, "room_id");

        if (!match(TOKEN, token) || !match(ROOM_ID, roomId))
            return response("delete_room_response", "400", "Dados em formato invalido");

        Account a = auth(token, true);
        if (a == null || !ADMIN_TOKENS.contains(token) || !"admin".equals(a.role))
            return response("delete_room_response", "403", "Permissao insuficiente");

        Room room = ROOMS.get(roomId);
        if (room == null)
            return response("delete_room_response", "404", "Sala nao encontrada");

        synchronized (roomLock(roomId)) {
            room = ROOMS.get(roomId);
            if (room == null)
                return response("delete_room_response", "404", "Sala nao encontrada");

            if (hasActiveReservations(roomId))
                return response("delete_room_response", "409", "Sala possui reservas ativas");

            ROOMS.remove(roomId, room);
        }

        return response("delete_room_response", "200", "Sala removida com sucesso");
    }

    private static JsonObject checkAvailability(JsonObject r) {
        String token = str(r, "token");
        String roomId = str(r, "room_id");
        String dateText = str(r, "date");
        String startText = str(r, "start_time");
        String endText = str(r, "end_time");

        if (!match(TOKEN, token) || !match(ROOM_ID, roomId)
                || !validDateTimeTriple(dateText, startText, endText))
            return response("check_availability_response", "400", "Dados em formato invalido");

        Account a = auth(token, true);
        if (a == null)
            return response("check_availability_response", "401", "Token invalido ou expirado");

        Room room = ROOMS.get(roomId);
        if (room == null)
            return response("check_availability_response", "404", "Sala nao encontrada");

        LocalDate date = parseDate(dateText);
        LocalTime start = parseTime(startText);
        LocalTime end = parseTime(endText);

        boolean available;
        synchronized (roomLock(roomId)) {
            room = ROOMS.get(roomId);
            if (room == null)
                return response("check_availability_response", "404", "Sala nao encontrada");
            available = "active".equals(room.roomStatus) && isAvailable(roomId, date, start, end, null);
        }

        JsonObject rep = response("check_availability_response", "200", "Consulta realizada com sucesso");
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

        if (!match(TOKEN, token) || !match(ROOM_ID, roomId)
                || !validDateTimeTriple(dateText, startText, endText)
                || !match(TOPIC, topic) || !match(PARTICIPANTS, participantsText))
            return response("create_reservation_response", "400", "Dados da reserva em formato invalido");

        LocalDate date = parseDate(dateText);
        if (date.isBefore(LocalDate.now(ZONE)))
            return response("create_reservation_response", "400", "Dados da reserva em formato invalido");

        Account owner = auth(token, true);
        if (owner == null)
            return response("create_reservation_response", "401", "Token invalido ou expirado");

        Room room = ROOMS.get(roomId);
        if (room == null)
            return response("create_reservation_response", "404", "Sala nao encontrada");

        LocalTime start = parseTime(startText);
        LocalTime end = parseTime(endText);
        int participants = Integer.parseInt(participantsText);

        Object lock = roomLock(roomId);
        synchronized (lock) {
            room = ROOMS.get(roomId);
            if (room == null)
                return response("create_reservation_response", "404", "Sala nao encontrada");

            if (participants > room.capacity)
                return response("create_reservation_response", "400", "Dados da reserva em formato invalido");

            if (!"active".equals(room.roomStatus))
                return response("create_reservation_response", "409", "Sala indisponivel para reserva");

            if (!isAvailable(roomId, date, start, end, null))
                return response("create_reservation_response", "409", "Sala ja reservada no periodo solicitado");

            String id = String.valueOf(NEXT_RESERVATION_ID.getAndIncrement());
            Reservation reservation = new Reservation(
                    id, room, owner, date, start, end, topic, participants, now()
            );
            RESERVATIONS.put(id, reservation);

            JsonObject rep = response("create_reservation_response", "201", "Reserva realizada com sucesso");
            rep.addProperty("reservation_id", id);
            return rep;
        }
    }

    private static JsonObject listReservations(JsonObject r) {
        String token = str(r, "token");
        boolean hasScope = r.has("scope");
        boolean hasDate = r.has("date");
        boolean hasRoom = r.has("room_id");

        String scope = hasScope ? str(r, "scope") : "mine";
        String dateText = hasDate ? str(r, "date") : null;
        String roomId = hasRoom ? str(r, "room_id") : null;

        if (!match(TOKEN, token)
                || (hasScope && !("mine".equals(scope) || "all".equals(scope)))
                || (hasDate && !validDate(dateText))
                || (hasRoom && !match(ROOM_ID, roomId)))
            return response("list_reservations_response", "400", "Filtros em formato invalido");

        Account requester = auth(token, true);
        if (requester == null)
            return response("list_reservations_response", "401", "Token invalido ou expirado");

        if ("all".equals(scope) && !"admin".equals(requester.role))
            return response("list_reservations_response", "403", "Permissao insuficiente");

        LocalDate date = hasDate ? parseDate(dateText) : null;

        List<Reservation> list = new ArrayList<>(RESERVATIONS.values());
        list.sort(Comparator.comparingLong(res -> Long.parseLong(res.id)));

        JsonArray arr = new JsonArray();
        for (Reservation res : list) {
            if ("mine".equals(scope) && res.owner != requester) continue;
            if (date != null && !date.equals(res.date)) continue;
            if (roomId != null && !roomId.equals(res.room.id)) continue;
            arr.add(reservationJson(res, false));
        }

        JsonObject rep = response("list_reservations_response", "200", "Consulta realizada com sucesso");
        rep.addProperty("count", String.valueOf(arr.size()));
        rep.add("reservations", arr);
        return rep;
    }

    private static JsonObject readReservation(JsonObject r) {
        String token = str(r, "token");
        String reservationId = str(r, "reservation_id");

        if (!match(TOKEN, token) || !match(RESERVATION_ID, reservationId))
            return response("read_reservation_response", "400", "Dados em formato invalido");

        Account requester = auth(token, true);
        if (requester == null)
            return response("read_reservation_response", "401", "Token invalido ou expirado");

        Reservation res = RESERVATIONS.get(reservationId);
        if (res == null)
            return response("read_reservation_response", "404", "Reserva nao encontrada");

        if (res.owner != requester && !"admin".equals(requester.role))
            return response("read_reservation_response", "403", "Permissao insuficiente");

        JsonObject rep = response("read_reservation_response", "200", "Consulta realizada com sucesso");
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

        if (r.has("room_id") || r.has("date")
                || !match(TOKEN, token) || !match(RESERVATION_ID, reservationId)
                || !update(TIME, startText) || !update(TIME, endText)
                || !update(TOPIC, topic) || !update(PARTICIPANTS, participantsText))
            return response("update_reservation_response", "400", "Dados da reserva em formato invalido");

        Account requester = auth(token, true);
        if (requester == null)
            return response("update_reservation_response", "401", "Token invalido ou expirado");

        Reservation res = RESERVATIONS.get(reservationId);
        if (res == null)
            return response("update_reservation_response", "404", "Reserva nao encontrada");

        if (res.owner != requester)
            return response("update_reservation_response", "403", "Permissao insuficiente");

        if (reservationEnded(res))
            return response("update_reservation_response", "409", "Reserva ja encerrada");

        Object lock = roomLock(res.room.id);
        synchronized (lock) {
            res = RESERVATIONS.get(reservationId);
            if (res == null)
                return response("update_reservation_response", "404", "Reserva nao encontrada");
            if (reservationEnded(res))
                return response("update_reservation_response", "409", "Reserva ja encerrada");

            LocalTime newStart = startText.isEmpty() ? res.start : parseTime(startText);
            LocalTime newEnd = endText.isEmpty() ? res.end : parseTime(endText);
            String newTopic = topic.isEmpty() ? res.topic : topic;
            int newParticipants = participantsText.isEmpty() ? res.participants : Integer.parseInt(participantsText);

            if (!newEnd.isAfter(newStart) || newParticipants > res.room.capacity)
                return response("update_reservation_response", "400", "Dados da reserva em formato invalido");

            if (!isAvailable(res.room.id, res.date, newStart, newEnd, res.id))
                return response("update_reservation_response", "409", "Sala ja reservada no periodo solicitado");

            res.start = newStart;
            res.end = newEnd;
            res.topic = newTopic;
            res.participants = newParticipants;
        }

        return response("update_reservation_response", "200", "Reserva atualizada com sucesso");
    }

    private static JsonObject deleteReservation(JsonObject r) {
        String token = str(r, "token");
        String reservationId = str(r, "reservation_id");

        if (!match(TOKEN, token) || !match(RESERVATION_ID, reservationId))
            return response("delete_reservation_response", "400", "Dados em formato invalido");

        Account requester = auth(token, true);
        if (requester == null)
            return response("delete_reservation_response", "401", "Token invalido ou expirado");

        Reservation res = RESERVATIONS.get(reservationId);
        if (res == null)
            return response("delete_reservation_response", "404", "Reserva nao encontrada");

        if (res.owner != requester && !"admin".equals(requester.role))
            return response("delete_reservation_response", "403", "Permissao insuficiente");

        synchronized (roomLock(res.room.id)) {
            res = RESERVATIONS.get(reservationId);
            if (res == null)
                return response("delete_reservation_response", "404", "Reserva nao encontrada");

            if (reservationEnded(res))
                return response("delete_reservation_response", "409", "Reserva ja encerrada");

            RESERVATIONS.remove(reservationId, res);
        }

        return response("delete_reservation_response", "200", "Reserva cancelada com sucesso");
    }

    private static JsonObject userDetail(String op, Account a) {
        JsonObject rep = response(op, "200", "Consulta realizada com sucesso");
        rep.addProperty("user", a.user);
        rep.addProperty("email", a.email);
        rep.addProperty("role", a.role);
        rep.addProperty("created_at", a.createdAt);
        return rep;
    }

    private static Account auth(String token, boolean renew) {
        Account a = BY_TOKEN.get(token);
        if (a == null) return null;

        synchronized (a) {
            long t = System.currentTimeMillis();
            if (!token.equals(a.token) || expired(a, t)) {
                if (token.equals(a.token)) invalidate(a);
                else BY_TOKEN.remove(token, a);
                return null;
            }
            if (renew) a.lastUse = t;
            return a;
        }
    }

    private static boolean expired(Account a, long t) {
        return t - a.lastUse > TOKEN_TIMEOUT_MS;
    }

    private static void invalidate(Account a) {
        synchronized (a) {
            if (a.token != null) {
                ADMIN_TOKENS.remove(a.token);
                BY_TOKEN.remove(a.token, a);
                a.token = null;
                a.lastUse = 0L;
            }
        }
    }

    private static int adminCount() {
        int n = 0;
        for (Account a : BY_USER.values()) {
            if ("admin".equals(a.role)) n++;
        }
        return n;
    }

    private static Object roomLock(String roomId) {
        return ROOM_LOCKS.computeIfAbsent(roomId, k -> new Object());
    }

    private static boolean roomNameExists(String name, String exceptRoomId) {
        for (Room room : ROOMS.values()) {
            if (name.equals(room.name) && (exceptRoomId == null || !exceptRoomId.equals(room.id)))
                return true;
        }
        return false;
    }

    private static boolean futureReservationExceedsCapacity(String roomId, int newCapacity) {
        for (Reservation res : RESERVATIONS.values()) {
            if (roomId.equals(res.room.id) && !reservationEnded(res) && res.participants > newCapacity)
                return true;
        }
        return false;
    }

    private static boolean hasActiveReservations(String roomId) {
        for (Reservation res : RESERVATIONS.values()) {
            if (roomId.equals(res.room.id) && !reservationEnded(res)) return true;
        }
        return false;
    }

    private static void deleteAllReservationsOf(Account account) {
        List<Reservation> toDelete = new ArrayList<>();
        for (Reservation res : RESERVATIONS.values()) {
            if (res.owner == account) toDelete.add(res);
        }
        for (Reservation res : toDelete) {
            synchronized (roomLock(res.room.id)) {
                RESERVATIONS.remove(res.id, res);
            }
        }
    }

    private static boolean isAvailable(String roomId, LocalDate date, LocalTime start, LocalTime end, String ignoreReservationId) {
        for (Reservation res : RESERVATIONS.values()) {
            if (!roomId.equals(res.room.id)) continue;
            if (!date.equals(res.date)) continue;
            if (ignoreReservationId != null && ignoreReservationId.equals(res.id)) continue;

            if (start.isBefore(res.end) && end.isAfter(res.start)) return false;
        }
        return true;
    }

    private static boolean reservationEnded(Reservation res) {
        LocalDateTime finish = LocalDateTime.of(res.date, res.end);
        return !finish.isAfter(LocalDateTime.now(ZONE));
    }

    private static JsonObject roomJson(Room room) {
        JsonObject j = new JsonObject();
        copyRoomFields(j, room);
        return j;
    }

    private static void copyRoomFields(JsonObject j, Room room) {
        j.addProperty("room_id", room.id);
        j.addProperty("name", room.name);
        j.addProperty("capacity", String.valueOf(room.capacity));
        j.addProperty("location", room.location);

        JsonArray resources = new JsonArray();
        for (String resource : room.resources) resources.add(resource);
        j.add("resources", resources);

        j.addProperty("room_status", room.roomStatus);
    }

    private static JsonObject reservationJson(Reservation res, boolean includeCreatedAt) {
        JsonObject j = new JsonObject();
        copyReservationFields(j, res, includeCreatedAt);
        return j;
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

    private static String str(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return null;
        JsonElement e = o.get(k);
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static List<String> resources(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull() || !o.get(k).isJsonArray()) return null;

        List<String> list = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(k)) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
            list.add(e.getAsString());
        }
        return list;
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

    private static boolean validTime(String s) {
        if (!match(TIME, s)) return false;
        try {
            LocalTime.parse(s, HH_MM);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean validDateTimeTriple(String date, String start, String end) {
        if (!validDate(date) || !validTime(start) || !validTime(end)) return false;
        return parseTime(end).isAfter(parseTime(start));
    }

    private static LocalDate parseDate(String s) {
        return LocalDate.parse(s);
    }

    private static LocalTime parseTime(String s) {
        return LocalTime.parse(s, HH_MM);
    }

    private static JsonObject response(String op, String status, String message) {
        JsonObject j = new JsonObject();
        j.addProperty("op", op);
        j.addProperty("status", status);
        j.addProperty("message", message);
        return j;
    }

    private static void send(BufferedWriter out, JsonObject j, String who) throws IOException {
        String json = GSON.toJson(j);

        if ((json + "\n").getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            String op = str(j, "op");
            String fallbackOp = (op == null || "error".equals(op)) ? "error" : op;
            JsonObject fallback = "error".equals(fallbackOp)
                    ? response("error", "400", "Mensagem excede o tamanho maximo")
                    : response(fallbackOp, "500", "Erro interno do servidor");
            json = GSON.toJson(fallback);
        }

        System.out.println("[" + who + "] SERVIDOR -> CLIENTE | JSON ENVIADO: " + json);

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
        volatile String roomStatus;

        Room(String id, String name, int capacity, String location, List<String> resources, String roomStatus) {
            this.id = id;
            this.name = name;
            this.capacity = capacity;
            this.location = location;
            this.resources = new ArrayList<>(resources);
            this.roomStatus = roomStatus;
        }
    }

    private static class Reservation {
        final String id;
        final Room room;
        final Account owner;
        final LocalDate date;
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