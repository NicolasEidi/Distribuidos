# Reserva de Salas — EP-3 (cliente e servidor com interface gráfica)

Projeto cliente/servidor em Java (TCP + JSON + Gson + Swing) que segue o protocolo
**Reserva de Salas v2.10.0**: <https://pedrosalvatori.github.io/protocolo-reserva-salas/>

## Arquivos

- `src/ReservaSalas/AuthServer.java` — servidor (motor do protocolo + janela Swing)
- `src/ReservaSalas/AuthClient.java` — cliente (janela Swing)
- `src/ReservaSalas/Ui.java` — tema e componentes visuais compartilhados (cores, botões, tabelas)
- `gson-2.14.0.jar`

## Como executar

### Servidor (`AuthServer`)

1. Execute `AuthServer` (NetBeans: `Shift + F6`; é a classe principal do projeto).
2. Na janela, digite a **porta** e clique em **Iniciar servidor**. A janela mostra os IPs da máquina.
3. A janela exibe:
   - **Usuários logados** (sessões ativas), atualizados automaticamente a cada login, logout ou expiração;
   - **Mensagens recebidas e enviadas** (JSON de cada requisição e resposta, coloridas: azul = pedido, verde = sucesso, vermelho = erro).

Cada conexão roda em uma thread própria: vários clientes ao mesmo tempo.

### Cliente (`AuthClient`)

1. Execute `AuthClient` (NetBeans: botão direito no arquivo → *Run File*).
2. No topo, digite o **IP** e a **porta** do servidor e clique em **Conectar** (a bolinha fica verde).
3. Para falar com **outro servidor**, digite outro IP/porta e clique em **Conectar** de novo. Se houver
   login ativo, o cliente faz logout no servidor antigo antes de trocar.
4. O painel inferior mostra as mensagens enviadas e recebidas (pode ser ocultado).

Conta administrativa criada pelo servidor ao iniciar (igual em todos os servidores):

```text
email: admin@admin.com   user: admin   senha: admin
```

## Telas do cliente

| Tela | O que faz (operação do protocolo) |
|---|---|
| Bem-vindo (Entrar / Criar conta) | `login`, `register` |
| Salas e horários | `list_rooms` (filtros), `read_room` com data = **agenda / horários vagos**, botão "Reservar esta sala"; admin: `create_room`, `update_room`, `delete_room` |
| Minhas reservas | `check_availability`, `create_reservation`, `list_reservations`, `read_reservation` (por ID), `update_reservation` (muda sala, dia, horário, assunto, participantes), `delete_reservation` (libera a sala) |
| Minha conta | `read_user`, `update_user`, `delete_user` |
| Administração (só admin) | `admin_list_users`, `admin_read_user`, `admin_update_user`, `admin_delete_user` |
| Menu lateral | `logout` ("Sair da conta") |

## Pontos do protocolo v2.10 implementados

- Linha JSON única terminada em `\n`, UTF-8, limite de 8192 bytes nas requisições, resposta `error` para linhas inválidas.
- Token de 64 hex, 30 min de validade com renovação, sessão única por usuário (login simultâneo: um 200, um 409),
  whitelist de tokens de admin (promoção/rebaixamento vale na hora).
- Horários UTFPR (`2M1`, `4T3`...): `slots` da sala, `start_time`/`end_time` só em início/fim de aula,
  reserva ocupa todas as aulas entre os dois, 409 "Horario fora do funcionamento da sala", agenda do `read_room`.
- Ordem de validação da seção 4 (400 → 401 → 403 perfil → 404 → 403 dono → 409 encerrada → 400 do registro → 409).
- **Sem reservas duplicadas**: verificar disponibilidade e gravar a reserva acontecem sob o mesmo lock por sala;
  `update_reservation` com troca de sala trava as duas salas em ordem crescente de `room_id`.
- `update_reservation` aceita `room_id` e `date` (v2.10) e ignora a própria reserva na checagem de conflito.
- Remover conta apaga as reservas dela; último admin não pode ser removido nem rebaixado.

Os dados ficam em memória; ao fechar o servidor eles são apagados.

## Compilação pelo terminal

```powershell
javac -encoding UTF-8 -cp "gson-2.14.0.jar" -d out src\ReservaSalas\AuthServer.java src\ReservaSalas\AuthClient.java src\ReservaSalas\Ui.java
java -cp "out;gson-2.14.0.jar" AuthServer
java -cp "out;gson-2.14.0.jar" AuthClient
```

Observação: `teste.py` é o script de testes da versão anterior (v2.6) e não vale mais para este protocolo.
