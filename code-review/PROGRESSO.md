# Code Review — Backend Ktor (kotlinAPI)

> 🔴 **ABERTOS: ver [`ACHADOS-ABERTOS.md`](ACHADOS-ABERTOS.md) — S4–S8, M1–M5, E3, E5** (+ duas
> pontas do E2). Varredura de 2026-07-29 sobre o código inteiro. **Próximo: S4** (rate limit no
> `POST /users`, ~5 linhas no plugin já instalado).
>
> ✅ **S2 e S3 fechados (2026-08-10 `f3595b0`; 2026-08-19).** A família "validação de Patch":
> `UserPatch` ganhou `validate()` (era o único Patch sem), e os três Patch ganharam o guard de corpo
> vazio — `PATCH {}` respondia **500** nas três rotas (o Exposed recusa montar `UPDATE SET` vazio).
> **Suíte: 29 testes, 0 falhas** (eram 25).
>
> ✅ **S1 fechado (2026-07-31, `cbdc65c`).** Era o ALTO da lista — `username` sem unicidade, e dois
> `POST /users` bloqueavam o login de uma vítima permanentemente, sem autenticação. Fechado com
> `uniqueIndex()` + `@` proibido no username (as duas metades; nenhuma resolve sozinha).
> **E1 e E4 fechados (2026-07-31, `f1d4052`)** — pacote raiz `com.koin`; **E2 parcial**.
>
> ✅ **P1–P6 fechados (2026-07-29) — ver [`ACHADOS-PENDENTES.md`](ACHADOS-PENDENTES.md).**
> Saíram de uma varredura feita DEPOIS de fechar C/H/M; nenhum estava na lista original. P1 está
> documentado abaixo; P2 (IDOR no `PATCH /costs`), P3 (`toInt()` → 500), P4 (ordem da validação),
> P5 (coleção vazia = 200 + `[]`) e P6 (delete de global) estão no outro documento, cada um com a
> decisão que o fechou. **Suíte: 25 testes, 0 falhas** (eram 22; o S1 trouxe 3).

Progresso das correções do code review. Ordem de ataque: **C1 → C2 → C3 → C4 → C5**, depois **H1 (StatusPages)** e **H2 (Hikari)**.

## Status geral

| # | Item | Status |
|---|------|--------|
| C1 | IDOR: autorização em `PATCH /categories/{id}` | ✅ Feito |
| C2 | Senha em texto plano no update de perfil | ✅ Feito |
| C3 | Entidade `User` com senha vazando nas respostas | ✅ Feito |
| C4 | Segredos hardcoded/commitados (JWT + banco) | ✅ Feito |
| C5 | Teste apontando pro banco de produção | ✅ Feito |
| H1 | Sem `StatusPages` (500 cru / stacktrace vazando) | ✅ Feito |
| H2 | Sem pool real (HikariCP no build, nunca usado) | ✅ Feito (resolveu dívida do C5) |
| H4 | `POST /costs` insere antes de validar categoria | ✅ Feito |
| H5 | JWT de 1 ano + `validate` frágil / `asInt()` | ✅ Feito (expiração 30min + helper `userId()`; refresh → H9) |
| H6 | `runBlocking` dentro de `transaction` no boot | ✅ Feito |
| H7 | Login retorna 404 em vez de 401 | ✅ Feito (+ fase 2: timing constant-time, dummy hash válido — corrigiu 500 do `checkpw("","")`) |
| H8 | Validação fraca / ordem errada | ✅ Feito (DTO + Patch validam ANTES da query; CHECK no banco) |
| H9 | JWT sem refresh token (access curto exige reautenticação) | ✅ Feito (refresh stateful + rotação + detecção de reuso + logout) |
| M1 | Remover código morto (repos fantasma + data class `User` órfão) | ✅ Feito |
| M2 | Adotar Flyway (migrations versionadas) | ✅ Feito |
| M3 | `StdOutSqlLogger` só em dev | ✅ N/A (nunca existiu no código — item obsoleto) |
| M4 | Helper de `userId` | ✅ Feito no H5 (`security/AuthExtensions.kt`) |
| M5 | Email duplicado no update → 409 | ✅ Feito (já coberto pelo StatusPages; faltava o teste que prova) |
| M6 | `CallLogging` + rate limiting no `/login` (CORS: N/A) | ✅ Feito |
| M7 | Consolidar versões no `libs.versions.toml` | ✅ Feito |
| M8 | Separar entidade de DTOs | ✅ Feito no C3 |
| M9 | Shape de resposta de categoria | ✅ Feito |

---

## ✅ Concluído

### C1 — Autorização em `PATCH /categories/{id}`
- `CategoryService.editCategory(id, userId, patch)` com `where`: `(id eq id) and (userId eq userId)`.
- Rota lê `userId` do **token JWT** (`principal`), não da URL. Falha de edição responde **404** (não revela que o recurso existe pra outro dono).
- Bônus: corrigido import quebrado `com.sun.tools.jdeprscan.Main.call` (H3) que travava o build.
- **Regra aprendida:** autenticar diz *quem é*; autorizar diz *o que pode* — e o `where` da query é onde a autorização mora.

### C2 — Hash de senha no update
- `UserService.updateUser` (linha 77): `it[password] = transformPasswordInHash(newPassword)`.
- **Regra:** senha nunca entra no banco sem hash — em TODO caminho de escrita (cadastro, update, reset).

### C3 — DTOs de resposta sem senha
- Criados `UserResponse` (id, email, username, balance) e `LoginResponse` (userResponse + token) em `models/User.kt`.
- `createUser` → retorna `UserResponse`. `loginUser` → retorna `LoginResponse?`. `generateToken` recebe `UserResponse`.
- **Regra:** entidade é o schema do banco; DTO de resposta é o contrato da API. A senha não pode nem existir no objeto de resposta.
- **Pendência menor (opcional):** no `loginUser` há um `UserResponse` construído em duplicidade — dá pra reaproveitar a variável em vez de montar de novo dentro do `LoginResponse`.
- **Nota:** o data class `User` ficou órfão (só usado por código morto `CategoriesRepository.validadeCategory` + 2 imports sem uso em `UserService.kt:5` e `TokenConfig.kt:5`). Será varrido no M1.

### Sobre `?` e valores padrão em data classes (dúvida respondida)
- `?`/default só é o **certo nos Patch** (`null` = "não mexe nesse campo").
- **Request de campo obrigatório** e **Response**: sem `?`, sem default → falha cedo se faltar dado.
- Default em campo obrigatório engole dado incompleto em silêncio (ex.: `username = ""` deixava cadastrar sem username).

---

### C4 — Segredos fora do código

