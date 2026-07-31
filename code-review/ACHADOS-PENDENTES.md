# Achados P1–P6 — ✅ TODOS RESOLVIDOS (2026-07-29)

Achados que saíram da varredura de 2026-07-28, **depois** de fechar C1–C5, H1–H9 e M1–M9.
Nenhum estava na lista original do code review — apareceram lendo o código e o log de teste.

P1 está documentado em `PROGRESSO.md`; P2–P6 abaixo, cada um com a decisão que o fechou.

**Suíte: `gradlew test` → 22 testes, 0 falhas** (conferido no XML, 2026-07-29).

**⚠️ Achados NOVOS e abertos: [`ACHADOS-ABERTOS.md`](ACHADOS-ABERTOS.md)** (S1–S8, M1–M5, E1–E5,
varredura de 2026-07-29). O S1 é **ALTO** e verificado rodando. As pendências não-bloqueantes de
antes (boot no MySQL, limpeza de refresh tokens, `XForwardedHeaders`, volume no rate limit, CORS)
foram absorvidas lá no **M5**.

**Ordem de fechamento:** P2 → P3 → P4 → P5 → P6.

---

## ~~P2 — IDOR no `PATCH /costs/{id}`~~ ✅ RESOLVIDO (2026-07-29)

**Onde:** `services/costs/CostService.kt:143` (`patchCost`).

**O bug:** o `where` do update protege **qual custo** é alterado (`id` + `userId`), mas o
`categoryId` gravado vem do payload **sem checagem de dono**:

```kotlin
patch.categoryId?.let { newCategory -> it[categoryId] = newCategory }
```

`CostPatch.validate()` (`models/Cost.kt:56`) só checa `categoryId <= 0`. A FK só barra categoria
**inexistente** — categoria que existe e é de outro dono passa limpo.

**O vazamento é concreto:** `getCostsByUser` (`CostService.kt:24`) faz INNER JOIN custos→categorias e
devolve `name`, `image` e `color` dentro do `CostDTOResponse`. Então B aponta um custo dele para a
categoria privada de A e **lê o nome e a cor que A escolheu**.

**Por que se escondeu aqui:** no `POST` não existe `where` nenhum, então era óbvio que faltava
guarda — virou o H4. No `PATCH` existe um `where` que *parece* proteção. Foi a guarda parcial que
camuflou a guarda ausente.

### A regra que este achado ensina

> O `where` autoriza a **linha que você toca**. Ele não diz nada sobre os **valores que você grava**.
> Toda chave estrangeira escrita é uma **segunda** decisão de autorização, sobre uma **segunda**
> tabela, e precisa da própria checagem.

Contraste com o C1: lá o recurso modificado **é** a categoria, então `CategoriesTable.userId eq userId`
no `where` era exatamente certo. Aqui o recurso modificado é o custo, e a categoria é **valor de entrada**.

### Caminho que NÃO funciona (já testado empiricamente)

- **Passar `userId` para o `CostPatch.validate()`:** o `userId` dá à função a *pergunta*, não a
  *resposta* — "a categoria 7 é dele?" só o **banco** responde. Faria `models/` depender de `factory/`,
  transformaria a camada barata do H8 em I/O (matando a premissa do teste
  `POST costs com valor negativo... retorna 400 antes da query`) e abriria a checagem numa transação
  **separada** da escrita — a janela TOCTOU que o H4 fechou.
- **Referenciar `CategoriesTable.userId` no `where` do update:** SQL inválido (é a linha 144 de hoje).
  `WHERE` filtra linhas de tabelas **que estão na query**; a categoria nova é um literal no `SET`.
  (Existiria a variante com subquery `EXISTS`, que é válida e atômica — mas foge do formato que o
  `addCost` já usa neste mesmo arquivo e continua colapsando os dois motivos de falha em "0 linhas".)

### Caminho indicado

O `SELECT` necessário **já existe pronto** no `addCost`, `CostService.kt:106-112` — inclusive com o
`or CategoriesTable.userId.isNull()` que libera as categorias globais. Dentro do mesmo `dbQuery` do
`patchCost`, antes do `update`, no formato do `addCost` (`?: return@dbQuery ...`).

### As duas decisões — como foram fechadas

1. **O `SELECT` roda só quando `patch.categoryId != null`** (`patch.categoryId?.let { ... }`).
   Sem `categoryId` no payload não há FK sendo escrita → não há segunda autorização a tomar, e a
   query não é só cara, é sem pergunta. Princípio 7 (barata antes de cara) é o bônus, não o motivo.
   ⚠️ A 1ª tentativa deixou o `SELECT` incondicional com `CategoriesTable.id eq patch.categoryId`:
   com `null`, o Exposed gera `id IS NULL` → 0 linhas → **todo `PATCH {"title":"..."}` viraria 404**.
