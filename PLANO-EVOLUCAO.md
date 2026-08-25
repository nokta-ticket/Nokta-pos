# Plano de evolução — Nokta POS

Análise feita em 2026-08-24 sobre o código real (41 arquivos Kotlin + backend `nokta-api`).
Este documento é o registro da análise e do plano; o estado de execução fica no fim.

---

## PARTE 1 — ANÁLISE

### 1.1 O que já existe e funciona (reutilizar, não recriar)

| Área | Estado | Onde |
|---|---|---|
| Pareamento de terminal (código 6 dígitos → `deviceToken`) | ✅ Completo | `PairingScreen`, `AuthRepository.redeemPairingCode`, backend `VenueDeviceService` |
| Login de operador (`POST /auth/device-login`) | ✅ Completo | `AuthRepository.login`, backend `AuthController.deviceLogin` |
| Dois estágios de identidade (device + operador) | ✅ Completo | `DeviceTokenInterceptor` + `BearerAuthInterceptor` |
| Credenciais Cielo por unidade (cifradas, nunca no APK) | ✅ Completo | `DeviceCredentialsStore` (EncryptedSharedPreferences) |
| Cardápio com cache offline (leitura) | ✅ Completo | `MenuRepository` (DataStore) |
| Carrinho local com modificadores e notas | ✅ Completo | `cart/CartModels.kt` |
| Pagamento Cielo por Deep Link, à prova de processo morto | ✅ Completo | `CieloDeepLinkPaymentProvider`, `PendingCieloAttemptStore` |
| Idempotência (pedido + pagamento) | ✅ Completo | `clientRequestId` / `idempotencyKey` |
| Nunca calcular total localmente | ✅ Completo | `OperationRepository` sempre relê o Tab |

### 1.2 Código pronto porém DESCONECTADO da UI

- **`OperationRepository.openTab(...)`** — já suporta `TABLE`/`INDIVIDUAL`/`COUNTER` e `tableId` opcional. Nenhuma tela chama. É a base da venda de balcão.
- **`registerPayment(amount, receivedCents, ...)`** — já aceita valor arbitrário (pagamento parcial) e troco. A UI só chama com `tab.remaining` (integral).

### 1.3 Capacidades do backend que o app IGNORA

1. **`GET tabs/:tabId` já devolve `orders[].items[].modifiers[]` e `payments[]`** (`TAB_DETAIL_INCLUDE`, `venue-tabs.service.ts:19`). O DTO `TabResponse` do app não declara esses campos → o app não consegue mostrar consumo, histórico nem pagamentos. **Nenhum endpoint novo necessário.**
2. **`GET locations/:locationId/tables` já devolve `openTab` por mesa** (`venue-tables.service.ts:43-54`). Resolve "consultar mesa" em 1 chamada.
3. **`GET locations/:locationId/tabs?search=` já busca por `publicCode` OU `customerName`** (`venue-tabs.service.ts:128-133`). Resolve "consultar comanda".
4. **`GET /organizations/:id/me/access` já devolve as permissões granulares do operador** (`venue-me-access.controller.ts`). O app nunca chama → não sabe se o operador pode cobrar.
5. **`GET menus`** lista cardápios com `isMain` → resolve o `MAIN_MENU_ID` hardcoded.
6. **`VenueSetupProfile.operationMode`** (`TABLE_SERVICE|COUNTER_SERVICE|MIXED`) já existe → é a "configuração por unidade" do item 20, sem criar nada.

### 1.4 Problemas reais encontrados

