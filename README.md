# 🏢 Reserva de Salas — Cliente/Servidor TCP com JSON

Sistema de reserva de salas em **Java**, com arquitetura **cliente-servidor** sobre **sockets TCP**. As mensagens são trocadas em **JSON** (uma por linha). O projeto foi desenvolvido como trabalho acadêmico e implementa o protocolo **Reserva de Salas v2.6.0**: autenticação por token, controle de acesso por papel (`user` / `admin`), gerenciamento de salas e reservas com detecção de conflito de horário.

---

## ✨ Funcionalidades

### 🔐 Autenticação e sessão
- Cadastro de usuários (`register`) e login (`login`) com **token de sessão** de 64 caracteres hexadecimais gerado com `SecureRandom`
- Sessão expira após **30 minutos de inatividade** (o prazo é renovado a cada uso)
- Apenas **uma sessão ativa por usuário**
- Senhas armazenadas como hash **SHA-256** e comparadas em tempo constante

### 👤 Próprio cadastro
- Consultar, atualizar (usuário/senha) e remover a própria conta
- A remoção exige confirmação de senha e apaga as reservas do usuário

### 🛡️ Administração
- Listar, consultar, atualizar e remover qualquer usuário
- Promover ou rebaixar usuários entre `user` e `admin`
- Proteção contra remoção ou rebaixamento do **último administrador**
- Lista de tokens autorizados (*whitelist*) para operações administrativas

### 🚪 Salas
- CRUD completo de salas (criação, edição e remoção restritas a admin)
- Atributos: nome, capacidade, localização, lista de recursos e status (`active` / `inactive`)
- Listagem com filtros por **capacidade mínima** e por **disponibilidade em data/horário**
- Bloqueio de remoção de salas com reservas ativas e de redução de capacidade abaixo das reservas futuras

### 📅 Reservas
- Consulta de disponibilidade de uma sala em um intervalo
- Criação, leitura, atualização, listagem e cancelamento de reservas
- **Detecção de sobreposição de horários** na mesma sala e data
- Validação de capacidade, de datas passadas e de reservas já encerradas
- Listagem por escopo (`mine` para as próprias reservas, `all` só para admin), data e sala

---

## 🧱 Arquitetura

```
┌──────────────┐     JSON + '\n' (TCP)     ┌──────────────────────┐
│  AuthClient  │ ────────────────────────▶ │      AuthServer      │
│   (CLI)      │ ◀──────────────────────── │  (multi-thread, em   │
└──────────────┘                           │   memória)           │
                                           └──────────────────────┘
```

| Componente | Descrição |
|---|---|
| `AuthServer.java` | Servidor TCP com **uma thread por cliente**, estado em memória com `ConcurrentHashMap`, **locks por sala** para evitar reservas concorrentes conflitantes e console local de administração |
| `AuthClient.java` | Cliente de linha de comando com menus interativos, token mantido só em memória, **reconexão automática** e troca de servidor em tempo de execução |

### Detalhes do protocolo
- Cada mensagem é um objeto JSON terminado por `\n`
- Tamanho máximo de **8192 bytes** por mensagem, nos dois sentidos
- Toda requisição tem o campo `op`. Toda resposta tem `op` (`<operacao>_response`), `status` e `message`
- Todos os valores trafegam como **strings**, inclusive números e booleanos
- Conexões ociosas são encerradas pelo servidor após **300 s**
- Códigos de status no estilo HTTP: `200`, `201`, `400`, `401`, `403`, `404`, `409`, `500`

---

## 📋 Operações suportadas

| Grupo | Operações |
|---|---|
| Autenticação | `register`, `login`, `logout` |
| Próprio cadastro | `read_user`, `update_user`, `delete_user` |
| Administrador | `admin_list_users`, `admin_read_user`, `admin_update_user`, `admin_delete_user` |
| Salas | `create_room`, `list_rooms`, `read_room`, `update_room`, `delete_room` |
| Reservas | `check_availability`, `create_reservation`, `list_reservations`, `read_reservation`, `update_reservation`, `delete_reservation` |