2. **`Boolean` mantido, os dois motivos colapsam no mesmo 404.** É a lição do C1: distinguir
   "custo não é seu" de "categoria de destino não é sua" confirmaria pro atacante que a categoria
   existe. A rota (`CostRoutes.kt:92`) já traduzia `false` → 404 — zero mudança de contrato.

### Testes (o alvo) — ✅ verdes

Ambos em `ApplicationTest.kt`:

- `PATCH costs nao pode mover custo para categoria de outro dono (P2)` — espera **404** e prova que
  o custo **não mudou** de categoria.
- `PATCH costs pode mover custo para categoria global (P2 - contraprova)` — garante que a correção
  não quebra o caso legítimo (categoria global, `userId` null). **Esta é a que pega correção
  exagerada**, do tipo que esquece o `isNull()`.

**Regra aprendida:** o `where` autoriza a **linha que você toca**; ele não diz nada sobre os
**valores que você grava**. Toda FK escrita é uma segunda decisão de autorização, sobre uma segunda
tabela. E guarda parcial camufla guarda ausente melhor do que guarda nenhuma — o `POST` sem `where`
virou o H4 na hora; o `PATCH` com meio-`where` sobreviveu ao review inteiro.

---

## ~~P3 — `toInt()` em parâmetro de rota → 500~~ ✅ RESOLVIDO (2026-07-29)

**Onde:** `routes/CategoryRoutes.kt:53` (patch), `routes/CategoryRoutes.kt:75` (delete),
`routes/CostRoutes.kt:105` (delete).

`DELETE /costs/abc` → `NumberFormatException` → catch-all do StatusPages → **500**. Deveria ser 400.

**Agravante silencioso:** o `if (id == null)` que acompanha os três é **código morto** — o `?.toInt()`
estoura antes de conseguir devolver null, e a rota é `/{id}`, então o parâmetro nunca falta. O
tratamento de erro parece existir e não existe.

`CostRoutes.kt:61` (get) e `CostRoutes.kt:76` (patch) usam `toIntOrNull()` corretamente. **Dois de
cinco sites certos, três errados.**

**Decisão: centralizado, no formato do H5.** `routes/RouteExtensions.kt` → `call.pathId()`, que lança
`BadRequestException` (a do próprio Ktor — o StatusPages **já** a mapeia para 400, sem classe nova nem
handler novo). Os **5** sites viraram `val id = call.pathId()` e os blocos `if (id == null)` foram
deletados: o helper apaga mais linha do que escreve, não é camada nova.

**Por que não só `toIntOrNull()` nos três:** o que ficaria repetido não é enfeite, é a **decisão**
("id inválido = 400"), e ela falha em **silêncio** — quem esquecer não toma erro de compilação, toma
500 em produção. Prova no próprio repo: 5 sites, 3 divergiram. Mesmo argumento do `AuthExtensions.kt:19-24`:
devolver nullable não elimina a repetição, só troca a forma dela (`?: return@x respond(400)` em todo handler).

**Teste:** `id nao numerico na URL responde 400 e nao 500 (P3)` — varre os 5 sites e assere o corpo
`{"error":"Requisição Inválida"}`, provando que o 400 saiu do NOSSO handler. **21 testes, 0 falhas** (XML).

**Regra aprendida:** `toInt()` em entrada de cliente é 500 esperando acontecer — a conversão que pode
falhar tem que ser a versão `OrNull`, e o tratamento da falha mora num lugar só. E tratamento de erro
que não pode ser alcançado (`if (id == null)` depois de um `toInt()`) é pior que ausente: passa no
code review justamente por parecer que existe.

---

## ~~P4 — `validateUser` consulta o banco ANTES das checagens em memória~~ ✅ RESOLVIDO (2026-07-29)

**Onde:** `repositories/users/UsersRepository.kt:12`.

O primeiro ramo do `when` é o `dbQuery` de email duplicado. Só depois vêm `email.isEmpty()`,
`contains("@")` e tamanho da senha.

É o **princípio 7 do próprio review invertido** (barata antes de cara). Hoje um
`POST /users {"email":"","password":"1"}` paga um round-trip ao banco antes de ser rejeitado por regra
que roda em memória — numa rota **pública e sem rate limit**, a única sem o freio do M6.

**Decisão: `SELECT` MANTIDO, movido para o último ramo do `when`.** O `when` para na primeira condição
verdadeira, então payload malformado nem chega ao banco.

**O que destravou a decisão:** o `SELECT` prévio **não garante nada** — é TOCTOU. Dois cadastros
simultâneos passam pelos dois `SELECT`s e um toma o 409 do `uniqueIndex` do mesmo jeito (M5). Logo o
caminho do 409 existe de qualquer forma, e o `SELECT` **não é corretude, é UX**. A escolha ficou sendo
só "essa mensagem vale um round-trip por cadastro?" — e vale: o 400 "O email já está sendo utilizado"
o app Android gruda no campo de email, enquanto o 409 genérico não diz **qual** campo conflitou;
traduzir 409 = "email em uso" por convenção quebraria no dia do segundo índice único na tabela.