- `application.conf`: `secret = ${JWT_SECRET}` (sem `?` → sem a env var o config nem carrega e o app não sobe). Contraste: `${?PORT}` usa `?` porque porta tem fallback; segredo não tem.
- `DatabaseFactory.kt`: `DB_URL` / `DB_USER` / `DB_PASSWORD` via `System.getenv(...) ?: error(...)` (fail-fast, mensagem clara). `System.getenv` lê o ambiente **real do processo**, não um arquivo `.env`.
- `.env` estava **vazio e em `src/main/kotlin/`** (iria empacotado no `.jar`) → removido. Criado `.env.example` na **raiz** (template versionável, sem valores) e `.env` adicionado ao `.gitignore`.
- **Definir as vars (recomendado, zero lib):** IntelliJ → *Run → Edit Configurations → Environment variables* → `JWT_SECRET=...;DB_URL=...;DB_USER=...;DB_PASSWORD=...`. Viram env vars reais do processo → tanto `System.getenv` quanto o `${JWT_SECRET}` do HOCON enxergam. (Só se quiser ler `.env` de verdade: lib `dotenv-kotlin` + `System.setProperty` no início do `main`, antes do Ktor carregar o config.)
- **~~Rotação urgente~~ — alarme falso, corrigido em 2026-07-28:** este item afirmava que `API_BOLADA_KOTLIN` e `Activa@01` "já foram pro Git → estão queimados". **O projeto não é um repositório git** (não existe `.git/`) e os valores são de sandbox local. Nada foi publicado → **nada a rotacionar**. A afirmação foi inferida da existência do `.gitignore`, não verificada.
- **O que continua valendo (para quando houver repo):** segredo que entra num commit está comprometido para sempre — `git rm` não apaga o histórico. Antes do primeiro `git init`/`push`: conferir que `.env` segue no `.gitignore` (segue) e gerar valores de produção **separados** dos de sandbox (`openssl rand -base64 32`) — nunca promover o segredo de dev.
- **Nota p/ C5:** `DatabaseFactory.init()` ainda **não é parametrizado** (lê `System.getenv` direto). O C5 vai precisar injetar as credenciais do H2 (ex.: `init(jdbcUrl, driver, user, pwd)`), senão o teste tenta puxar as env vars de produção.
- **Regra aprendida (dobrada):** segredo é configuração de ambiente, não código — e segredo que foi pro Git precisa ser **rotacionado**, não só movido. Mas **"foi pro Git" é uma afirmação verificável** (existe `.git/`? `git log -S`?), e aqui ela não foi verificada. Achado de segurança sem verificação vira ruído — e ruído treina o time a ignorar o próximo alerta, que pode ser real.

---

### C5 — Teste em H2, não no banco real

- **Costura (não foi parametrizar o `init` com 4 args, foi um split melhor):** `DatabaseFactory.getEnvData(): Database` (obter conexão: MySQL+env, fail-fast) separado de `init(database: Database)` (usar a conexão) — com `transaction(database)` honrando o parâmetro (sem argumento, o Exposed iria no *default global*, não no injetado).
- **Sobrecarga de `module`:** `fun Application.module() = module(DatabaseFactory.getEnvData())` (casca fina que o EngineMain chama em produção) delega pro `fun Application.module(database: Database)` (corpo real, testável). O Ktor resolve a casca sem-arg sozinho porque não sabe fornecer um `Database`. Sem duplicação: corpo escrito uma vez.
- **Teste (`ApplicationTest.kt`):** H2 em memória com nome único por teste (`jdbc:h2:mem:c5_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1`) injetado via `application { module(Database.connect(...)) }`; config sobrescrita com `MapApplicationConfig` (dá os `jwt.*` sem env var e impede o auto-load do módulo de produção); client de teste com o **próprio** `ContentNegotiation` + `BigDecimalSerializer` contextual.
- **4 testes, 0 falhas:** registro→login→cria categoria (201); `/categories` sem token (401); **C1/IDOR: B editando categoria de A → 404**; login errado → 404 (comportamento atual, comentário apontando o H7).
- **Ajustes de contrato descobertos:** senha exige ≥ 8 chars (`validateUser`); `identifier` do login casa com email ou username; faltava `ktor-client-content-negotiation` → adicionado como `testImplementation` (produção intacta).
- **Regra aprendida:** teste de integração acha bug que teste isolado esconde; "passa sozinho, falha em grupo" = estado global compartilhado. Injeção de dependência só vale se a dependência for **efetivamente usada** na ponta.

**⚠️ Achado do C5 (bug de produção, adiado p/ o H2):** `DatabaseFactory.dbQuery` usa `newSuspendedTransaction {}` **sem passar o `Database`** → toda query vai pro *default global* do Exposed (última conexão aberta). Hoje funciona porque só há uma conexão; quebra quando existir mais de uma `Database.connect` — exatamente o que o **H2 (HikariCP)** introduz. Correção definitiva: `dbQuery`/`init` honrarem um `Database` explícito, ou centralizar tudo num único `HikariDataSource`. **Resolver junto com o H2.** (No teste, contornado com uma conexão H2 única por classe.)

---

### H6 — Boot: schema (transação síncrona) separado do seed (suspend próprio)

- **O bug que existia:** `runBlocking { ... }` **dentro** do `transaction {}` do `init()`. Dois problemas: (a) bloqueava a thread de boot **segurando a conexão** daquela transação o tempo todo do seed; (b) aninhava uma transação **suspensa** (o `newSuspendedTransaction` do `dbQuery`) dentro de uma **bloqueante** — *nested transaction* de semântica frágil no Exposed (a interna podia herdar/disputar a conexão errada). O erro nunca foi o `runBlocking` em si — foi ele **no lugar errado**.
- **`DatabaseFactory.init(database)`:** faz **só** o schema, numa `transaction(database)` síncrona. DDL é síncrono por natureza → transação bloqueante aqui é adequada.
- **`DatabaseFactory.seedGlobalCategories()`:** agora `suspend`, chamada do `module()` **fora** de qualquer `transaction {}`. Abre a própria transação via `dbQuery` e é **idempotente** (`where { userId.isNull() }.empty().not()` → não reinsere se as globais já existem).
- **`Application.module()`:** `runBlocking { seedGlobalCategories() }` no **nível do boot** — não dentro de transação. `module()` roda na thread de *startup* (não numa worker do Netty servindo request), então bloquear ali é aceitável e até desejável: o seed vira um **portão** (nenhuma request é servida antes das categorias globais existirem — determinístico, sem race).
- **Trade-off (registrado no código):** alternativa não-bloqueante seria `launch` num escopo da aplicação em `ApplicationStarted` — mas é *fire-and-forget*: uma request poderia chegar antes do seed terminar. No boot, previsibilidade > microssegundos. **Ideal futuro (M2):** seed idempotente via migration Flyway versionada, tirando o seed do código de boot de vez.
- **Regra aprendida:** `runBlocking` no boot, **fora de transação**, é ok — roda uma vez, na thread de startup, antes de existir request. O proibido é `runBlocking`/bloqueio **dentro de `transaction {}`** (segura conexão + aninha transação suspensa numa bloqueante) e **em handler de rota** (trava worker do Netty). Separe sempre: DDL = síncrono/bloqueante; seed = suspend, transação própria, idempotente.

