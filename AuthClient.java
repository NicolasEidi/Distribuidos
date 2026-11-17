import com.google.gson.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente CLI TCP/JSON do projeto Reserva de Salas.
 * Mantem token e role apenas em memoria durante a execucao.
 */
public class AuthClient {
    private static final Gson GSON = new Gson();
    private static final int MAX_BYTES = 8192;

    private final BufferedReader console;
    private String host;
    private int port;

    private Socket socket;
    private InputStream in;
    private BufferedWriter out;

    private String token;
    private String role;

    private AuthClient(BufferedReader console, String host, int port) {
        this.console = console;
        this.host = host;
        this.port = port;
    }

    public static void main(String[] args) throws IOException {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        System.out.print("IP do servidor: ");
        String ip = console.readLine();
        System.out.print("Porta do servidor: ");

        int port;
        try {
            port = Integer.parseInt(console.readLine());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Porta invalida. Use um numero entre 1 e 65535.");
            return;
        }

        AuthClient client = new AuthClient(console, ip, port);
        try {
            client.connect(false);
            client.mainMenu();
        } catch (UnknownHostException e) {
            System.err.println("Servidor nao encontrado: " + ip);
        } catch (IOException e) {
            System.err.println("Erro de conexao/I-O: " + e.getMessage());
        } finally {
            client.closeConnection();
        }
    }

    private void mainMenu() throws IOException {
        boolean run = true;

        while (run) {
            System.out.println("\n=== RESERVA DE SALAS (v2.6.0) ===");
            System.out.println("1 - Cadastrar (register)");
            System.out.println("2 - Entrar (login)");
            System.out.println("3 - Sair da sessao (logout)");
            System.out.println("4 - Proprio cadastro");
            System.out.println("5 - Administrador [ADM]");
            System.out.println("6 - Salas");
            System.out.println("7 - Reservas");
            System.out.println("8 - Trocar servidor");
            System.out.println("0 - Encerrar cliente");
            System.out.println("Servidor atual: " + host + ":" + port);
            System.out.println(token == null
                    ? "Sessao atual: sem login"
                    : "Sessao atual: autenticado como role=" + role);
            System.out.print("Opcao: ");

            String op = console.readLine();
            if (op == null) break;

            switch (op) {
                case "1" -> register();
                case "2" -> login();
                case "3" -> logout();
                case "4" -> ownMenu();
                case "5" -> adminMenu();
                case "6" -> roomsMenu();
                case "7" -> reservationsMenu();
                case "8" -> switchServer();
                case "0" -> {
                    if (token != null) {
                        logout();
                        if (token != null) {
                            System.out.println("Logout nao foi concluido; o cliente permanecera aberto para nao perder o token local.");
                            break;
                        }
                    }
                    run = false;
                }
                default -> System.out.println("Opcao invalida.");
            }
        }

        System.out.println("Cliente encerrado.");
    }