Enumeração não entrou na conta: cadastro vaza "esse email existe" nos dois desenhos (400 explícito ou
409), então não desempata.

**Regra aprendida:** checagem em memória antes de I/O não é micro-otimização — é o que impede que
payload lixo consuma conexão do pool numa rota pública sem rate limit. E "manter a validação" ≠
"manter a ordem": a decisão de **existir** e a de **onde roda** são independentes. Verificação
antes-da-escrita nunca é garantia (TOCTOU) — quem garante é a constraint; a verificação prévia se
justifica pela **qualidade da mensagem**, e é assim que ela deve ser avaliada.

---

## ~~P5 — Coleção vazia responde 404~~ ✅ RESOLVIDO (2026-07-29)

**Onde:** `routes/CostRoutes.kt:28` e `routes/CategoryRoutes.kt:27`.

Coleção vazia não é "não encontrado": o recurso existe e está vazio. O certo é **200 com `[]`**. Hoje o
app Android é obrigado a tratar 404 como estado normal, e isso colide com o 404 de verdade (custo
inexistente).

**Decisão: mudado nos dois sites** — `GET /costs` e `GET /categories` respondem 200 com a lista
(vazia ou não). O `if (isEmpty())` foi deletado dos dois handlers.

**O teste do H4 mudou junto, e ficou melhor:** ele usava o 404 do `GET /costs` vazio como prova de
que nada tinha sido gravado. Virou `assertTrue(listaB.body<List<CostDTOResponse>>().isEmpty())`.
Não era opção manter: com 200 a assertion antiga ficaria **vermelha** (falha barulhenta, não erosão
silenciosa). E a nova prova mais: status só diz o que o handler respondeu — e um 404 pode vir de
qualquer `NotFound` no caminho; a lista vazia afirma o fato que o teste alega, zero linhas do B.

**Regra aprendida:** status HTTP é vocabulário, não sinalização livre — 404 significa "esse recurso
não existe", e coleção vazia é recurso que existe com zero itens. Reusar 404 para os dois obriga o
cliente a tratar erro como estado normal e apaga a diferença entre "sua lista está vazia" e "esse id
não é seu". E ao mudar contrato, o teste que se apoiava no comportamento antigo é oportunidade:
troque a assertion pelo **fato**, não pelo status.

---

## ~~P6 — Deletar categoria global responde 400 confuso~~ ✅ RESOLVIDO (2026-07-29)

**Onde:** `routes/CategoryRoutes.kt:74-96`.

O handler chama `getCategoryById(id, userId)`, que **aceita categoria global** (`userId eq user OR
userId.isNull()`). Depois chama `deleteCategoryById(id, userId)`, cujo `where` exige
`userId eq userId` — global nunca casa. Resultado: `DELETE` numa categoria global cai no ramo
`400 "Falha ao excluir a categoria"`.

Dois problemas: a mensagem sugere erro do servidor quando a regra é "global não é sua para deletar"
(403/404 seriam mais honestos), e são **duas queries** onde uma bastaria — o `delete` já devolve o
número de linhas afetadas, que é o mesmo guarda de corrida do C1.

**Correção:** a pré-checagem saiu. Quem autoriza é o **row count** do `deleteWhere` — `true` → 200,
`false` → 404. Uma query, atômica, sem janela entre SELECT e DELETE, e igual ao que o `patch` vizinho
já fazia (`editCategory` → `false` → 404).

**Por que 404 e não 403** (que seria mais honesto para a global, e não vazaria nada — toda global
aparece no `GET /categories`): o row count não distingue "global", "de outro dono" e "não existe" —
os três são 0 linhas. Dizer 403 exige uma **segunda query**, que é exatamente a pré-checagem que este
achado remove, e faria o `delete` divergir do `patch` de novo.

**Efeito colateral (varrido):** `CategoryService.getCategoryById` ficou sem chamador — a pré-checagem
era o único — e foi **removido** (regra do M1). Não existe rota `GET /categories/{id}`.

**Teste:** `DELETE categoria global responde 404 e nao exclui (P6)`. **22 testes, 0 falhas** (XML).

**Regra aprendida:** duas queries com regras **diferentes** sobre o mesmo recurso é um bug esperando
data — aqui uma aceitava global e a outra não, e o desencontro virou um 400 que culpava o servidor por
uma regra de negócio. Quando a escrita já devolve linhas afetadas, ela **é** a autorização: pré-checar
não adiciona garantia (o C1 e o H9 já tinham ensinado isso), só adiciona uma segunda regra para
divergir da primeira.

---

## Ordem sugerida

1. ~~**P2**~~ — ✅ feito (2026-07-29).
2. ~~**P3**~~ — ✅ feito (2026-07-29).
3. ~~**P4**~~ — ✅ feito (2026-07-29).
4. ~~**P5 / P6**~~ — ✅ feitos (2026-07-29), decisão de contrato registrada em cada seção.