---

### H7 — Login: credencial inválida → 401 (não 404), mensagem genérica

- **O que estava errado:** `UserRoutes.kt` respondia `HttpStatusCode.NotFound` (404) para login falho. 404 significa "esse recurso não existe" → confirma pro atacante que aquele e-mail/username **não está cadastrado**. Repetindo o login com vários e-mails, ele mapeia quais contas existem (**enumeração de usuário**) — insumo para phishing e força bruta direcionada.
- **A correção:** trocado para `HttpStatusCode.Unauthorized` (401) = "autenticação falhou", sem revelar o que falhou. Uma linha só na rota; o resto já estava certo.
- **Por que não mudou o service:** `UserService.loginUser` já retornava `null` para os **dois** casos — usuário inexistente (`singleOrNull() == null`) e senha errada (`BCrypt.checkpw` falso). Nunca distinguiu os dois, que é exatamente o desejado. O 404 estava só na tradução HTTP, na rota.
- **Mensagem única e genérica:** "Usuário ou senha inválidos" para ambos os casos, de propósito. Qualquer diferença observável — status, texto, ou até **tempo de resposta** — vira um oráculo que revela quais contas existem.
- **Testes:** o antigo "login errado → 404 (ver H7)" virou "→ 401 (H7)"; adicionado um **gêmeo** com usuário inexistente que afirma **mesmo 401 E mesma mensagem** que senha errada (prova a indistinguibilidade). `gradlew test` verde.
- **Radar (próximo passo natural):** timing attack — `BCrypt.checkpw` só roda quando o usuário existe; usuário inexistente responde na hora. Essa diferença de tempo é um canal lateral de enumeração. Mitigação: comparar contra um hash "dummy" mesmo quando o usuário não existe, para o tempo de resposta ser constante. E rate limiting no `/login` (M6).
- **Regra aprendida:** status e mensagem de erro são **superfície de informação**. Em autenticação, colapse todos os modos de falha numa resposta única e genérica (mesmo status, mesmo texto): "não existe" e "senha errada" têm que ser indistinguíveis de fora. Vazar *qual* falhou é enumeração de graça.

#### H7 (fase 2) — Mitigação de timing (constant-time no hash)

- **Regressão que existia:** o caminho "usuário inexistente" (`UserService.loginUser`, `userRow == null`) fazia `BCrypt.checkpw("", "")` como tentativa de tempo constante. Mas `""` **não é hash bcrypt válido** → `jbcrypt 0.4` lançava `StringIndexOutOfBoundsException` → caía no catch-all do `StatusPages` → respondia **500**. Resultado: inexistente = 500, senha errada = 401 → a indistinguibilidade que o H7 fechou tinha sido **reaberta** (e o teste `ApplicationTest.kt:182` estava vermelho, apesar do doc dizer "verde").
- **Correção:** `DUMMY_HASH` top-level = `BCrypt.hashpw("string-para-dummy", BCrypt.gensalt())` (hash **válido**, computado 1x no load). No branch sem usuário: `BCrypt.checkpw(user.password, DUMMY_HASH)` → sempre `false`, retorna `null`, **sem exceção**. Agora os dois modos de falha rodam um bcrypt de mesmo custo → tempo de CPU pareado.
- **Custo tem que casar:** `transformPasswordInHash` usa `gensalt()` sem argumento (default do jbcrypt = **custo 10**); o `DUMMY_HASH` usa o mesmo `gensalt()` default → ambos custo 10. Custo divergente reabriria o canal de timing (dummy mais barato = resposta mais rápida = enumeração).
- **Assimetria residual (aceita, não corrigida):** antes do `checkpw`, o `selectAll().where{}.singleOrNull()` materializa a row quando o usuário existe e não materializa quando não existe → diferença de **microssegundos**. Afoga sob o bcrypt (custo 10 ≈ dezenas de ms) + jitter de rede → **ruído, não explorável remotamente**. Defesa real contra brute-force é **rate limiting no `/login` (M6)**, não engenharia contra microssegundos.
- **Testes:** `gradlew test` verde; `ApplicationTest.kt:182` de volta a **401**.
- **Regra aprendida:** doc dizer "verde" não substitui **re-rodar** — a linha dummy quebrada entrou depois do último run e passou como concluída. E defesa de segurança mal-calibrada (dummy inválido / custo errado) vira bug **pior** que a ausência dela: aqui virou 500 e reabriu a enumeração. Constant-time em auth vale para o **relógio**, não só para status/texto.

---

### Auditoria (2026-07-23) — conferência linha a linha de H6/H7 + H1/H2/H4/H5

- **H6 — OK, bate com o doc.** `init(database)` faz SÓ schema em `transaction(database)`; `seedGlobalCategories()` é `suspend`, idempotente (`sendGlobalCategories` checa `userId.isNull()` e sai cedo) e é chamada FORA de transação, via `runBlocking` no nível do boot. Zero `runBlocking` dentro de `transaction {}`. Confirmado no código.
- **H7 (fases 1 e 2) — OK, bate com o doc.** Login responde 401 (não 404), mensagem única "Usuário ou senha inválidos" para os dois modos de falha; `DUMMY_HASH` é hash bcrypt VÁLIDO computado 1x no load; branch "usuário inexistente" faz `checkpw(user.password, DUMMY_HASH)` sem exceção; custo casa (ambos `gensalt()` default = 10). Confirmado.
- **H1/H2/H4/H5 — implementados de verdade (não só marcados).** StatusPages instalado + testado; HikariCP instanciado em `getEnvData()` (pool 10); `addCost` valida categoria antes de inserir na mesma transação; access token 30 min + helper `call.userId()`. Todos com teste cobrindo.
- **⚠️ Divergência encontrada (o motivo da auditoria):** o H8 estava **meio-implementado sem estar documentado** — `CategoryDTO.validate()` já existia e estava ligado no POST /categories, com um teste — mas a tabela ainda dizia "⬜ Pendente" E o teste estava **VERMELHO**: asseverava `contains("Cor inválida")`, texto que a app NUNCA produziu (a mensagem real é "Formato de cor inválido"). O doc do H7 fase 2 dizia "gradlew test verde", mas o trabalho parcial do H8 que entrou depois deixou a suíte **9 testes / 1 falha**. Corrigido ao fechar o H8 (abaixo). **Lição:** exatamente o padrão do próprio H7 fase 2 — "doc dizer verde não substitui re-rodar".

---

### H8 — Validação: formato/obrigatoriedade ANTES da query; CHECK no banco como última linha