    private void switchServer() throws IOException {
        if (token != null) {
            System.out.println("Faca logout antes de trocar de servidor.");
            System.out.println("O token atual pertence ao servidor " + host + ":" + port + ".");
            return;
        }

        System.out.print("Novo IP do servidor: ");
        String newHost = console.readLine();
        if (newHost == null || newHost.isBlank()) {
            System.out.println("IP invalido. O servidor atual foi mantido.");
            return;
        }

        System.out.print("Nova porta do servidor: ");
        String portText = console.readLine();
        int newPort;
        try {
            newPort = Integer.parseInt(portText);
            if (newPort < 1 || newPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.out.println("Porta invalida. O servidor atual foi mantido.");
            return;
        }

        Socket newSocket = new Socket();
        InputStream newIn = null;
        BufferedWriter newOut = null;

        try {
            newSocket.connect(new InetSocketAddress(newHost, newPort), 5000);
            newIn = newSocket.getInputStream();
            newOut = new BufferedWriter(new OutputStreamWriter(
                    newSocket.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            try { newSocket.close(); } catch (IOException ignored) {}
            System.out.println("Nao foi possivel conectar a " + newHost + ":" + newPort + ".");
            System.out.println("Servidor atual mantido: " + host + ":" + port + ".");
            return;
        }

        String oldServer = host + ":" + port;
        closeConnection();

        host = newHost;
        port = newPort;
        socket = newSocket;
        in = newIn;
        out = newOut;

        System.out.println("Servidor alterado de " + oldServer + " para " + host + ":" + port + ".");
    }

    private void register() throws IOException {
        System.out.print("Email: ");
        String email = console.readLine();
        System.out.print("Usuario: ");
        String user = console.readLine();
        System.out.print("Senha: ");
        String password = console.readLine();

        JsonObject r = req("register");
        r.addProperty("email", email);
        r.addProperty("user", user);
        r.addProperty("password", password);
        show(send(r));
    }

    private void login() throws IOException {
        if (token != null) {
            System.out.println("Ja existe uma sessao neste cliente. Faca logout primeiro.");
            return;
        }

        System.out.print("Email: ");
        String email = console.readLine();
        System.out.print("Senha: ");
        String password = console.readLine();

        JsonObject r = req("login");
        r.addProperty("email", email);
        r.addProperty("password", password);

        JsonObject rep = send(r);
        show(rep);

        if (success(rep, "login_response", "200")) {
            token = str(rep, "token");
            role = str(rep, "role");
            System.out.println("Token recebido e guardado em memoria.");
        }
    }

    private void logout() {
        if (!logged()) return;

        try {
            JsonObject rep = send(authReq("logout"));
            show(rep);

            if (success(rep, "logout_response", "200")
                    || (success(rep, "logout_response", "401"))) {
                clearSession("Token descartado da memoria do cliente.");
            }
        } catch (IOException e) {
            System.err.println("Logout nao concluido: " + e.getMessage());
            System.err.println("O token foi mantido em memoria; tente logout novamente.");
        }
    }

    private void ownMenu() throws IOException {
        if (!logged()) return;

        boolean run = true;
        while (run && token != null) {
            System.out.println("\n=== PROPRIO CADASTRO ===");
            System.out.println("1 - Ler meu cadastro (read_user)");
            System.out.println("2 - Atualizar meu cadastro (update_user)");
            System.out.println("3 - Remover meu cadastro (delete_user)");
            System.out.println("0 - Voltar");
            System.out.print("Opcao: ");

            String op = console.readLine();
            if (op == null) return;

            switch (op) {
                case "1" -> readUser();
                case "2" -> updateUser();
                case "3" -> {
                    deleteUser();
                    if (token == null) run = false;
                }
                case "0" -> run = false;
                default -> System.out.println("Opcao invalida.");
            }
        }
    }

    private void readUser() throws IOException {
        JsonObject rep = send(authReq("read_user"));
        show(rep);
        generic401(rep);

        if (success(rep, "read_user_response", "200")) {
            System.out.println("Usuario: " + str(rep, "user"));
            System.out.println("Email: " + str(rep, "email"));
            System.out.println("Role: " + str(rep, "role"));
            System.out.println("Criado em: " + str(rep, "created_at"));
            role = str(rep, "role");
        }
    }

    private void updateUser() throws IOException {
        System.out.println("Deixe vazio para nao alterar.");
        System.out.print("Novo usuario: ");
        String user = console.readLine();
        System.out.print("Nova senha: ");
        String password = console.readLine();

        JsonObject r = authReq("update_user");
        r.addProperty("user", nvl(user));
        r.addProperty("password", nvl(password));

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void deleteUser() throws IOException {
        System.out.print("Confirme sua senha: ");
        String password = console.readLine();

        JsonObject r = authReq("delete_user");
        r.addProperty("password", password);

        JsonObject rep = send(r);
        show(rep);

        if (success(rep, "delete_user_response", "200")) {
            clearSession("Cadastro removido; token descartado da memoria do cliente.");
        } else if (status(rep, "401")) {
            generic401(rep);
        } else if (success(rep, "delete_user_response", "403")
                && "Senha incorreta".equals(str(rep, "message"))) {
            System.out.println("Senha incorreta. A sessao continua ativa.");
        }
    }

    private void adminMenu() throws IOException {
        if (!logged()) return;

        boolean run = true;
        while (run && token != null) {
            System.out.println("\n=== ADMINISTRADOR (ADM) ===");
            System.out.println("1 - Listar usuarios (admin_list_users)");
            System.out.println("2 - Ler usuario (admin_read_user)");
            System.out.println("3 - Atualizar usuario (admin_update_user)");
            System.out.println("4 - Remover usuario (admin_delete_user)");
            System.out.println("0 - Voltar");
            System.out.print("Opcao: ");

            String op = console.readLine();
            if (op == null) return;

            switch (op) {
                case "1" -> adminList();
                case "2" -> adminRead();
                case "3" -> adminUpdate();
                case "4" -> adminDelete();
                case "0" -> run = false;
                default -> System.out.println("Opcao invalida.");
            }
        }
    }

    private void adminList() throws IOException {
        JsonObject rep = send(authReq("admin_list_users"));
        show(rep);
        generic401(rep);

        if (success(rep, "admin_list_users_response", "200")) {
            System.out.println("Quantidade: " + str(rep, "count"));
            JsonArray arr = array(rep, "users");
            if (arr != null) {
                for (JsonElement e : arr) {
                    JsonObject u = e.getAsJsonObject();
                    System.out.println("- user=" + str(u, "user")
                            + " | email=" + str(u, "email")
                            + " | role=" + str(u, "role"));
                }
            }
        }
    }

    private void adminRead() throws IOException {
        System.out.print("Usuario alvo (target_user): ");
        String target = console.readLine();

        JsonObject r = authReq("admin_read_user");
        r.addProperty("target_user", target);

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void adminUpdate() throws IOException {
        System.out.print("Usuario alvo (target_user): ");
        String target = console.readLine();
        System.out.println("Deixe vazio para nao alterar.");
        System.out.print("Novo user: ");
        String user = console.readLine();
        System.out.print("Nova senha: ");
        String password = console.readLine();
        System.out.print("Novo role (user/admin): ");
        String newRole = console.readLine();

        JsonObject r = authReq("admin_update_user");
        r.addProperty("target_user", target);
        r.addProperty("user", nvl(user));
        r.addProperty("password", nvl(password));
        r.addProperty("role", nvl(newRole));

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void adminDelete() throws IOException {
        System.out.print("Usuario alvo (target_user): ");
        String target = console.readLine();

        JsonObject r = authReq("admin_delete_user");
        r.addProperty("target_user", target);

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void roomsMenu() throws IOException {
        if (!logged()) return;

        boolean run = true;
        while (run && token != null) {
            System.out.println("\n=== SALAS ===");
            System.out.println("1 - Cadastrar sala (create_room) [admin]");
            System.out.println("2 - Listar salas (list_rooms)");
            System.out.println("3 - Ler sala (read_room)");
            System.out.println("4 - Atualizar sala (update_room) [admin]");
            System.out.println("5 - Remover sala (delete_room) [admin]");
            System.out.println("0 - Voltar");
            System.out.print("Opcao: ");

            String op = console.readLine();
            if (op == null) return;

            switch (op) {
                case "1" -> createRoom();
                case "2" -> listRooms();
                case "3" -> readRoom();
                case "4" -> updateRoom();
                case "5" -> deleteRoom();
                case "0" -> run = false;
                default -> System.out.println("Opcao invalida.");
            }
        }
    }

    private void createRoom() throws IOException {
        System.out.print("Nome da sala: ");
        String name = console.readLine();
        System.out.print("Capacidade: ");
        String capacity = console.readLine();
        System.out.print("Localizacao: ");
        String location = console.readLine();
        System.out.print("Recursos separados por virgula (vazio = []): ");
        String resourcesText = console.readLine();

        JsonObject r = authReq("create_room");
        r.addProperty("name", name);
        r.addProperty("capacity", capacity);
        r.addProperty("location", location);
        r.add("resources", resourcesArray(resourcesText));

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void listRooms() throws IOException {
        System.out.println("Filtros opcionais. Para horario, date/start_time/end_time devem ser enviados juntos.");
        System.out.print("Data AAAA-MM-DD (vazio = omitir): ");
        String date = console.readLine();
        System.out.print("Hora inicial HH:mm (vazio = omitir): ");
        String start = console.readLine();
        System.out.print("Hora final HH:mm (vazio = omitir): ");
        String end = console.readLine();
        System.out.print("Capacidade minima (vazio = omitir): ");
        String minCapacity = console.readLine();

        JsonObject r = authReq("list_rooms");
        addOptionalString(r, "date", date);
        addOptionalString(r, "start_time", start);
        addOptionalString(r, "end_time", end);
        addOptionalString(r, "min_capacity", minCapacity);

        JsonObject rep = send(r);
        show(rep);

        // LOG FORMATADO NO CLI PARA SALAS
        if (success(rep, "list_rooms_response", "200")) {
            System.out.println("\n--- [LOG CLI] LISTAGEM DE SALAS ---");
            System.out.println("Total de salas: " + str(rep, "count"));
            JsonArray arr = array(rep, "rooms");
            if (arr != null) {
                int i = 1;
                for (JsonElement e : arr) {
                    JsonObject room = e.getAsJsonObject();
                    System.out.println(i++ + ". ID: " + str(room, "room_id")
                            + " | Nome: " + str(room, "name")
                            + " | Capacidade: " + str(room, "capacity")
                            + " | Local: " + str(room, "location")
                            + " | Status: " + str(room, "room_status")
                            + (room.has("available") ? " | Disponível: " + str(room, "available") : ""));
                }
            }
            System.out.println("-----------------------------------\n");
        }

        generic401(rep);
    }

    private void readRoom() throws IOException {
        System.out.print("room_id: ");
        String roomId = console.readLine();

        JsonObject r = authReq("read_room");
        r.addProperty("room_id", roomId);

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void updateRoom() throws IOException {
        System.out.print("room_id: ");
        String roomId = console.readLine();
        System.out.print("Novo nome: ");
        String name = console.readLine();
        System.out.print("Nova capacidade: ");
        String capacity = console.readLine();
        System.out.print("Nova localizacao: ");
        String location = console.readLine();
        System.out.print("Lista completa de recursos, separados por virgula: ");
        String resourcesText = console.readLine();
        System.out.print("Novo room_status (active/inactive): ");
        String roomStatus = console.readLine();

        JsonObject r = authReq("update_room");
        r.addProperty("room_id", roomId);
        r.addProperty("name", nvl(name));
        r.addProperty("capacity", nvl(capacity));
        r.addProperty("location", nvl(location));
        r.add("resources", resourcesArray(resourcesText));
        r.addProperty("room_status", nvl(roomStatus));

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void deleteRoom() throws IOException {
        System.out.print("room_id: ");
        String roomId = console.readLine();

        JsonObject r = authReq("delete_room");
        r.addProperty("room_id", roomId);

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void reservationsMenu() throws IOException {
        if (!logged()) return;

        boolean run = true;
        while (run && token != null) {
            System.out.println("\n=== RESERVAS ===");
            System.out.println("1 - Consultar disponibilidade (check_availability)");
            System.out.println("2 - Criar reserva (create_reservation)");
            System.out.println("3 - Listar reservas (list_reservations)");
            System.out.println("4 - Ler reserva (read_reservation)");
            System.out.println("5 - Atualizar reserva (update_reservation)");
            System.out.println("6 - Cancelar reserva (delete_reservation)");
            System.out.println("0 - Voltar");
            System.out.print("Opcao: ");

            String op = console.readLine();
            if (op == null) return;

            switch (op) {
                case "1" -> checkAvailability();
                case "2" -> createReservation();
                case "3" -> listReservations();
                case "4" -> readReservation();
                case "5" -> updateReservation();
                case "6" -> deleteReservation();
                case "0" -> run = false;
                default -> System.out.println("Opcao invalida.");
            }
        }
    }

    private void checkAvailability() throws IOException {
        System.out.print("room_id: ");
        String roomId = console.readLine();
        System.out.print("Data AAAA-MM-DD: ");
        String date = console.readLine();
        System.out.print("Hora inicial HH:mm: ");
        String start = console.readLine();
        System.out.print("Hora final HH:mm: ");
        String end = console.readLine();

        JsonObject r = authReq("check_availability");
        r.addProperty("room_id", roomId);
        r.addProperty("date", date);
        r.addProperty("start_time", start);
        r.addProperty("end_time", end);

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void createReservation() throws IOException {
        System.out.print("room_id: ");
        String roomId = console.readLine();
        System.out.print("Data AAAA-MM-DD: ");
        String date = console.readLine();
        System.out.print("Hora inicial HH:mm: ");
        String start = console.readLine();
        System.out.print("Hora final HH:mm: ");
        String end = console.readLine();
        System.out.print("Assunto (topic): ");
        String topic = console.readLine();
        System.out.print("Participantes: ");
        String participants = console.readLine();

        JsonObject r = authReq("create_reservation");
        r.addProperty("room_id", roomId);
        r.addProperty("date", date);
        r.addProperty("start_time", start);
        r.addProperty("end_time", end);
        r.addProperty("topic", topic);
        r.addProperty("participants", participants);

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void listReservations() throws IOException {
        System.out.print("scope (mine/all, vazio = omitir): ");
        String scope = console.readLine();
        System.out.print("Data AAAA-MM-DD (vazio = omitir): ");
        String date = console.readLine();
        System.out.print("room_id (vazio = omitir): ");
        String roomId = console.readLine();

        JsonObject r = authReq("list_reservations");
        addOptionalString(r, "scope", scope);
        addOptionalString(r, "date", date);
        addOptionalString(r, "room_id", roomId);

        JsonObject rep = send(r);
        show(rep);

        // LOG FORMATADO NO CLI PARA RESERVAS
        if (success(rep, "list_reservations_response", "200")) {
            System.out.println("\n--- [LOG CLI] LISTAGEM DE RESERVAS ---");
            System.out.println("Total de reservas: " + str(rep, "count"));
            JsonArray arr = array(rep, "reservations");
            if (arr != null) {
                int i = 1;
                for (JsonElement e : arr) {
                    JsonObject res = e.getAsJsonObject();
                    System.out.println(i++ + ". ID Reserva: " + str(res, "reservation_id")
                            + " | Sala: " + str(res, "name") + " (ID: " + str(res, "room_id") + ")"
                            + " | Usuário: " + str(res, "user")
                            + " | Data: " + str(res, "date")
                            + " | Horário: " + str(res, "start_time") + " às " + str(res, "end_time")
                            + " | Assunto: " + str(res, "topic")
                            + " | Participantes: " + str(res, "participants"));
                }
            }
            System.out.println("-------------------------------------\n");
        }

        generic401(rep);
    }

    private void readReservation() throws IOException {
        System.out.print("reservation_id: ");
        String reservationId = console.readLine();

        JsonObject r = authReq("read_reservation");
        r.addProperty("reservation_id", reservationId);

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void updateReservation() throws IOException {
        System.out.print("reservation_id: ");
        String reservationId = console.readLine();
        System.out.print("Nova hora inicial HH:mm (vazio = manter): ");
        String start = console.readLine();
        System.out.print("Nova hora final HH:mm (vazio = manter): ");
        String end = console.readLine();
        System.out.print("Novo assunto (vazio = manter): ");
        String topic = console.readLine();
        System.out.print("Novo numero de participantes (vazio = manter): ");
        String participants = console.readLine();

        JsonObject r = authReq("update_reservation");
        r.addProperty("reservation_id", reservationId);
        r.addProperty("start_time", nvl(start));
        r.addProperty("end_time", nvl(end));
        r.addProperty("topic", nvl(topic));
        r.addProperty("participants", nvl(participants));

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private void deleteReservation() throws IOException {
        System.out.print("reservation_id: ");
        String reservationId = console.readLine();

        JsonObject r = authReq("delete_reservation");
        r.addProperty("reservation_id", reservationId);

        JsonObject rep = send(r);
        show(rep);
        generic401(rep);
    }

    private boolean logged() {
        if (token == null) {
            System.out.println("Esta operacao exige login.");
            return false;
        }
        return true;
    }

    private JsonObject req(String op) {
        JsonObject r = new JsonObject();
        r.addProperty("op", op);
        return r;
    }

    private JsonObject authReq(String op) {
        JsonObject r = req(op);
        r.addProperty("token", token);
        return r;
    }

    private void generic401(JsonObject rep) {
        if (status(rep, "401")) {
            clearSession("Token invalido/expirado descartado da memoria do cliente.");
        }
    }

    private void clearSession(String msg) {
        token = null;
        role = null;
        System.out.println(msg);
    }

    private JsonObject send(JsonObject r) throws IOException {
        String json = GSON.toJson(r);
        if ((json + "\n").getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            System.err.println("A requisicao excede 8192 bytes e nao sera enviada.");
            return null;
        }

        IOException firstFailure = null;

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected()) {
                    connect(attempt > 0);
                }
                return sendOnce(json);
            } catch (ProtocolIOException e) {
                throw e;
            } catch (IOException e) {
                if (attempt == 1) {
                    if (firstFailure != null) e.addSuppressed(firstFailure);
                    throw e;
                }

                firstFailure = e;
                System.out.println("Conexao perdida/fechada. Reconectando ao servidor...");
                closeConnection();
                connect(true);
            }
        }

        throw new IOException("Nao foi possivel concluir a requisicao.");
    }

    private JsonObject sendOnce(String json) throws IOException {
        System.out.println("[CLIENTE -> SERVIDOR " + host + ":" + port + "] JSON ENVIADO: " + json);

        out.write(json);
        out.write('\n');
        out.flush();

        Line line = readLine(in);
        if (line == null) throw new EOFException("Servidor fechou a conexao sem resposta.");
        if (line.tooLong) throw new ProtocolIOException("Resposta do servidor excedeu 8192 bytes.");

        System.out.println("[SERVIDOR " + host + ":" + port + " -> CLIENTE] JSON RECEBIDO: " + line.text);

        try {
            JsonElement e = JsonParser.parseString(line.text);
            if (!e.isJsonObject())
                throw new ProtocolIOException("Resposta do servidor nao e um objeto JSON.");
            return e.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new ProtocolIOException("Servidor enviou JSON invalido: " + e.getMessage(), e);
        }
    }

    private void connect(boolean reconnecting) throws IOException {
        closeConnection();

        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        socket = s;
        in = s.getInputStream();
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

        System.out.println((reconnecting ? "Reconectado" : "Conectado")
                + " ao servidor " + host + ":" + port + ".");
    }

    private void closeConnection() {
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}

        out = null;
        in = null;
        socket = null;
    }

    private static void show(JsonObject rep) {
        if (rep == null) return;
        System.out.println("Resposta JSON: " + GSON.toJson(rep));
    }

    private static boolean success(JsonObject r, String op, String status) {
        return r != null && op.equals(str(r, "op")) && status.equals(str(r, "status"));
    }

    private static boolean status(JsonObject r, String s) {
        return r != null && s.equals(str(r, "status"));
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

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static void addOptionalString(JsonObject r, String key, String value) {
        if (value != null && !value.isEmpty()) r.addProperty(key, value);
    }

    private static JsonArray resourcesArray(String line) {
        JsonArray arr = new JsonArray();
        if (line == null || line.trim().isEmpty()) return arr;

        String[] parts = line.split(",", -1);
        for (String part : parts) arr.add(part.trim());
        return arr;
    }

    private static Line readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n = 0;
        boolean tooLong = false;

        while (true) {
            int x = in.read();
            if (x == -1) return null;
            n++;
            if (n > MAX_BYTES) tooLong = true;
            if (x == '\n') break;
            if (!tooLong) b.write(x);
        }

        if (tooLong) return new Line(null, true);

        byte[] raw = b.toByteArray();
        int len = raw.length;
        if (len > 0 && raw[len - 1] == '\r') len--;
        return new Line(new String(raw, 0, len, StandardCharsets.UTF_8), false);
    }

    private static class ProtocolIOException extends IOException {
        ProtocolIOException(String message) { super(message); }
        ProtocolIOException(String message, Throwable cause) { super(message, cause); }
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