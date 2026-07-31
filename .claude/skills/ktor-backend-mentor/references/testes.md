# Testes em Ktor: testApplication e a pirâmide na prática

## Por que testar (o argumento que convence júnior)

Teste não é burocracia — é **liberdade de mudar**. Sem testes, cada refatoração é roleta-russa e você "testa" clicando no Postman de novo... e de novo. Com testes, `./gradlew test` responde em segundos se você quebrou algo. O custo se paga na primeira regressão evitada.

## A pirâmide, adaptada a uma API Ktor

1. **Unitários (muitos)**: services e funções puras (validação, cálculo de preço). Sem servidor, sem banco — milissegundos.
2. **Integração de rotas (vários)**: `testApplication` sobe a aplicação em memória e bate nos endpoints de verdade (serialização, status codes, StatusPages — tudo real). É o melhor custo-benefício em Ktor.
3. **Ponta a ponta com banco real (poucos)**: repositórios contra H2/Testcontainers.

## `testApplication` — o jeito Ktor 3

⚠️ Nota de versão: o antigo `withTestApplication` foi **removido** no Ktor 3. Se o usuário achar tutorial antigo usando isso, avise.

```kotlin
class ProdutoRoutesTest {

    // Nome de teste é documentação: descreva o COMPORTAMENTO esperado,
    // não o método testado. Backticks do Kotlin deixam isso legível.
    @Test
    fun `POST produtos com dados validos retorna 201 e o produto criado`() = testApplication {

        // 1. Montamos a aplicação COM UM FAKE no lugar do repositório real.
        //    É por isso que rotas recebem dependências em vez de instanciá-las:
        //    no teste, trocamos a peça. Sem banco, sem rede, sem flakiness.
        application {
            configureSerialization()
            configureStatusPages()
            routing { produtoRoutes(ProdutoService(ProdutoRepositoryFake())) }
        }

        // 2. Cliente de teste PRECISA do ContentNegotiation também —
        //    esquecê-lo é o erro nº 1 (dá erro de serialização no teste).
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        // 3. Age...
        val response = client.post("/produtos") {
            contentType(ContentType.Application.Json)
            setBody(CriarProdutoRequest(nome = "Filtro de óleo", precoCentavos = 3990, estoque = 10))
        }

        // 4. ...e verifica contrato: status E corpo.
        assertEquals(HttpStatusCode.Created, response.status)
        val corpo = response.body<ProdutoResponse>()
        assertEquals("Filtro de óleo", corpo.nome)
        assertTrue(corpo.id > 0)
    }

    @Test
    fun `GET produtos-id retorna 404 quando produto nao existe`() = testApplication {
        application {
            configureSerialization(); configureStatusPages()
            routing { produtoRoutes(ProdutoService(ProdutoRepositoryFake())) }
        }
        val response = client.get("/produtos/999")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST produtos com preco negativo retorna 422 com a mensagem de erro`() = testApplication {
        // Caminho triste é tão importante quanto o feliz: é aqui que
        // validação e StatusPages provam que funcionam.
        application {
            configureSerialization(); configureStatusPages()
            routing { produtoRoutes(ProdutoService(ProdutoRepositoryFake())) }
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/produtos") {
            contentType(ContentType.Application.Json)
            setBody(CriarProdutoRequest(nome = "X", precoCentavos = -1))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }
}
```

## O fake: simples de propósito

```kotlin
// Um fake NÃO é gambiarra — é uma implementação legítima da interface,
// otimizada para teste: previsível, rápida, em memória.
class ProdutoRepositoryFake : ProdutoRepository {
    private val dados = mutableMapOf<Long, Produto>()
    private var proximoId = 1L

    override suspend fun listar(pagina: Int, tamanho: Int) = dados.values.toList()
    override suspend fun buscar(id: Long) = dados[id]
    override suspend fun criar(nome: String, precoCentavos: Long, estoque: Int): Produto {
        val p = Produto(proximoId++, nome, precoCentavos, estoque)
        dados[p.id] = p
        return p
    }
    override suspend fun remover(id: Long) = dados.remove(id) != null
}
```

Trade-off a mencionar: fakes manuais vs. biblioteca de mocks (MockK). Para interfaces pequenas, fake manual é mais legível e ensina mais; MockK brilha quando você precisa verificar interações ("foi chamado com X?") ou simular libs de terceiros.

## Testando o repositório real: H2 em memória

Para provar que o SQL/Exposed está certo, use um banco de verdade descartável:

```kotlin
class ProdutoRepositoryExposedTest {
    @BeforeTest
    fun setup() {
        // MODE=PostgreSQL aproxima o dialeto do H2 ao do Postgres de produção.
        // DB_CLOSE_DELAY=-1 mantém o banco vivo entre conexões do mesmo teste.
        Database.connect("jdbc:h2:mem:teste;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Produtos) }
    }

    @AfterTest
    fun teardown() { transaction { SchemaUtils.drop(Produtos) } }

    @Test
    fun `criar persiste e buscar recupera o mesmo produto`() = runTest {
        val repo = ProdutoRepositoryExposed()
        val criado = repo.criar("Pastilha de freio", 12990, 5)
        assertEquals(criado, repo.buscar(criado.id))
    }
}
```

Limite honesto do H2 a explicar: dialetos divergem em recursos avançados (JSONB, funções específicas do Postgres). Quando o SQL sofisticar, o upgrade natural é **Testcontainers** (sobe um Postgres real em Docker por teste) — apresente como próximo passo, não como ponto de partida.

## Auth nos testes

Para rotas protegidas: gere um token válido com o MESMO secret configurado no ambiente de teste e envie `header(HttpHeaders.Authorization, "Bearer $token")`. Teste os dois caminhos: com token (200) e sem token (401) — o 401 prova que a proteção existe.
