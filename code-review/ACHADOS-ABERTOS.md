# Achados abertos — varredura de 2026-07-29 (S / M / E)

Saíram de uma leitura completa de `src/main` (25 arquivos) + `build.gradle.kts`, `application.conf`,
`V1__baseline.sql`, `logback.xml` e `.gitignore`, **depois** de fechar C1–C5, H1–H9, M1–M9 e P1–P6.
Nenhum estava nas listas anteriores.

**Estado do repositório (na data da varredura):** `gradlew test` → **22 testes, 0 falhas** (conferido
no XML). Nada aqui deixou a suíte vermelha — os achados são de código que ninguém exercitava.

> **Atualização 2026-07-31 — três achados fechados, suíte em 25 testes, 0 falhas:**
>
> | Achado | Estado | Commit |
> |---|---|---|
> | **S1** | ✅ resolvido | `cbdc65c` |
> | **E1**, **E4** | ✅ resolvidos | `f1d4052` |
> | **E2** | 🟡 parcial — falta `transformPasswordInHash` → `security/` e achatar `tables/` | `f1d4052` |
>
> Seguem abertos: **S2–S8**, **M1–M5**, **E3**, **E5** e as duas pontas do E2.
> **Os caminhos citados nos achados abaixo são os de antes do `f1d4052`** — hoje tudo mora sob
> `src/main/kotlin/com/koin/`, e `repositories/` chama-se `tables/`. Próximo da fila: **S2 + S3**.

**Prefixos:** `S` = segurança/corretude · `M` = módulo/rota faltando · `E` = estrutura.

**Dois achados foram verificados RODANDO**, não só lendo (marcados ✅). Os testes temporários que
provaram isso foram removidos do repo — os passos exatos estão registrados abaixo para virarem
teste na hora de consertar. Princípio 8: não afirmar sem rodar.

## Ordem sugerida

1. ~~**S1**~~ — ✅ fechado em 2026-07-31 (`cbdc65c`). Era o único explorável por terceiro.
2. **S2 + S3** — mesma família (validação de Patch); o S2 depende do E2 para não duplicar regra.
3. **S4** — 5 linhas reaproveitando plugin já instalado.
4. ~~**E1 + E2**~~ — ✅ a parte que importava saiu em `f1d4052`: com `validateUser` já virado
   `UserDTO.validate()` em `models/`, **o S2 está destravado** — pode ser atacado direto.
5. **S5 + S6** — mentira de status code; conserto mecânico.
6. **M1 / M2** — precisam de decisão de contrato/domínio antes do código.
7. ~~**E1 + E4**~~ — ✅ fechados em 2026-07-31 (`f1d4052`); o **E2** foi junto quase todo (ver lá).
   Sobrou o **E3** + as duas pontas do E2, que agora vão de carona nele. **E5** segue por último.

---

# S — Segurança e corretude

## ~~S1~~ ✅ **RESOLVIDO** em 2026-07-31 (`cbdc65c`) — `username` não é único → bloqueio permanente de conta