- **Ordem certa (o coração do H8):** validação é barata (em memória), query é cara (I/O). Em `POST /costs` e `POST /categories` o DTO é validado PRIMEIRO; só com payload sano é que se toca no banco. Payload insano = 400 sem gastar ida ao banco.
- **`CategoryDTO.validate()`** (já existia, agora com teste verde): nome em branco + cor fora de `^#[0-9A-Fa-f]{6}$`. Ligado no POST /categories.
- **`CostDTO.validate()` (novo):** título em branco + `value <= 0`. `buildList` acumula TODOS os erros (usuário conserta tudo de uma vez, não erro-a-erro). `value` é `BigDecimal` → `<=` usa `compareTo` (ignora escala), então "0.00" também é barrado. Ligado no POST /costs ANTES do `addCost` (que faz o check de dono do H4).
- **Regra de negócio mais estrita que o banco, de propósito:** a app rejeita `value <= 0` (0 é ruído de digitação); a CHECK constraint do banco é `value >= 0`. Invariante de DADO (nunca negativo) mora no banco; regra de UX (tem que ser positivo) mora na app.
- **CHECK constraint em `CostsTable` (última linha):** `check("chk_cost_value_non_negative") { value greaterEq BigDecimal.ZERO }`. Se alguém inserir contornando o service (script, bug futuro, outra rota), o banco recusa. ⚠️ MySQL ≥ 8.0.16 e H2 enforçam CHECK; MySQL < 8.0.16 **ignora em silêncio** — por isso a app é a 1ª linha, não a única.
- **Teste RED corrigido** (`Teste de campos em branco na adição de categoria`): assertion trocada de "Cor inválida" → "cor inválido" (trecho que a mensagem real de fato contém). **Novo teste** `POST costs com valor negativo e titulo vazio retorna 400 antes da query (H8)`: manda `categoryId=999` (inexistente) com título vazio e valor negativo → responde **400** (formato), não 404 (categoria) — prova que a validação roda ANTES da query.
- **`gradlew test` verde: 10 testes, 0 falhas** (re-rodado e conferido no XML do relatório, não só na saída do console).
- **PATCH também valida (fechado):** `CategoryPatch.validate()` (Category.kt:34) e `CostPatch.validate()` (Cost.kt:56) usam `campo?.let { ... }` — validam SÓ o que veio no payload (`null` = não mexe). Ligados nas rotas: `CategoryRoutes.kt:62-66` e `CostRoutes.kt:78-81` (400 se erro). O caminho de update está protegido no nível da app, não só pela CHECK do banco.
- **Nota cosmética:** em `CostRoutes.kt:76-81` o `validate()` roda ANTES do check de `id == null` (ordem invertida em relação ao CategoryRoutes). Ambos retornam 400 → sem bug, só inconsistência de ordem.
- **Regra aprendida:** validação tem ORDEM (barata antes de cara: formato em memória → query no banco) e CAMADAS (app = 1ª linha, mensagem amigável 400; constraint do banco = última linha, invariante que a app não pode furar). E: item marcado "✅" na tabela sem seção detalhada + sem re-rodar teste = mentira em potencial — este H8 estava meio-pronto e vermelho enquanto o doc seguia em frente.

---

### H9 — Refresh token: stateful, com rotação e detecção de reuso

- **O que estava ausente:** só existia o access token (JWT curto, 30 min do H5). Sem refresh, o usuário reautenticaria a cada 30 min — inviável. E JWT é irrevogável por natureza (stateless): não dá para "deslogar" um access antes de expirar. Faltava o par clássico: **access curto irrevogável** (crachá descartável, mandado em toda request) + **refresh longo revogável** (a "chave da sala de crachás", usada só para obter um access novo).
- **Por que STATEFUL (tabela no banco), e não um segundo JWT:** um refresh que é JWT herda o problema do JWT — não dá para cassar. Para suportar logout e detecção de roubo, o refresh PRECISA de estado no servidor. Nova tabela `refresh_tokens` (`repositories/refresh/RefreshTokensTable.kt`): `tokenHash`, `userId`, `familyId`, `expiresAt` (epoch millis, `long` — sem depender de exposed-java-time, idêntico em H2/MySQL), `revoked`.
- **Por que guardar só o HASH (nunca o token cru):** mesma lição da senha, threat model diferente. Se o banco vazar, um atacante com os tokens crus impersonaria usuários até expirarem. Guardando `SHA-256(token)`, o vazamento entrega só hashes inúteis. O lookup no `/refresh` re-hasheia o token recebido e busca pelo hash (índice único).
- **Por que SHA-256 aqui e BCrypt na senha (ponto que confunde):** BCrypt é lento **de propósito** para conter força bruta em senha HUMANA (baixa entropia, dicionário). O refresh é aleatório de **256 bits** (`SecureRandom` → 32 bytes → Base64url): não há dicionário nem brute force viável, então basta um hash rápido de mão única. BCrypt num token aleatório seria só desperdício de CPU. **Entropia da entrada decide o algoritmo.**
- **Rotação (cada uso queima o token):** `POST /auth/refresh` valida → marca o refresh usado como `revoked` → emite um **novo** refresh na MESMA `familyId` + um access novo → devolve o par. Um refresh vale **exatamente uma vez**. Consumo é **atômico**: o `UPDATE ... WHERE tokenHash=? AND revoked=false` só afeta a linha se ela ainda estiver viva; o **count de linhas afetadas** é o guarda de corrida (mesma ideia do `where` do IDOR no C1). Dois requests concorrentes com o mesmo token: só um vira `false→true`.
- **Detecção de reuso = terra arrasada na família:** se um refresh JÁ revogado reaparece, é sinal de roubo (o legítimo e o ladrão têm cópias do mesmo token; um rotaciona, o outro apresenta a versão velha). Aí revogamos a **família inteira** (`revokeFamilyInTx`). Não importa quem chegou primeiro: se o ladrão rotacionou, o dono cai no reuso e mata a cadeia do ladrão; se o dono rotacionou, o ladrão cai. A sessão morre para os dois — o dono reautentica, o ladrão fica de fora. Logado no servidor como `warn` (evento de segurança).
- **Revogação (logout):** `POST /auth/logout` (autenticado) revoga a **família** do refresh apresentado — um login = uma família = uma sessão/dispositivo. `POST /auth/logout-all` revoga TODAS as famílias do usuário ("sair de todos os dispositivos"). Os access tokens ainda vivos morrem sozinhos em ≤ 30 min; o que cassamos é o refresh, que é o único jeito de renovar.
- **Endpoint `/auth/refresh` é PÚBLICO de propósito:** quando o cliente chama refresh, o access JÁ expirou — não dá para exigir `authenticate`. A credencial do endpoint é o **próprio refresh** (no corpo), não o JWT. Resposta de falha é **401 genérica única** para todos os modos (desconhecido/expirado/revogado/reuso): diferenciar daria um oráculo de quais tokens existem (mesma anti-enumeração do H7).
- **⚠️ Armadilha evitada (nested transaction — lição do H6):** `UserService.loginUser` roda numa `dbQuery`. Emitir o refresh (que também abre `dbQuery`) DENTRO dela aninharia `newSuspendedTransaction` dentro de `newSuspendedTransaction` — exatamente a semântica frágil que o H6 nos ensinou a evitar. Solução: `loginUser` foi reestruturado em **duas transações sequenciais** — a 1ª verifica a credencial e devolve o `UserResponse` (com o `DUMMY_HASH` do H7 preservado no caminho "sem usuário"); a emissão do refresh vem DEPOIS, fora daquele bloco. Já no `rotate`, tudo roda numa transação só, mas via helpers `*InTx` que usam DSL do Exposed direto (sem abrir `dbQuery` novo) — zero aninhamento.
- **Arquivos:** novos → `repositories/refresh/RefreshTokensTable.kt`, `services/auth/RefreshTokenService.kt`, `routes/AuthRoutes.kt`. Alterados → `security/TokenConfig.kt` (novo `generateAccessToken(config, userId)`; o `generateToken` antigo delega a ele — a assinatura do JWT mora num lugar só), `models/User.kt` (`LoginResponse.refreshToken` + `RefreshRequest` + `TokenPair`), `services/users/UserService.kt` (injeta `RefreshTokenService`, login reestruturado), `Routing.kt` (uma instância de `RefreshTokenService` compartilhada entre login e `/auth`), `factory/DatabaseFactory.kt` (`SchemaUtils.create` inclui a nova tabela).
- **Testes (`gradlew test` verde: 14 testes, 0 falhas — conferido no XML do relatório, não só no console):** (1) login emite refresh → `/refresh` devolve par novo com refresh DIFERENTE (rotação) e o access novo entra numa rota protegida (200); (2) reuso de refresh rotacionado → 401 E o refresh-irmão da mesma família também vira 401 (prova rotação + morte da família); (3) logout → refresh não troca mais por par (401); (4) token desconhecido → 401.
- **Pendência (não bloqueante):** tokens expirados/revogados ficam na tabela — falta um job de limpeza (`DELETE WHERE expires_at < now OR revoked`). Sem índice em `family_id` (revogação de família é rara; tabela pequena). Ambos são otimizações, não correção. Refresh TTL (14 dias) é constante no código, não no `application.conf`, pela mesma razão do access token (evitar replicar a property no `MapApplicationConfig` dos testes).
- **Regra aprendida:** o algoritmo de hash se escolhe pela **entropia da entrada** — BCrypt para senha humana (lento contra dicionário), SHA-256 para token aleatório de 256 bits (rápido, sem dicionário a temer). Refresh sem rotação + detecção de reuso é vetor PIOR que access longo: a segurança do refresh não está em existir, está na **rotação** (uso único) somada à **família** (um reuso derruba a sessão toda). E a lição do H6 se paga de novo: feature nova que toca o banco dentro de outra transação = nested transaction — separe em transações sequenciais.