### Exemplo de troca de mensagens

**Login**
```json
{"op":"login","email":"admin@admin.com","password":"admin"}
```
```json
{"op":"login_response","status":"200","message":"Login realizado com sucesso","token":"3f9a...e1","role":"admin"}
```

**Criar reserva**
```json
{"op":"create_reservation","token":"3f9a...e1","room_id":"1","date":"2026-12-01","start_time":"14:00","end_time":"15:30","topic":"Reuniao de projeto","participants":"6"}
```
```json
{"op":"create_reservation_response","status":"201","message":"Reserva realizada com sucesso","reservation_id":"1"}
```

---

## ✅ Regras de validação

| Campo | Formato |
|---|---|
| `user` | 1–30 letras minúsculas (`a-z`) |
| `password` | 1–20 caracteres alfanuméricos |
| `email` | letras minúsculas, dígitos, `.`, `_`, `-` e domínio válido |
| `name` (sala) | 1–30 caracteres (letras, dígitos, espaço, `-`) |
| `capacity` / `participants` | inteiro de 1 a 9999 |
| `location` / `topic` | 1–60 caracteres (letras, dígitos, espaço, `,`, `.`, `-`) |
| `date` | `AAAA-MM-DD` (data válida) |
| `start_time` / `end_time` | `HH:mm`, com fim depois do início |

---

## 🚀 Como executar

### Pré-requisitos
- **Java 17+**
- **[Gson](https://github.com/google/gson)** (`gson-x.y.z.jar`)

### Compilação
```bash
javac -cp gson.jar AuthServer.java AuthClient.java
```

### Iniciar o servidor
```bash
# Linux/macOS
java -cp .:gson.jar AuthServer
# Windows
java -cp .;gson.jar AuthServer
```
O servidor pede a porta e começa a aceitar conexões. Comandos disponíveis no console do servidor:
- `usuarios`: lista os usuários cadastrados
- `ajuda`: mostra os comandos

### Iniciar o cliente
```bash
# Linux/macOS
java -cp .:gson.jar AuthClient
# Windows
java -cp .;gson.jar AuthClient
```
Informe o IP e a porta do servidor e use o menu:

```
=== RESERVA DE SALAS (v2.6.0) ===
1 - Cadastrar (register)
2 - Entrar (login)
3 - Sair da sessao (logout)
4 - Proprio cadastro
5 - Administrador [ADM]
6 - Salas
7 - Reservas
8 - Trocar servidor
0 - Encerrar cliente
```

### 🔑 Administrador inicial
Ao iniciar, o servidor cria automaticamente esta conta:

| Email | Usuário | Senha |
|---|---|---|
| `admin@admin.com` | `admin` | `admin` |

---

## 🔍 Logs

Cliente e servidor mostram no console **todo JSON enviado e recebido**, o que facilita depurar e conferir a conformidade com o protocolo:

```
[127.0.0.1:52344] CLIENTE -> SERVIDOR | JSON RECEBIDO: {"op":"list_rooms","token":"..."}
[127.0.0.1:52344] SERVIDOR -> CLIENTE | JSON ENVIADO: {"op":"list_rooms_response","status":"200",...}
```

---

## ⚠️ Limitações

- **Persistência apenas em memória**: todos os dados se perdem quando o servidor reinicia
- Hash de senha SHA-256 **sem salt**, adequado para fins didáticos mas não para produção
- Comunicação sem criptografia (TCP puro, sem TLS)
- Credenciais do admin inicial fixas no código

---

## 🛠️ Tecnologias

- Java (Sockets, Threads, `java.time`, `java.security`)
- Gson para serialização JSON
- Estruturas concorrentes (`ConcurrentHashMap`, `AtomicLong`, `synchronized`)

---

## 📄 Licença

Projeto acadêmico, livre para estudo e referência.