| # | Problema | Gravidade | Onde |
|---|---|---|---|
| P1 | `MAIN_MENU_ID = 1L` hardcoded — quebra em qualquer org cujo cardápio não seja id 1 | **Alta** | `CardapioViewModel.kt:164` |
| P2 | `startDestination = PAIRING` sempre — terminal já pareado/logado volta pro pareamento | **Alta** | `NoktaPosNavHost.kt:35` |
| P3 | Sem tratamento de 401 — sessão expirada só quebra a tela, não manda pro login | **Alta** | `AuthInterceptors.kt` |
| P4 | `requireOpenCashSessionForPayments` (default **true**) bloqueia TODO pagamento se o caixa não estiver aberto — trava o POS em campo sem explicação clara | **Alta** | `venue-payments.service.ts:63-71` |
| P5 | Fechar comanda é bloqueado por qualquer item não-`DELIVERED`/`CANCELED` — bebida entregue na hora trava o fechamento | **Alta** | `venue-money.util.ts:75` |
| P6 | `userName = user.email` — Home mostra e-mail em vez do nome | Média | `AuthRepository.kt:55` |
| P7 | `getModifierGroups` sem cache → adicionais quebram offline | Média | `MenuRepository.kt:78` |
| P8 | Sem outbox: pedido/pagamento feitos offline se perdem | **Alta** | (não existe) |
| P9 | `sessionExpiresAt` recebido e descartado | Média | `DeviceCredentialsStore.saveSession` |
| P10 | QR Scanner é o único caminho de entrada | **Alta** | `HomeScreen.kt` |

### 1.5 Decisões de arquitetura tomadas

1. **Não criar endpoint de "venda rápida" no backend.** Balcão = `Tab` tipo `COUNTER` criada e fechada pelo app numa sequência automática. A ledger fica idêntica à de qualquer venda (auditoria intacta, item 18), e a UI esconde a comanda (item 6). Uma chamada composta no backend duplicaria regra de negócio já existente e testada.
2. **Flexibilizar `checkTabCanClose`, não removê-la.** Item pendente de preparo passa a ser bloqueio configurável por organização (`VenueOperationSettings.blockTabCloseWithPendingItems`, default **false** = permite fechar). Preserva o fluxo administrativo de quem quer a trava (item 14 pede alteração segura, não remoção).
3. **`operationMode` decide o que a Home destaca, nunca o que o app suporta** (item 3/20): um único APK faz tudo.
4. **Outbox local (Room) para pedidos**, nunca para pagamento de cartão (item 19: Nokta não inventa aprovação financeira offline). Dinheiro/PIX podem ir pra fila; cartão exige a Cielo online.
5. **Permissões vêm de `GET me/access`** — a UI esconde o que o operador não pode fazer; o backend continua sendo a autoridade.

---

## PARTE 2 — PLANO POR FASES

### FASE 1 — Fundação: navegação, sessão, permissões, config da unidade
**Cria:** `ui/splash/SplashScreen.kt`+VM, `session/SessionManager.kt`, `network/UnauthorizedInterceptor.kt`, `access/AccessRepository.kt`, `access/domain/OperatorAccess.kt`, `settings/PosSettingsRepository.kt`
**Altera:** `NoktaPosNavHost.kt` (rotas novas + start destination real), `DeviceCredentialsStore` (expiresAt, nome), `AuthRepository`, `NetworkModule`
**APIs:** `GET me/access` (existente), `GET menus` (existente) — nenhuma nova
**Banco:** nenhuma
**Risco:** baixo — não altera fluxo existente, só o roteamento inicial
**Backend:** `deviceLogin` passa a devolver `nome` + `operationMode` (aditivo)

### FASE 2 — Home operacional + Venda de balcão
**Cria:** `ui/home/HomeScreen` (reescrita), `ui/venda/NovaVendaScreen`+VM, `ui/venda/BalcaoCheckoutScreen`
**Altera:** `Routes`, remove `onScanTab`
**APIs:** `createTab(COUNTER)` + `createOrder` + `sendOrder` + `createPayment` + `closeTab` — todas existentes
**Risco:** médio — sequência de 5 chamadas precisa ser resiliente (idempotência já existe)

### FASE 3 — Mesa
**Cria:** `ui/mesa/MesasScreen`+VM, `comanda/data/TablesRepository`, DTOs de mesa
**APIs:** `GET locations/:id/tables` (existente, já traz `openTab`)

### FASE 4 — Comanda por número
**Cria:** `ui/comanda/BuscarComandaScreen`+VM
**APIs:** `GET tabs?search=` e `GET tabs/by-code/:code` (existentes)
**Remove:** rota `SCANNER` da navegação (arquivos do scanner preservados)

### FASE 5 — Detalhe da comanda com consumo real
**Altera:** `OperationDtos` (declarar `orders`/`payments`), `ComandaModels` (itens/pagamentos), `ComandaScreen`
**APIs:** nenhuma nova — só passa a ler o que já vem