---

### M1 — Varredura de código morto

- Deletados `repositories/costs/CostsRepository.kt` e `repositories/categories/CategoriesRepository.kt`: `object` singleton com `mutableListOf` global (estado mutável compartilhado = race condition sob múltiplas worker threads do Netty; `ArrayList` não é thread-safe → escrita perdida / `ConcurrentModificationException`; invisível em teste single-thread) + `validadeCategory` com `user.id!!`. Nenhum tinha referência externa (grep confirmou).
- Removido o data class `User` órfão de `models/User.kt` (só era parâmetro do `validadeCategory` morto). DTOs (`UserDTO`/`UserResponse`/`UserLogin`/`LoginResponse`/…) intactos.
- **Armadilha do import morto:** removidos os 3 `import models.User` (`UserService.kt`, `TokenConfig.kt`, `UsersRepository.kt`). Em Kotlin, import de símbolo que deixou de existir **não é warning, é erro de compilação** (`Unresolved reference`) — a 1ª tentativa quebrou o build por deixá-los. O compilador é quem diz se a varredura ficou completa, não a inspeção visual.
- **`gradlew test` verde: 14/0** (conferido no XML).
- **Regra aprendida:** apagar a definição sem apagar as referências quebra o build; e validação duplicada morta é pior que ausente (viola SRP + fonte única e é inconsistência esperando pra divergir).

### M2 — Flyway: schema versionado no lugar do `SchemaUtils.create`