> **Como foi fechado:** as duas metades previstas abaixo, porque nenhuma resolve sozinha (visto
> acontecer: com só a metade 1, o teste do vetor de username seguiu vermelho).
>
> 1. **`@` proibido no username** (`models/User.kt`) — parte o espaço de identificadores: **com** `@`
>    só casa e-mail (já único), **sem** `@` só casa username;
> 2. **`uniqueIndex()` em `UsersTable.username`** + `V2__username_unique.sql`.
>
> `singleOrNull()` **não** virou `firstOrNull()`, como o texto abaixo alertava. Username duplicado
> responde **409** pelo handler de `ExposedSQLException`, sem pré-checagem (a constraint é quem
> garante; `SELECT` prévio é TOCTOU). A mensagem genérica desse 409 é o que o **S6** ainda vai
> melhorar para a API inteira.
>
> **Verificado:** 3 testes novos escritos vermelhos antes do conserto (2 defendem o fato "a vítima
> continua logando", um por vetor; 1 defende o contrato da rota) → `gradlew test` **25 testes, 0
> falhas**. Boot real contra o MySQL: Flyway aplicou V1 e V2 com `success=1` e `SHOW INDEX FROM users`
> lista `users_username_unique` com `Non_unique=0` — isso também fecha a pendência de boot real do M5.
>
> O relato original fica abaixo, na íntegra, porque é o que ensina o bug.

**Onde:** `repositories/users/UsersTable.kt:7` (username sem `uniqueIndex()`),
`repositories/users/UsersRepository.validateUser` (só checa e-mail duplicado),
`services/users/UserService.kt:65-77` (`loginUser`).

**O bug:** o login casa o `identifier` contra e-mail **OU** username e fecha com `singleOrNull()`:

```kotlin
UsersTable.selectAll()
    .where { (UsersTable.email eq user.identifier) or (UsersTable.username eq user.identifier) }
    .singleOrNull()
```

`singleOrNull()` do Kotlin devolve **null** quando a coleção tem **mais de um** elemento (só
`single()` estoura). Duas linhas casando → `null` → cai no caminho do `DUMMY_HASH` → **401**.

**Verificação (rodada em 2026-07-29, H2):**

| Passo | Resultado |
|---|---|
| Vítima cadastrada (`vitima@x.com` / `vitima`) e logando | 200 |
| Atacante cadastra com `username = "vitima@x.com"` | **201** — nada barra |
| Vítima loga com o **e-mail** dela | **401** (era 200) |
| Vítima loga com o **username** dela | 200 (ainda funciona) |
| Atacante cadastra 2ª conta com `username = "vitima"` | **201** |
| Vítima loga com o **username** dela | **401** |

**Impacto:** dois `POST /users` derrubam o login da vítima **permanentemente**, sem autenticação
nenhuma. Pior que um 500: a resposta é 401 "Usuário ou senha inválidos" — **indistinguível de senha
errada**, então nem a vítima nem o suporte diagnosticam. E não existe fluxo de reset de senha para
escapar (ver M4). O `POST /users` sem rate limit (S4) é o veículo.

**⚠️ A correção óbvia NÃO resolve sozinha:** só adicionar `uniqueIndex()` em `username` deixa o furo
aberto. O username do atacante (`"vitima@x.com"`) é único **entre usernames**, o e-mail da vítima é
único **entre e-mails**, e o `OR` continua casando as duas linhas. Precisa dos dois:

1. `uniqueIndex()` em `UsersTable.username` + migration `V2__username_unique.sql`
   (⚠️ a migration **falha** se o banco já tiver usernames duplicados — conferir/limpar antes);
2. **proibir `@` no username** no `validateUser` (mesma função que já valida o e-mail).

Com os dois, identificador **com** `@` só pode casar e-mail (único) e **sem** `@` só username
(único) → no máximo 1 linha, e o `singleOrNull()` volta a significar o que parece significar.

**Não trocar `singleOrNull()` por `firstOrNull()`** — isso autenticaria uma identidade ambígua, que
é bem pior que negar. A invariante mora no banco (índice único) + na validação (sem `@`); o
`singleOrNull()` é a rede de segurança, não a correção.

**Testes a escrever junto:** (a) cadastrar username com `@` → 400; (b) cadastrar username já
existente → 400 (mensagem por campo, coerente com a decisão do P4); (c) o roteiro da tabela acima,
afirmando que a vítima continua logando por e-mail **e** por username depois das duas tentativas.

---

## S2 🟠 MÉDIO-ALTO — `PATCH /users/profile` não valida nada

**Onde:** `routes/UserRoutes.kt:53-63`, `models/User.kt:47-51` (`UserPatch` sem `validate()`).

`UserPatch` é o **único** Patch do projeto sem `validate()` — `CategoryPatch` (`Category.kt:34`) e
`CostPatch` (`Cost.kt:56`) têm, ligados nas rotas desde o H8. Consequências:

- **senha pode virar `"1"`** — a política de ≥ 8 caracteres existe só no cadastro (`validateUser`).
  O usuário rebaixa a própria credencial abaixo da política, e o `PATCH` hasheia e grava numa boa;
- **e-mail pode virar `"abc"` ou `""`** — e aí o login por e-mail nunca mais funciona (o
  `identifier` não casa nada);
- **username pode virar `""`**.

É o princípio 9 do próprio review: rota irmã não herda proteção da vizinha. O H8 fechou os PATCH de
categoria e custo e passou reto por este.

**Correção:** `UserPatch.validate(): List<String>` no formato dos outros dois (`campo?.let { ... }`,
`buildList` acumulando todos os erros), ligado na rota **antes** do service (ordem do H8: 400 sem
tocar no banco). As regras de e-mail/senha hoje vivem em `UsersRepository.validateUser` — fazer o E2
primeiro evita nascer com a regra duplicada em dois lugares para divergir depois.

---

## S3 🟠 MÉDIO — `PATCH` com corpo vazio → 500 nas três rotas ✅ VERIFICADO

**Onde:** `services/costs/CostService.patchCost`, `services/categories/CategoryService.editCategory`,
`services/users/UserService.updateUser` — e os `validate()` de `CostPatch`/`CategoryPatch`.

`{}` (ou qualquer patch cujos campos venham todos `null`) passa pelo `validate()` — que **por
design** só checa o que veio — e chega ao `update` sem nenhuma atribuição no `SET`:

```
PATCH /costs/1        {} → 500 {"error":"Erro Interno"}
PATCH /users/profile  {} → 500
PATCH /categories/8   {} → 500
java.lang.IllegalArgumentException: Can't prepare UPDATE statement without fields to update
```

(Verificado em 2026-07-29. O Exposed recusa montar o statement; a exceção cai no `exception<Throwable>`
do StatusPages.)

Erro de cliente virando 500 — exatamente o formato do P3 (`toInt()`), em outra roupa.

**Correção:** um guard em cada `validate()` — se **todos** os campos são `null`, adicionar
"Envie ao menos um campo para atualizar" → 400. Três linhas, uma por Patch (o `UserPatch.validate()`
do S2 nasce já com ela).

**Teste:** os três `PATCH {}` → 400, um por rota.

---

## S4 🟠 MÉDIO — `POST /users` sem rate limit, e cada request paga um BCrypt

**Onde:** `routes/UserRoutes.kt:22-30`, `Application.kt:84-130` (o `RateLimit` só registra o balde
`login`).

O cadastro é público, sem autenticação, e a **primeira** coisa que `createUser` faz é
`transformPasswordInHash` (BCrypt custo 10 ≈ dezenas de ms de **CPU pura**, lento de propósito).
Requests concorrentes = exaustão de CPU: o P1 tirou o BCrypt de dentro da transação (não segura mais
conexão do pool), mas a CPU continua sendo paga por um anônimo. E criação em massa de contas é
justamente o veículo do S1.

**Correção:** registrar um segundo balde no plugin já instalado:

- `RateLimitName("register")`, chave **só de IP** — não precisa de `DoubleReceive` aqui, o
  identifier é irrelevante (todo cadastro é de uma conta nova);
- envolver o `post` do cadastro com `rateLimit(RateLimitName("register")) { ... }`.

~5 linhas. **Herda o `ponytail:` do M6:** atrás de proxy sem `XForwardedHeaders`, o balde vira único
para todo mundo.

**`POST /auth/refresh` também é público e sem limite** (`routes/AuthRoutes.kt:20`). Prioridade menor
— o token tem 256 bits, não há brute force viável — mas cada chamada é um lookup no banco. Mesma
correção se quiser fechar a dimensão de volume.

---

## S5 🟡 MÉDIO — nenhum campo valida TAMANHO → 409 mentiroso

**Onde:** todos os `validate()` (`models/`) vs. os limites das tabelas.

| Campo | Coluna | Validação hoje |
|---|---|---|
| `title` (custo) | `VARCHAR(128)` | só `isBlank()` |
| `description` (custo) | `VARCHAR(255)` | nenhuma |
| `name` (categoria) | `VARCHAR(128)` | só `isBlank()` |
| `image` (categoria) | `VARCHAR(255)` | nenhuma |
| `email` / `username` | `VARCHAR(128)` | formato, não tamanho |

Payload maior que a coluna → MySQL/H2 em strict mode recusa → `ExposedSQLException` → o handler
responde **409 "Registro em Conflito"**, que é falso: não há conflito, o payload é inválido (400).

**Correção:** um `if (campo.length > N)` em cada `validate()` que já existe. O número fica replicado
entre tabela e validação — aceitável e explícito; ler o length da coluna via Exposed não paga o
custo. Uma constante por campo se quiser fonte única.

---

## S6 🟡 BAIXO-MÉDIO — `ExposedSQLException → 409` é balaio

**Onde:** `plugins/StatusPages.kt:23`.

**Toda** exceção SQL vira 409. Só violação de **unique** é conflito de verdade; violação de FK, de
CHECK e data-too-long (S5) não são. Efeito concreto hoje: `DELETE /categories/{id}` numa categoria
**que tem custos** bate no `ON DELETE RESTRICT` (`V1__baseline.sql:5`) e responde
**409 "Registro em Conflito"** — o app não tem como dizer "essa categoria tem custos, mova-os antes".

**Correção (uma das duas):**

- no handler, separar por `sqlState` (`23000`/`23505` = unique → 409; resto → 400 ou 500); **ou**
- tratar o caso concreto no `delete` da categoria (contar custos antes, ou capturar a violação de FK
  ali) e responder 409 com mensagem específica.

A 1ª é mais geral, a 2ª dá mensagem melhor. Decidir junto com o M2/M3 (se custo passar a poder
ficar sem categoria, o cenário muda).

---

## S7 🟢 BAIXO — `unique(name, user_id)` não protege as categorias globais

**Onde:** `repositories/categories/CategoriesTable.kt:13`, `V1__baseline.sql:4`.

Em MySQL e H2, `NULL != NULL` em índice único → duas categorias **globais** com o mesmo nome passam
pelo índice. A idempotência do seed depende 100% do `if` da app
(`CategoryService.sendGlobalCategories`), que não é atômico: dois boots simultâneos (2ª instância,
restart em paralelo) podem duplicar as globais.

**Correção (se e quando doer):** mover o seed para uma migration Flyway versionada — que é o "ideal
futuro" já registrado no H6/M2 — ou usar um valor sentinela em vez de `NULL` para "global"
(mudança de contrato, mais invasiva).

---

## S8 🟢 BAIXO — access token irrevogável e `validate` não confere se o usuário existe

**Onde:** `Application.kt:142-149`.

O `validate` do JWT aceita o token se houver claim `userId`, sem consultar o banco. Um usuário
excluído (quando existir `DELETE /users` — ver M5) continua autenticado até o access expirar.
Trade-off **já aceito** no H9 (janela ≤ 30 min; consultar o banco em toda request anularia o ganho
do JWT stateless). Registrado para não parecer esquecido.

---

# M — Módulos / rotas faltando

## M1 — `GET /users/profile` (ou `/me`) não existe

O app recebe o `UserResponse` **uma vez**, no login. Depois de um `PATCH /profile` não há como reler
o próprio perfil (nem o `balance`) sem deslogar e logar de novo. E o `PATCH` devolve a string
`"Usuário editado com sucesso"`, não o recurso atualizado.

**Correção:** `GET /users/profile` dentro do `authenticate` devolvendo `UserResponse` (a query já
existe em espírito no `loginUser`), e/ou o `PATCH` passando a devolver o `UserResponse` novo. É a
lacuna mais barata da lista.

## M2 — `balance` nunca muda (decisão de domínio)

`createUser` grava `0` e **nada mais no projeto escreve nessa coluna**. Custos não afetam o saldo.
Ou a coluna é peso morto, ou falta a feature central de um app de despesas. Três caminhos:

1. **Calcular na leitura** (`SUM(INFLOW) − SUM(OUTFLOW)` sobre `costs`) e **remover a coluna** —
   recomendado: saldo materializado dessincroniza no primeiro bug, e a soma é barata com índice em
   `costs.user_id`;
2. manter a coluna e atualizá-la em **toda** escrita de custo (add/patch/delete) — precisa da mesma
   transação e cuidado com concorrência (`UPDATE ... SET balance = balance + ?`, nunca ler-somar-gravar);
3. remover a coluna e não expor saldo.

Escolher **antes** do M3, porque muda o que os endpoints de agregação precisam fazer.

## M3 — sem filtro por período, agregação ou paginação em `/costs`

`GET /costs` devolve a vida inteira do usuário num JSON só, e "gastos do mês por categoria" tem que
ser somado no cliente. Falta `GET /costs?from=&to=` e/ou um endpoint de totais. Paginação entra
junto quando a lista crescer. **Gatilho:** quando a tela de relatório existir ou a lista passar de
algumas centenas de itens — não antes.

## M4 — sem recuperação de senha

Nenhum fluxo de reset. Isolado é uma feature; **combinado com o S1, é a ausência de saída** para uma
conta bloqueada. Consertar o S1 baixa a urgência disto.

## M5 — pendências já registradas em outros documentos (continuam abertas)

- **Limpeza de `refresh_tokens`** expirados/revogados (do H9): `DELETE WHERE expires_at < now OR revoked`.
- **`XForwardedHeaders`** (do M6): obrigatório no dia em que houver proxy/LB, senão o rate limit
  (e o S4 novo) viram um balde único para todos.
- **Camada de volume no rate limit** (do M6): teto por IP contando **falhas**. Gatilho: tráfego real
  ou 2ª instância.
- **CORS** (do M6): só quando existir front web.
- **Boot real contra o MySQL** (do M2): confirmar que o Flyway cria as 4 tabelas +
  `flyway_schema_history`.
- **`DELETE /users`** (excluir conta): não existe, e com `ON DELETE RESTRICT` nas 3 FKs exigiria
  apagar custos/categorias/tokens antes. Decisão de produto.

---

# E — Estrutura de pastas e pacotes

## ~~E1~~ ✅ **RESOLVIDO** em 2026-07-31 (`f1d4052`) — `Routing.kt` não declara `package`

> Foi junto no passe do E4: `Routing.kt` agora abre com `package com.koin`, e o `import
> configureRouting` solto do default package sumiu do `Application.kt`.

**Onde:** `Routing.kt:1` (começa direto num `import`).

O arquivo fica no **pacote raiz**, e é por isso que `Application.kt:13` tem aquele
`import configureRouting` solto — import de símbolo do default package, que nenhuma outra parte do
projeto usa. **Correção: 1 linha** (`package com.serraf`, ou o pacote que o E4 adotar) + ajustar o
import.

## E2 🟡 **PARCIAL** — `repositories/` não contém repositório nenhum

> **Fechado em 2026-07-31 (`f1d4052`):**
>
> - `repositories/` virou **`tables/`** — o nome não mente mais;
> - **`validateUser` virou `UserDTO.validate()`** em `models/User.kt`, ao lado de
>   `CategoryDTO.validate()` e `CostDTO.validate()`. A pré-checagem de e-mail duplicado foi
>   **removida**, não movida: consultava o banco (forçando `suspend`) e era TOCTOU — quem garante
>   unicidade é o `uniqueIndex`, e o duplicado vira 409 pelo StatusPages;
> - **nenhuma rota importa a camada de persistência** (`grep "import com.koin.tables" routes/` → 0).
>   O `route → service → table` fechou.
>
> **O que continua aberto:**
>
> - **`transformPasswordInHash` ainda mora em `tables/users/UsersRepository.kt`** — é BCrypt, cabe em
>   `security/`. É a última coisa dentro daquele `object`; movida, o arquivo some inteiro;
> - as 4 tabelas seguem **cada uma num subpacote próprio com um arquivo dentro**
>   (`tables/{categories,costs,refresh,users}/`), em vez de lado a lado.
>
> Ambos são passe mecânico, e vão bem de carona no **E3**.

**Onde:** `repositories/{categories,costs,users,refresh}/`.

O pacote contém **definições de schema** (`CategoriesTable`, `CostsTable`, `UsersTable`,
`RefreshTokensTable`) — tabelas Exposed, não repositórios. O nome mente, e cada uma está num
subpacote próprio com **um arquivo dentro**. Recomendado: `db/tables/`, os 4 arquivos lado a lado.

E lá mora `UsersRepository`, que não é persistência:

- **`validateUser`** = regra de validação → cabe em `models/User.kt`, junto de `CategoryDTO.validate()`
  e `CostDTO.validate()`. É exatamente onde o **S2** vai precisar dela (e o S1 vai editá-la);
- **`transformPasswordInHash`** = BCrypt → cabe em `security/`, junto do `TokenConfig`/`AuthExtensions`;
- é o **único** caso de rota chamando a camada de persistência direto
  (`routes/UserRoutes.kt:16` importa `UsersRepository.validateUser`), furando o
  `route → service → table` que todo o resto do projeto segue.

Fazer **antes** do S1/S2 evita mexer duas vezes nos mesmos arquivos.

## E3 — `factory/DatabaseFactory.kt` não é um factory

É a infra de banco: pool Hikari, `Flyway.migrate()`, `Database.connect` e o `dbQuery`. Cabe em `db/`
(junto do `tables/` do E2). Renomear o pacote é 1 linha por import.

**Continua aberto** — o E4 (`f1d4052`) passou por aqui e manteve `factory/`. Sobrou de lá uma pasta
`src/main/kotlin/com/koin/db/` **vazia** (o Git não versiona diretório vazio, então ela só existe na
sua máquina): é o destino já reservado, ou lixo para apagar se o E3 não for acontecer.

## ~~E4~~ ✅ **RESOLVIDO** em 2026-07-31 (`f1d4052`) — pacote base inconsistente (o passe mecânico)

> **Duas diferenças em relação ao desenho abaixo**, ambas deliberadas:
>
> - o pacote raiz adotado foi **`com.koin`**, não `com.serraf` — leia a árvore abaixo trocando um
>   pelo outro. `application.conf` foi junto (`modules = [ com.koin.ApplicationKt.module ]`), sem o
>   que o app não subiria;
> - **`factory/` continuou `factory/`** — o `db/` do desenho depende do **E3**, que segue aberto.
>
> `src/test/kotlin/com/koin/` espelha o pacote. Passe mecânico puro, sem correção de bug junto.

`Application.kt` declara `package com.serraf` mas mora em `src/main/kotlin/`; todo o resto usa
pacotes de topo sem prefixo (`routes`, `models`, `services`, `security`, `plugins`, `serializers`,
`factory`, `tables`). O recomendado — e o que IDE, Gradle e qualquer pessoa nova assumem — é
um pacote raiz único com os diretórios espelhando os pacotes:

```
src/main/kotlin/com/serraf/
├── Application.kt          plugins + boot
├── Routing.kt              ← ganha package (E1)
├── db/                     ← era factory/                      (E3)
│   ├── DatabaseFactory.kt
│   └── tables/             ← era tables/*/, 4 arquivos    (E2)
├── models/                 entidades + DTOs + validate()  ← recebe validateUser (E2)
├── routes/
├── services/
├── security/               ← recebe transformPasswordInHash     (E2)
├── plugins/
└── serializers/
```

`src/test/kotlin/` espelha o mesmo pacote (o teste já é `package com.serraf`).

**Como fazer:** num passe só, pelo **IDE (Refactor → Move / Rename package)** — nunca à mão. Zero
mudança de comportamento, mas toca todos os arquivos, então não misturar com correção de bug no
mesmo passo (senão o diff esconde o conserto). Rodar `gradlew test` depois: 22/0 é o gabarito.
`application.conf` já aponta para `com.serraf.ApplicationKt.module` — se o pacote do
`Application.kt` mudar, essa linha muda junto **ou o app não sobe**.

## E5 — `ApplicationTest.kt`: uma classe só (25 testes desde o S1; eram 22 na varredura)

Dividir por área (users/auth/costs/categories) é o normal, mas há um obstáculo **real**: o H2 é
compartilhado num `companion object` e `DatabaseFactory.database` é global por JVM (estado mutável
de propósito, documentado lá) — duas classes de teste com `DataSource` diferentes brigariam por esse
global, e voltaria o "passa sozinho, falha em grupo" do C5. Se dividir, extrair o boot/H2 para um
helper único compartilhado **primeiro**. Não é urgente.

---

## Regras que estes achados ensinam

1. `singleOrNull()` não é "0 ou 1" — é "**exatamente** 1, senão null". Quando a consulta é um `OR`
   entre duas colunas, quem garante a unicidade do resultado é o **schema**, não a função (S1).
2. Unicidade **por coluna** não impede colisão **entre colunas**: dois índices únicos não impedem que
   o username de um seja igual ao e-mail de outro se o login casa os dois no mesmo `OR` (S1).
3. Validação de Patch que só olha "o que veio" tem um caso de borda que nenhum campo cobre: **não
   veio nada** (S3). O `null` significa "não mexe"; todos `null` significa "não há update".
4. Trabalho caro de CPU em rota **pública** precisa de freio, mesmo fora de transação. O P1 tirou o
   BCrypt de dentro da transação; a CPU continua sendo paga por um anônimo (S4).
5. Handler genérico de exceção é rede de segurança, não classificação: quando ele mapeia uma família
   inteira (`ExposedSQLException`) para um status, ele **mente** para a maioria dos membros (S5, S6).
6. Nome de pacote é documentação executável: `repositories/` que só tem tabelas, e `factory/` que não
   fabrica nada, custam a cada pessoa nova (E2, E3).