### FASE 6 — Pagamentos: parcial e divisão
**Cria:** `ui/pagamento/PagamentoScreen`+VM, `payment/domain/SplitCalculator.kt`
**APIs:** `createPayment` com valor parcial (já suportado)
**Backend:** `blockTabCloseWithPendingItems` + tornar `requireOpenCashSessionForPayments` visível ao POS

### FASE 7 — Histórico operacional
**Altera:** detalhe da comanda (timeline de lançamentos/pagamentos)

### FASE 8 — Offline / outbox
**Cria:** `sync/` (Room: `OutboxDao`, `OutboxEntity`, `SyncWorker`)
**Risco:** alto — idempotência é o que impede duplicar; já existe no backend

### FASE 9 — Cielo
**Altera:** `CieloDeepLinkPaymentProvider` (PIX), `PaymentModels`

### FASE 10 — Testes
**Cria:** testes unitários de `SplitCalculator`, outbox, mapeamento de DTOs; backend: atualizar `venue-operation-close.test.mjs`

---

## PARTE 3 — EXECUÇÃO (concluída em 2026-08-24)

### Entregue

| Fase | Estado | Onde |
|---|---|---|
| 1 — Navegação/sessão/permissões | ✅ | `ui/splash/`, `session/SessionEvents`, `network/UnauthorizedInterceptor`, `access/OperatorAccess`, `ui/theme/`, `ui/components/` |
| 2 — Venda de balcão | ✅ | `ui/venda/` (ViewModel + 2 telas) |
| 3 — Mesa | ✅ | `ui/mesa/` |
| 4 — Comanda por número | ✅ | `ui/comanda/BuscarComanda*` (QR removido da navegação) |
| 5 — Consumo real | ✅ | `OperationDtos`/`ComandaModels`/`ComandaScreen` |
| 6 — Parcial e divisão | ✅ | `payment/domain/SplitCalculator`, `ui/checkout/` |
| 7 — Histórico operacional | ✅ | Detalhe da comanda (itens + pagamentos + status) |
| 8 — Offline/outbox | ✅ | `sync/` |
| 9 — Cielo | ✅ (reuso) | Provider intocado; balcão cobra ANTES de criar comanda |
| 10 — Testes | ✅ | 35/35 (19 novos) |

### Backend (commit `f5ef4dc`, deployado e verificado em produção)

- `POST /auth/device-login` devolve `nome`/`sobrenome`, `mainMenu` e `posConfig`.
- `VenueOperationSettings.blockTabCloseWithPendingItems` (default `false`) — migration additive-only.
- `checkTabCanClose` ganhou 3º parâmetro; saldo restante continua bloqueando sempre.
- 463/463 testes do backend passando.

### Nenhum endpoint novo foi criado

Tudo saiu de endpoints existentes. A venda de balcão usa a mesma sequência de qualquer comanda (`createTab` → `createOrder` → `sendOrder` → `createPayment` → `closeTab`), então a ledger é idêntica — a simplicidade vive só na interface.

### Verificado no emulador

App instalado e aberto sem crash; splash foi direto à Home (terminal já pareado); navegação Home → Nova venda funcionando; logout preservou o pareamento (voltou ao login, não ao pareamento). `device-login` em produção responde 403 sem `X-Device-Token`.

### Pendências conscientes

1. **Fluxo de venda ponta a ponta não foi executado com dados reais** — exige senha do operador e um cardápio principal definido na organização de teste. O caminho falha hoje com a mensagem correta ("Nenhum cardápio principal definido para esta unidade") em vez de quebrar.
2. ~~`requireOpenCashSessionForPayments`~~ — **resolvido** (`1d26709`): a recusa por caixa fechado agora vira uma mensagem que diz o que fazer (é ação de gerente no painel, o garçom não abre caixa pelo POS). A flag continua `true` por padrão no backend, como era.
3. **PIX é registro manual** — o operador confirma no app do banco. Não há integração de liquidação.
4. **Testes rodam só com Gradle home sem acento** (ver `gradle.properties`).
5. **Scanner de QR permanece no código**, sem tela apontando para ele — removido da navegação, não apagado.