- **Por quê:** `SchemaUtils.create` só emite `CREATE TABLE IF NOT EXISTS` — cria o que falta e **ignora em silêncio** a evolução (renomear/alterar coluna num banco existente não acontece, e não move dado). Flyway roda cada `V*.sql` **uma vez**, na ordem, registra em `flyway_schema_history` e versiona a evolução (princípio #6).
- **Baseline:** `src/main/resources/db/migration/V1__baseline.sql` com o DDL das 4 tabelas, **gerado** por `SchemaUtils.createStatements(...)` (não digitado à mão) — espelha exatamente as `*Table.kt` (colunas, FKs, uniques, CHECK `value >= 0`, `type` como VARCHAR(10), não ENUM). ⚠️ `createStatements` devolve os comandos **sem `;`**; num `.sql` do Flyway cada statement PRECISA de `;` (o Flyway divide o script por `;`, senão manda tudo como um statement só e o banco estoura).
- **Integração (fonte única — lição do C5):** `getEnvData()` passou a devolver `DataSource` (o `HikariDataSource` cru); `init(dataSource)` roda `Flyway.configure().dataSource(ds).load().migrate()` e só DEPOIS `Database.connect(dataSource)`. Flyway e Exposed consomem o MESMO DataSource. `SchemaUtils.create` eliminado. `Application.module` e o teste injetam `DataSource` (teste via `JdbcDataSource` de H2).
- **Build:** `flyway-core` + `flyway-mysql`. O suporte a H2 (testes) vem DENTRO do `flyway-core` — `flyway-database-h2` **não existe** como artefato (só bancos "grandes": mysql, postgresql, oracle… viram módulo à parte).
- **⚠️ O teste roda a migration REAL (não SchemaUtils)** — é isso que valida o `V1.sql`, e foi isso que expôs um bug de dialeto: baseline MySQL (crase `` `name` ``) rodando no H2 vira coluna `NAME` maiúscula, mas o Exposed consulta no dialeto H2 com `"name"` minúsculo → `Column "CATEGORIES.name" not found`. Fix: `CASE_INSENSITIVE_IDENTIFIERS=TRUE` na URL do H2 de teste (é como o MySQL real se comporta — deixa o teste MAIS fiel, não menos).
- **`gradlew test` verde: 14/0** (conferido no XML). Scratch `funcao.kt` (usado 1x pra gerar o DDL) removido.
- **Pendente do usuário (não automatizável aqui):** 1 boot contra o MySQL `koin` pra confirmar que o Flyway cria as 4 tabelas + `flyway_schema_history`. Seed de categorias globais permanece no boot (idempotente); mover pro Flyway é stretch futuro.
- **Regra aprendida:** (1) o teste tem que exercitar o MESMO artefato que vai pra prod — schema por SchemaUtils no teste + `V1.sql` na prod deixaria a migration quebrada passar direto pro deploy; (2) baseline gerado num dialeto NÃO roda transparente noutro — rodar a migration no teste é o que expõe o gap antes do deploy.

---

### M3–M9 — Fechamento dos médios

**Antes de codar, uma varredura:** metade dos "pendentes" já estava resolvida por trabalho anterior. `grep StdOutSqlLogger|addLogger` → **zero ocorrências** (M3 é item obsoleto, o logger nunca chegou ao código); `grep principal|getClaim` nas rotas → só um comentário (M4 morreu junto com o H5). Lição barata: **auditar antes de implementar** — item marcado ⬜ mente tanto quanto item marcado ✅ (princípio 8, agora nas duas direções).

#### M5 — 409 em email duplicado: o handler já existia, faltava a PROVA

- O `StatusPages` já mapeava `ExposedSQLException → 409` desde o H1. O que faltava era teste, e o TODO no `ApplicationTest.kt` dizia que o caminho "não era alcançável": verdade **para o `POST /users`**, onde `validateUser` faz um SELECT e barra o email duplicado com 400 antes do INSERT.
- **Mas o `PATCH /users/profile` não pré-checa nada** — manda o `UPDATE` direto e deixa o `uniqueIndex` de `users.email` estourar. Esse é exatamente o caminho que virava **500** antes do H1. O TODO tinha analisado só uma das duas rotas de escrita.
- Teste novo: usuário B tenta assumir o email de A via PATCH → **409** + corpo exato `{"error":"Registro em Conflito"}`. Assertar o corpo, não só o status, prova que a resposta saiu do NOSSO handler (um 409 pode vir de qualquer lugar).
- **Por que 409 e não 400/500:** o payload está bem formado (não é 400) e não é falha nossa (não é 500) — é **conflito com o estado atual do recurso**. Zero linha de produção mudou: a defesa genérica do H1 já cobria; o valor do M5 foi **descobrir que cobria e travar isso num teste**.

#### M6 — `CallLogging` + rate limit no `/login` (CORS: descartado)

- **`CallLogging`:** loga método, rota, status e duração de cada request. Sem ele, um 500 só aparece no stacktrace do StatusPages e não dá para responder "quantos 401 o `/users/login` tomou na última hora?". **De propósito não logamos corpo nem headers**: o corpo do login carrega senha e o `Authorization` carrega token — log é texto persistido, é superfície de vazamento (mesma lógica do C3).
- **Rate limit (`install(RateLimit)` + `rateLimit(RateLimitName("login"))` na rota):** 5 tentativas/minuto por IP. O H7 fechou a *enumeração* (não dá pra saber quais contas existem), mas **nada impedia 10.000 tentativas de senha na mesma conta**. Este é o freio real de força bruta — o que o H7 fase 2 já apontava como "a defesa de verdade", não a engenharia contra microssegundos de timing.
- **A chave: `(IP, identifier)` — composta.** A 1ª versão chaveava só por IP e estava errada; o desafio de fechamento do M6 expôs o furo e o usuário acertou o conserto. As duas opções simples falham de lados opostos:
  - **Só IP:** NAT junta gente demais numa chave. 200 funcionários — ou uma cidade inteira atrás do CGNAT da operadora, e **o nosso cliente é app Android** — saem pelo mesmo IP público. Com 5 fichas compartilhadas, o 6º a abrir o app às 8h toma 429 **com a senha certa**. DoS auto-infligido contra o usuário pagante.
  - **Só `identifier`:** (a) o atacante troca de email a cada tentativa, ganha balde novo e passa batido — que é exatamente o formato do *credential stuffing*; (b) qualquer um **trava a conta de um terceiro** de fora (DoS por lockout).
  - **Composta:** os 200 funcionários viram 200 chaves distintas (NAT resolvido) e o atacante não esgota o balde da vítima, porque `(IP_dele, conta_dela)` ≠ `(IP_dela, conta_dela)` — ela continua entrando enquanto ele apanha do 429.
- **`lowercase()` na chave não é cosmético:** o MySQL casa email sem diferenciar caixa, então `Ana@x.com` e `ana@x.com` atingem a MESMA conta. Sem normalizar, variar a caixa dava **balde novo a cada tentativa** — bypass de graça. Tem teste próprio.
- **Custo técnico da chave composta (a parte que trava quem tenta):** o `identifier` só existe no **corpo** da request. O `requestKey` do Ktor é `suspend` (`RateLimitConfig.kt:127`), então dá para chamar `call.receive()` ali — mas isso **consome** o corpo, e o handler do `/login` lê de novo depois → `RequestAlreadyConsumedException`. Solução: plugin oficial **`DoubleReceive`**, instalado DEPOIS do `ContentNegotiation` (quem sabe virar `UserLogin` o JSON). Corpo malformado cai num `runCatching` → balde só-de-IP, e o 400 sai do handler via StatusPages como em qualquer rota.
- **⚠️ O que a chave composta NÃO cobre (registrado como `ponytail:` no código):** a dimensão de **volume**. Com 5 fichas por email, o atacante varre 10.000 contas de um IP só sem encostar no limite. O teto por IP para isso precisa contar **falhas, não requests** — escritório às 8h gera 200 sucessos e 0 falhas; bot de stuffing gera ~99% de falha. Mas o plugin decide **antes** do handler (é o que evita gastar BCrypt com o atacante), então não sabe se falhou: exige contador próprio no branch do 401 + estado compartilhado (Redis) se houver 2ª instância. **Gatilho para fazer: tráfego real ou 2ª instância.** Não antes.
- **Por que só no `/login` e não na API inteira:** limite global puniria uso normal (uma tela lista categorias e custos em sequência). O alvo é a rota que aceita adivinhação de credencial. Estourado o limite, o plugin responde **429 + `Retry-After` antes do handler** — nenhum BCrypt é gasto com o atacante.
- **429 não reabre o H7:** o status muda de 401 para 429, mas isso depende só da **contagem de requests do IP**, nunca de a conta existir. Nenhum oráculo novo.
- **`ponytail:` registrado no código** — atrás de proxy/load balancer o `origin.remoteAddress` vira o IP do proxy e **todos os usuários compartilham o mesmo balde**. Ao colocar um proxy na frente: instalar `XForwardedHeaders` e só então confiar no origin.
- **CORS: N/A, não implementado.** CORS é o navegador pedindo permissão ao servidor; o cliente aqui é **app Android**, que não faz preflight. Instalar seria configuração morta — e configuração de segurança morta é pior que ausente (vira `anyHost()` no primeiro susto). Adicionar **quando** existir front web.
- Testes (2): (1) cadastro consome 1 do balde → 4 logins errados = 401 → 6ª tentativa = **429**; (2) **prova do NAT** — conta A esgotada (429, inclusive em MAIÚSCULA, provando o `lowercase`), e a conta B **no mesmo IP** loga normalmente. Com a chave antiga esse login de B viria 429.
- **Regra aprendida:** rate limit não é "escolher um número", é **escolher o que contar**. IP é grosso demais (200 pessoas = 1 chave) e fino demais (1 pessoa = 5.000 IPs) ao mesmo tempo — qualquer valor de `limit` numa dimensão só erra para os dois lados. E identidade composta cobre mais superfície, mas **cada campo dela precisa ser normalizado**, senão o atacante varia a caixa e ganha um balde novo.

#### M7 — Uma fonte de verdade para versões

- **O bug silencioso:** `logback` aparecia **duas vezes com versões diferentes** — `1.4.14` no catálogo (`libs.logback.classic`) e `1.5.6` hardcoded no `build.gradle.kts`, ambas declaradas. O Gradle resolvia para a maior e o build passava. Funcionava **por acaso, não por decisão** — e "por acaso" muda sozinho quando alguém mexe numa transitiva.
- Todas as libs hardcoded (Flyway, Exposed, MySQL, Hikari, H2, jbcrypt, ktor-client de teste) foram para o `libs.versions.toml`. `[versions]` agrupado por família: subir o Exposed agora é **uma linha**, não três que podem divergir.
- **`kotlinx-serialization-core:1.6.3` removido:** vinha transitivamente do `ktor-serialization-kotlinx-json` já numa versão mais nova. Declarar explícito uma versão velha é como **fixar um downgrade sem querer**.
- **`h2` era `implementation` → virou `testImplementation`:** o banco de teste estava indo dentro do `.jar` de produção sem nenhum código de produção referenciá-lo.
- **`jvmToolchain(25)` mantido:** a nota antiga de "bleeding edge" venceu — Java 25 é **LTS** (set/2025). Toolchain fixo garante que o build usa o mesmo JDK em qualquer máquina/CI, independente do `java` no PATH.

#### M9 — Shape consistente entre lista e detalhe

- `getCategories` montava o `Category` **sem o `userId`**; `getCategoryById` montava **com**. O mesmo recurso saía com formato diferente conforme a rota.
- Pior que inconsistência estética: `userId` é `Int?` e `null` **significa "categoria global"**. Omitir na lista fazia toda categoria do usuário se passar por global — o cliente não tinha como distinguir "minha, editável" de "global, não editável" sem um GET extra por item.
- Fix de uma linha (`userId = it[CategoriesTable.userId]`) + argumentos **nomeados** na construção: `Category(id, name, image, color)` posicional aceitaria `image` e `color` trocados sem o compilador piscar.
- Teste: lista traz a categoria do usuário **com** `userId` e a global "Alimentação" **com null** — as duas metades do contrato.

**`gradlew test` verde: 17 testes, 0 falhas** (conferido no XML do relatório).

**Regra aprendida:** defesa em profundidade só conta quando **alguém a exercita** — o 409 do M5 existia desde o H1 e ninguém sabia se funcionava; e rota irmã não herda a proteção da vizinha (o `POST /users` pré-checava o email, o `PATCH /profile` não, e o TODO generalizou de uma só). Em build: **dependência declarada duas vezes não é redundância, é ambiguidade** — o que roda passa a ser o que o resolvedor escolhe, não o que você escreveu.

---

## ⬜ Altos (H1, H2 primeiro)

- **H1 — StatusPages:** `install(StatusPages)` central. Logar completo no servidor, responder mensagem genérica + status certo. `SerializationException`/`BadRequestException` → 400; exceções de domínio → 4xx. (Hoje JSON malformado ou violação de FK/unique vira 500 com stacktrace vazando.)
- **H2 — HikariCP:** já está no build, nunca instanciado. Criar `HikariDataSource(HikariConfig)` uma vez e `Database.connect(dataSource)`. Pool ~10. É o gargalo nº1 de performance.
- **H4 — `POST /costs`:** validar a categoria (dono/existência) ANTES de inserir, na mesma transação. Ordem: recebe DTO → confere categoria → insere → 201.
- **H5 — JWT:** expiração curta (15-60 min) + refresh token. Extrair helper central `fun ApplicationCall.userId(): Int` tratando ausência (401), em vez de `principal!!...asInt()` repetido.
- **H6 — boot:** separar criação de schema (uma transação) do seed (chamada suspend própria a partir do `module()`), sem `runBlocking` dentro da transação. Ideal: seed via migration.
- **H7 — login:** credencial inválida → **401** (não 404). Mensagem genérica pra não permitir enumeração de usuário.
- **H8 — validação:** ✅ FEITO (ver seção "Concluído"). Validação de formato antes da query em `CostDTO`/`CategoryDTO` **e nos PATCH** (`CategoryPatch`/`CostPatch`) + CHECK `value >= 0` no banco. Nada pendente.
- **H9 — refresh token:** ✅ FEITO (ver seção "Concluído"). Refresh **stateful** (tabela `refresh_tokens` com só o **hash** do token), **rotação** a cada uso, **detecção de reuso** que revoga a família inteira, `POST /auth/refresh` + `/auth/logout` + `/auth/logout-all`. Nada pendente (limpeza de tokens expirados adiada para um job futuro, não bloqueante).

## ⬜ Médios

- **M1** — ✅ FEITO (ver seção "Concluído"). Código morto removido: `CostsRepository`/`CategoriesRepository` + data class `User` órfão + 3 imports mortos.
- **M2** — ✅ FEITO (ver seção "Concluído"). Flyway com `V1__baseline.sql`; `init` migra via `Flyway.migrate()` e injeta `DataSource`; `SchemaUtils.create` eliminado. Teste roda a migration real.
- **M3–M9** — ✅ TODOS FEITOS (ver seção "M3–M9 — Fechamento dos médios"). M3 e M4 eram itens obsoletos; M5/M6/M7/M9 implementados e testados. CORS descartado com justificativa (cliente é app Android).

---

### P1 — BCrypt dentro da transação (achado novo, saiu do log de teste)

**Como apareceu:** rodando os testes do M5, o log mostrou `Transaction attempt #0/#1/#2 failed` — o Exposed tentando 3x a MESMA violação de índice único. Puxando o fio, o retry acabou não sendo o problema; foi o holofote em cima de um.

**O retry (comportamento do Exposed, conferido na fonte 0.59.0):**
- `Suspended.kt:197` → `catch (cause: SQLException)`. **Qualquer** `SQLException`, sem olhar SQLState: violação de constraint cai no mesmo balaio de deadlock.
- `DatabaseConfig.kt:77` → `defaultMaxAttempts = 3`; `defaultMinRetryDelay = defaultMaxRetryDelay = 0` → os 3 tiros saem **sem intervalo**.
- **Decisão: NÃO mexer no `maxAttempts`.** Retry existe para falha *transitória* (deadlock, timeout de lock), onde repetir é exatamente o certo — e esses são justamente os que aparecem sob carga, quando mais doem. Baixar para 1 globalmente trocaria um desperdício raro (2 round-trips a mais num erro de usuário) por fragilidade no caminho que importa. O barulho no log é sintoma, não doença.

**A doença — as 4 chamadas de BCrypt estavam DENTRO de `dbQuery { }`** (`UserService` linhas 32, 52, 55, 80):
- BCrypt é lento **de propósito** (custo 10 ≈ dezenas de ms de CPU pura). Dentro da transação, ele segura **uma das 10 conexões do pool** parada, queimando CPU, enquanto ela poderia estar servindo outra query. Conexão de banco é para I/O de banco — é a lição do H6 (`runBlocking` dentro de `transaction {}`) reaparecendo com outra roupa.
- **O pior caso era o `/login`**, o caminho mais quente da API: cada tentativa (inclusive as erradas, inclusive as de um atacante) prendia uma conexão por dezenas de ms. Uma rajada de logins esgotava o pool e travava **toda** a API — inclusive rotas que nada têm a ver com autenticação.
- **É aqui que o retry vira amplificador:** um `PATCH /profile` trocando email duplicado **e** senha pagava o hash **3 vezes** antes de responder 409.

**Correção — hoisting, sem mudar contrato nenhum:**
- `createUser`: hash calculado **antes** de abrir a transação.
- `updateUser`: idem, via `patch.password?.let { transformPasswordInHash(it) }` antes do `dbQuery`.
- `loginUser`: a transação passou a fazer **só a leitura** (devolve `UserResponse to hash` e a conexão volta ao pool na hora); os dois `checkpw` foram para fora. **O H7 fica intacto e mais claro**: existindo ou não o usuário, roda exatamente **um** `checkpw` de mesmo custo (hash real ou `DUMMY_HASH`) — nenhum caminho responde mais rápido, que é o canal lateral que o H7 fase 2 fechou.
- **Bônus no `DUMMY_HASH`:** era `BCrypt.hashpw("string-para-dummy", ...)` — valor **conhecido**. Quem mandasse exatamente essa senha para um usuário inexistente fazia o `checkpw` devolver `true`. Não autenticava ninguém (não há usuário para devolver, e o `?: return null` segura), mas é um caminho estranho que some de graça: a entrada virou `UUID.randomUUID()`.

**`gradlew test` verde: 18 testes, 0 falhas** (conferido no XML). Os `Transaction attempt` continuam no log — de propósito: agora custam só o round-trip, sem BCrypt junto.

**Regra aprendida:** transação é para I/O de banco; **trabalho caro de CPU fica fora**, senão você paga com a conexão do pool, que é o recurso mais escasso da app. E antes de "consertar" o que faz barulho (o retry), confira **o que o barulho está iluminando** — o retry estava certo; o que ele multiplicava é que estava errado.

---

## ⬜ O que sobrou (nada bloqueante)

- **~~Rotação de segredos (do C4)~~ — CANCELADO:** não há repositório git e os valores são de sandbox. Nada exposto. O que fica é um checklist para o dia do `git init` (ver seção C4).
- **Boot real contra o MySQL (do M2):** confirmar que o Flyway cria as 4 tabelas + `flyway_schema_history`.
- **Limpeza de refresh tokens expirados (do H9):** `DELETE WHERE expires_at < now OR revoked`. Tabela cresce sem parar; não afeta corretude.
- **`XForwardedHeaders` (do M6):** obrigatório assim que houver proxy/LB na frente, senão o rate limit vira um balde único para todo mundo.
- **Camada de volume no rate limit (do M6):** teto por IP contando **falhas** (não requests), para pegar credential stuffing que varre muitas contas. Precisa de contador no branch do 401 + Redis se houver 2ª instância. Gatilho: tráfego real ou 2ª instância.
- **CORS (do M6):** só quando existir front web.

---

## Princípios que ficam

1. Autenticar = quem é; autorizar = o que pode. O `where` é onde a autorização mora.
2. Senha e entidade nunca cruzam a fronteira da API (hash em toda escrita, DTO de resposta em toda leitura).
3. Segredo é configuração, não código (env var + rotação). Teste roda em H2, nunca no banco real.
4. Robustez de produção = StatusPages + pool Hikari + migrations Flyway.
5. Platform types do Java (`asInt()`, `getClaim`) entram como não-nulos e escapam do null-check — tratar explicitamente ou vira NPE.
6. `SchemaUtils.create` não é migration — ignora mudanças de schema em silêncio.
7. Validação tem ORDEM (barata→cara: formato em memória antes da query) e CAMADAS (app = 1ª linha com 400 amigável; CHECK do banco = última linha, invariante que a app não pode furar).
8. Item marcado "✅" sem seção detalhada + sem re-rodar teste = mentira em potencial. Auditar = abrir o código E rodar a suíte, nunca confiar no status escrito. Vale nas duas direções: item ⬜ também mente (M3 e M4 já estavam prontos há semanas).
9. Defesa que ninguém exercita não conta como defesa (o 409 existia desde o H1 sem prova). E rota irmã não herda proteção da vizinha — verifique CADA caminho de escrita, não só o que o ticket cita.
10. Dependência declarada duas vezes não é redundância, é ambiguidade: o que roda vira o que o resolvedor escolhe, não o que você escreveu.
11. Transação é para I/O de banco. Trabalho caro de CPU (hash, imagem, parsing pesado) fica FORA — dentro dela você paga com a conexão do pool, o recurso mais escasso da app. Corolário do H6: o proibido nunca foi o `runBlocking`, é segurar conexão fazendo o que não é banco.
12. Rate limit não é escolher um número, é escolher **o que contar** — e cada campo da chave precisa ser normalizado, senão o atacante varia a caixa e ganha balde novo.
13. Antes de consertar o que faz barulho, veja **o que o barulho ilumina**. O retry do Exposed estava certo; errado era o BCrypt que ele multiplicava por 3.
14. O `where` autoriza a **linha que você toca**, não os **valores que você grava** (P2). Toda FK escrita é uma segunda decisão de autorização, sobre uma segunda tabela. E guarda **parcial** camufla guarda ausente melhor que guarda nenhuma.
15. Verificar antes de escrever nunca é garantia — é TOCTOU (P4, P6). Quem garante é a constraint / o row count da escrita. A pré-checagem só se paga quando produz informação que o resultado da escrita **não** produz **e** essa informação **muda a ação do cliente**: no P4 o 409 não diz qual campo conflitou, e "é o email" faz o app acender o input e o retry funcionar (fica); no P6 "é global" é inacionável — global não é deletável por ninguém, então 403 vs 404 não muda nada no app (sai). Quando não se paga, ela vira uma segunda regra para divergir da primeira.
16. Status HTTP é vocabulário, não sinalização livre (P5): coleção vazia é 200 + `[]`, não 404. Reusar um status para dois significados obriga o cliente a tratar erro como estado normal.
17. Conversão que pode falhar em entrada de cliente (`toInt()`) é 500 esperando acontecer (P3) — e tratamento de erro **inalcançável** é pior que ausente: passa no review por parecer que existe.
18. Num Patch, **"esse valor é válido?" e "veio algum valor?" são perguntas diferentes** (S3): a primeira olha os campos com regra, a segunda olha TODOS os campos graváveis. Um guard escrito sobre a lista errada rejeita payload legítimo — e o teste que pega isso é o que manda só um campo **sem** regra de valor.
