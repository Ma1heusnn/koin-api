package com.koin.com.koin

import com.koin.appModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import com.koin.models.Category
import com.koin.models.CategoryDTO
import com.koin.models.CategoryPatch
import com.koin.models.CostDTO
import com.koin.models.CostDTOResponse
import com.koin.models.CostPatch
import com.koin.models.LoginResponse
import com.koin.models.RefreshRequest
import com.koin.models.TokenPair
import com.koin.models.TransactionType
import com.koin.models.UserDTO
import com.koin.models.UserLogin
import com.koin.models.UserPatch
import com.koin.module
import org.h2.jdbcx.JdbcDataSource
import com.koin.serializers.BigDecimalSerializer
import javax.sql.DataSource
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C5 — testes de integração de rota rodando 100% em memória (H2), nunca no banco real.
 *
 * Duas peças destravam isso:
 *  1. module(database) recebe o Database de fora  -> o teste injeta um H2.
 *  2. config sobrescrita via MapApplicationConfig -> fornece os jwt.* sem env var
 *     E impede o auto-load do módulo de produção (que iria no MySQL via getEnvData()).
 */
class ApplicationTest {

    companion object {
        // UMA única conexão H2 para a classe inteira. Por quê não um banco por teste:
        // os repositórios usam DatabaseFactory.dbQuery -> newSuspendedTransaction {} SEM
        // passar o Database, então toda query vai para o "default database" GLOBAL do Exposed
        // (a última conexão aberta). Se cada teste abrisse seu próprio H2, esse default global
        // escorregaria entre os testes na mesma JVM: um cadastro gravaria num banco enquanto o
        // login leria de outro (o 404 fantasma que víamos). Com um único connect, o default
        // global fica estável. Emails distintos por teste evitam colisão de estado.
        // MODE=MySQL aproxima o dialeto de produção; DB_CLOSE_DELAY=-1 mantém o banco vivo
        // enquanto a JVM de teste existe.
        // (Dívida REAL de produção: dbQuery deveria honrar um Database explícito — ver relatório.)
        // M2: agora um DataSource (não mais Database.connect direto) — o mesmo que module() injeta
        // em produção. O Flyway migra ESTE DataSource no init() e o Exposed conecta nele. DB_CLOSE_DELAY=-1
        // mantém o banco em memória vivo enquanto a JVM de teste existir, mesmo sem conexão aberta.
        private val h2: DataSource = JdbcDataSource().apply {
            // CASE_INSENSITIVE_IDENTIFIERS=TRUE: o V1.sql é dialeto MySQL (crase -> coluna NAME
            // maiúscula no H2), mas o Exposed consulta no dialeto H2 com "name" minúsculo. Sem este
            // flag, H2 é case-sensitive pra identificador citado e não acha a coluna. Ligá-lo faz o
            // H2 tratar identificadores como case-insensitive — que é exatamente como o MySQL real
            // se comporta, então o teste fica MAIS fiel à produção, não menos.
            setURL("jdbc:h2:mem:c5_shared;MODE=MySQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE")
            user = "sa"
            password = ""
        }
    }

    private fun ApplicationTestBuilder.bootH2() {
        environment {
            config = MapApplicationConfig(
                "jwt.issuer" to "test-issuer",
                "jwt.audience" to "test-audience",
                "jwt.secret" to "test-secret-please-change-0123456789",
                "jwt.realm" to "test-realm",
            )
        }
        application { appModule(h2) }
    }

    // O client de teste PRECISA do ContentNegotiation (senão NoTransformationFound ao (de)serializar)
    // e do MESMO BigDecimalSerializer contextual — sem ele, body<LoginResponse>() quebra no balance.
    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                serializersModule = SerializersModule {
                    contextual(BigDecimal::class, BigDecimalSerializer)
                }
            })
        }
    }

    // Fluxo real reaproveitável: cadastra -> loga -> devolve o LoginResponse inteiro (access + refresh).
    // Senha com >= 8 chars porque validateUser rejeita senha curta (daria 400 no cadastro).
    private suspend fun HttpClient.registrarELogarFull(email: String, username: String): LoginResponse {
        val cadastro = post("/users") {
            contentType(ContentType.Application.Json)
            setBody(UserDTO(email = email, password = "senha1234", username = username))
        }
        assertEquals(HttpStatusCode.Created, cadastro.status, "cadastro de $email deveria retornar 201")

        val login = post("/users/login") {
            contentType(ContentType.Application.Json)
            // identifier casa com email OU username; usamos o email.
            setBody(UserLogin(identifier = email, password = "senha1234"))
        }
        assertEquals(HttpStatusCode.OK, login.status, "login de $email deveria retornar 200")
        return login.body<LoginResponse>()
    }

    // Atalho para os testes que só precisam do access token.
    private suspend fun HttpClient.registrarELogar(email: String, username: String): String =
        registrarELogarFull(email, username).token

    @Test
    fun `registro depois login depois cria categoria retorna 201`() = testApplication {
        bootH2()
        val client = jsonClient()

        val token = client.registrarELogar("ana@exemplo.com", "ana")

        val resp = client.post("/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CategoryDTO(name = "Mercado"))
        }

        assertEquals(HttpStatusCode.Created, resp.status)
        val criada = resp.body<Category>()
        assertEquals("Mercado", criada.name)
        val id = assertNotNull(criada.id)
        assertTrue(id > 0)
    }

    @Test
    fun `categorias sem token retorna 401`() = testApplication {
        bootH2()
        val client = jsonClient()

        // Prova que a rota está protegida: sem Authorization, o plugin JWT barra antes da lógica.
        val resp = client.get("/categories")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `usuario B editando categoria de A retorna 404 (IDOR - C1)`() = testApplication {
        bootH2()
        val client = jsonClient()

        val tokenA = client.registrarELogar("a@exemplo.com", "userA")
        val tokenB = client.registrarELogar("b@exemplo.com", "userB")

        // A cria uma categoria dele.
        val catId = client.post("/categories") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(CategoryDTO(name = "Categoria do A"))
        }.body<Category>().id!!

        // B tenta editar a categoria do A.
        val resp = client.patch("/categories/$catId") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
            contentType(ContentType.Application.Json)
            setBody(CategoryPatch(name = "invadida"))
        }

        // 404 e NÃO 403: o where (id AND userId) faz o update afetar 0 linhas -> a API
        // responde "não encontrei", sem revelar que o recurso existe para outro dono.
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `login com senha errada retorna 401 (H7)`() = testApplication {
        bootH2()
        val client = jsonClient()
        client.registrarELogar("c@exemplo.com", "userC")

        val resp = client.post("/users/login") {
            contentType(ContentType.Application.Json)
            setBody(UserLogin(identifier = "c@exemplo.com", password = "senhaERRADA"))
        }

        // H7: credencial inválida -> 401 (não 404). Senha errada é "autenticação falhou".
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `login com usuario inexistente retorna 401 e mesma mensagem que senha errada (H7)`() = testApplication {
        bootH2()
        val client = jsonClient()

        // Gêmeo do teste acima, mas com identificador que NUNCA foi cadastrado. O ponto do H7:
        // "usuário não existe" e "senha errada" têm que ser INDISTINGUÍVEIS de fora (mesmo status
        // E mesma mensagem), senão a API vira um oráculo de quais contas existem (enumeração).
        val resp = client.post("/users/login") {
            contentType(ContentType.Application.Json)
            setBody(UserLogin(identifier = "naoexiste@exemplo.com", password = "senha1234"))
        }

        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertEquals("Usuário ou senha inválidos", resp.bodyAsText())
    }

    @Test
    fun `JSON malformado em POST users retorna 400 do StatusPages (H1)`() = testApplication {
        bootH2()
        val client = jsonClient()

        // Mandamos bytes CRUS e inválidos marcados como application/json. Usamos TextContent (que
        // já é um OutgoingContent) de propósito: assim o ContentNegotiation do CLIENT o deixa passar
        // intacto. Se passássemos um objeto tipado, o client serializaria um JSON VÁLIDO e não haveria
        // o que quebrar — o teste não provaria nada.
        val resp = client.post("/users") {
            setBody(TextContent("{ isto não é json válido", ContentType.Application.Json))
        }

        // No servidor, call.receive<UserDTO>() manda o corpo pro conversor JSON, que falha ao
        // desserializar. O ContentNegotiation do Ktor embrulha essa falha num BadRequestException —
        // e é ESSE tipo que o nosso StatusPages captura (exception<BadRequestException> -> 400).
        // Sem o StatusPages instalado (o bug do H1), a exceção vazaria como 500 cru: por isso este
        // teste é a prova de fogo de que o plugin está instalado E funcionando.
        assertEquals(HttpStatusCode.BadRequest, resp.status)

        // Assertamos o CORPO, não só o status. Um 400 pode vir de muitos lugares (uma validação de
        // regra de negócio, o 400 default do Ktor sem corpo...). O corpo EXATO prova que a resposta
        // saiu do NOSSO handler — a mensagem "Requisição Inválida" só existe no StatusPages.kt.
        assertEquals("""{"error":"Requisição Inválida"}""", resp.bodyAsText())
    }

    @Test
    fun `Teste Gêmeo ao de JSON malformado para rota autenticada`() = testApplication {
        bootH2()
        val client = jsonClient()
        val token = client.registrarELogar("teste@teste.com", "perfilteste")
        val resp = client.patch("/users/profile") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(TextContent("{ Este json é inválido", ContentType.Application.Json))
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals("""{"error":"Requisição Inválida"}""", resp.bodyAsText())

    }

    @Test
    fun `POST costs com categoria de outro dono retorna 200 com lista vazia e nao grava (H4)`() = testApplication {
        bootH2()
        val client = jsonClient()

        val tokenA = client.registrarELogar("custoA@exemplo.com", "custoUserA")
        val tokenB = client.registrarELogar("custoB@exemplo.com", "custoUserB")

        // A cria uma categoria dele.
        val catIdDoA = client.post("/categories") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(CategoryDTO(name = "Categoria do A (custos)"))
        }.body<Category>().id!!

        // B tenta criar um custo apontando pra categoria do A. A categoria EXISTE (a FK passaria),
        // mas NÃO é do B -> o check de dono na MESMA transação barra ANTES do insert.
        val resp = client.post("/costs") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
            contentType(ContentType.Application.Json)
            setBody(
                CostDTO(
                    title = "Café",
                    categoryId = catIdDoA,
                    value = BigDecimal("9.90"),
                    type = TransactionType.OUTFLOW
                )
            )
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)

        // A prova do H4: NADA foi gravado. GET /costs do B tem que vir com a lista VAZIA (P5: coleção
        // vazia é 200 + []). Com o bug antigo (insere antes de validar), haveria 1 custo órfão aqui.
        // Assertar a lista vazia é mais forte que assertar status: status só diz o que o handler
        // respondeu; a lista diz o que existe no banco, que é o fato alegado pelo teste.
        val listaB = client.get("/costs") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertTrue(listaB.body<List<CostDTOResponse>>().isEmpty())
    }

    // M5 — o 409 do StatusPages, agora ALCANÇÁVEL. O TODO antigo dizia que ExposedSQLException não
    // era testável porque o POST /users pré-checa email duplicado com 400 antes do INSERT. Verdade
    // — mas o PATCH /profile NÃO pré-checa nada: manda o UPDATE direto e deixa o uniqueIndex do
    // banco estourar. Esse é exatamente o caminho que virava 500 antes do StatusPages.
    @Test
    fun `PATCH profile com email de outro usuario retorna 409 (M5)`() = testApplication {
        bootH2()
        val client = jsonClient()

        client.registrarELogar("dono@exemplo.com", "donoM5")
        val tokenInvasor = client.registrarELogar("invasor@exemplo.com", "invasorM5")

        val resp = client.patch("/users/profile") {
            header(HttpHeaders.Authorization, "Bearer $tokenInvasor")
            contentType(ContentType.Application.Json)
            // Tenta assumir o e-mail que já é de outra conta -> viola o uniqueIndex de users.email.
            setBody(UserPatch(email = "dono@exemplo.com"))
        }

        // 409 Conflict = "o pedido é válido, mas colide com o estado atual do recurso". Não é 400
        // (o payload está bem formado) nem 500 (não é falha nossa): é conflito de dado.
        assertEquals(HttpStatusCode.Conflict, resp.status)
        // Corpo EXATO prova que veio do NOSSO handler de ExposedSQLException, não de outro 409.
        assertEquals("""{"error":"Registro em Conflito"}""", resp.bodyAsText())
    }

    // M6 — rate limit no /login. O H7 fechou a enumeração; isto fecha a força bruta na senha.
    @Test
    fun `login excede o rate limit e retorna 429 (M6)`() = testApplication {
        bootH2()
        val client = jsonClient()

        // O cadastro já consome 1 do balde (registrarELogar faz 1 login bem-sucedido).
        client.registrarELogar("brute@exemplo.com", "bruteM6")

        // Tentativas 2..5: senha errada -> 401 (o balde ainda tem ficha).
        repeat(4) { i ->
            val resp = client.post("/users/login") {
                contentType(ContentType.Application.Json)
                setBody(UserLogin(identifier = "brute@exemplo.com", password = "errada$i"))
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status, "tentativa ${i + 2} deveria ser 401")
        }

        // 6ª tentativa: balde vazio -> o plugin corta ANTES do handler. Note que o status muda de
        // 401 para 429 — e isso NÃO reabre a enumeração do H7: 429 depende só da CONTAGEM de
        // tentativas naquele par (IP, conta), não de a conta existir ou não.
        val bloqueado = client.post("/users/login") {
            contentType(ContentType.Application.Json)
            setBody(UserLogin(identifier = "brute@exemplo.com", password = "senha1234"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, bloqueado.status)
    }

    // M6 (chave composta) — o teste acima prova que o freio existe; ESTE prova que ele freia a
    // pessoa certa. Todos os clients do testApplication saem do mesmo remoteAddress, então aqui
    // eles são exatamente o cenário do NAT: várias contas, um IP só.
    @Test
    fun `rate limit e por (IP, conta) - conta vizinha no mesmo IP nao e afetada (M6)`() = testApplication {
        bootH2()
        val client = jsonClient()

        // Esgota o balde da conta A (1 login do cadastro + 4 erradas = 5).
        client.registrarELogar("nat-a@exemplo.com", "natA")
        repeat(4) { i ->
            client.post("/users/login") {
                contentType(ContentType.Application.Json)
                setBody(UserLogin(identifier = "nat-a@exemplo.com", password = "errada$i"))
            }
        }
        val aBloqueada = client.post("/users/login") {
            contentType(ContentType.Application.Json)
            setBody(UserLogin(identifier = "nat-a@exemplo.com", password = "senha1234"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, aBloqueada.status, "A deveria estar bloqueada")

        // Normalização: o MySQL casa e-mail sem diferenciar caixa, então MAIÚSCULA atinge a MESMA
        // conta. Se a chave não fizesse lowercase(), isto ganharia um balde novo -> bypass de graça.
        val aMaiuscula = client.post("/users/login") {
            contentType(ContentType.Application.Json)
            setBody(UserLogin(identifier = "NAT-A@EXEMPLO.COM", password = "senha1234"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, aMaiuscula.status, "variar a caixa não pode dar balde novo")

        // O ponto do teste: a conta B, no MESMO IP, tem balde próprio e entra normalmente.
        // Com a chave antiga (só IP), este login viria 429 — o usuário legítimo pagando pelo vizinho.
        val loginB = client.registrarELogarFull("nat-b@exemplo.com", "natB")
        assertTrue(loginB.token.isNotBlank(), "B, no mesmo IP, deveria logar normalmente")
    }

    // M9 — shape consistente: a MESMA categoria tem que sair igual no GET da lista e no GET por id.
    @Test
    fun `GET categories devolve userId preenchido nas do usuario e null nas globais (M9)`() = testApplication {
        bootH2()
        val client = jsonClient()

        val token = client.registrarELogar("shape@exemplo.com", "shapeM9")
        client.post("/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CategoryDTO(name = "Categoria com dono"))
        }

        val lista: List<Category> = client.get("/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

        val minha = assertNotNull(lista.find { it.name == "Categoria com dono" })
        // Antes do M9 este userId vinha null e era indistinguível de uma categoria global.
        assertNotNull(minha.userId, "categoria do usuário deveria trazer userId na listagem")

        // Contraprova: global continua com userId null (o null tem que significar "é global").
        val global = assertNotNull(lista.find { it.name == "Alimentação" })
        assertEquals(null, global.userId)
    }

    @Test
    fun `Teste de campos em branco na adição de categoria`() = testApplication {
        bootH2()
        val client = jsonClient()
        val token =
            client.registrarELogar("catbranco@exemplo.com", "catbranco")

        val resp = client.post("/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")   // passa pelo pedágio (senão 401)
            contentType(ContentType.Application.Json)
            // senão ContentNegotiation não serializa
            setBody(CategoryDTO(name = "", color = "banana"))
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val corpo = resp.bodyAsText()
        assertTrue(corpo.contains("obrigatório"))
        // Estava "Cor inválida" (nunca existiu na app) -> teste RED. A mensagem real do validate()
        // é "Formato de cor inválido (#RRGGBB esperado)". Asserto um trecho que DE FATO aparece.
        assertTrue(corpo.contains("cor inválido"))
    }

    @Test
    fun `POST costs com valor negativo e titulo vazio retorna 400 antes da query (H8)`() = testApplication {
        bootH2()
        val client = jsonClient()
        val token = client.registrarELogar("valneg@exemplo.com", "valneg")

        // categoryId = 999 NÃO existe. Se a validação de formato NÃO rodasse antes, o addCost iria ao
        // banco, não acharia a categoria e responderia 404. Como validamos o payload PRIMEIRO (título
        // vazio + valor negativo), a resposta é 400 e o banco nem é tocado -> prova a ordem "valida->query".
        val resp = client.post("/costs") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                CostDTO(
                    title = "",
                    categoryId = 999,
                    value = BigDecimal("-5.00"),
                    type = TransactionType.OUTFLOW
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val corpo = resp.bodyAsText()
        assertTrue(corpo.contains("obrigatório"))       // título vazio
        assertTrue(corpo.contains("maior que zero"))    // valor <= 0
    }

    // P2 — IDOR no PATCH /costs: o `where` do update protege QUAL custo é alterado, mas o
    // categoryId gravado vem do payload. Sem checagem, B move um custo dele para a categoria
    // privada de A — e o GET /costs faz JOIN, então B passa a LER nome/cor da categoria do A.
    @Test
    fun `PATCH costs nao pode mover custo para categoria de outro dono (P2)`() = testApplication {
        bootH2()
        val client = jsonClient()

        val tokenA = client.registrarELogar("p2a@exemplo.com", "p2userA")
        val tokenB = client.registrarELogar("p2b@exemplo.com", "p2userB")

        val catDoA = client.post("/categories") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(CategoryDTO(name = "Terapia do A"))
        }.body<Category>().id!!

        val catDoB = client.post("/categories") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
            contentType(ContentType.Application.Json)
            setBody(CategoryDTO(name = "Mercado do B"))
        }.body<Category>().id!!

        val custoDoB = client.post("/costs") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
            contentType(ContentType.Application.Json)
            setBody(
                CostDTO(
                    title = "Feira",
                    categoryId = catDoB,
                    value = BigDecimal("50.00"),
                    type = TransactionType.OUTFLOW
                )
            )
        }.body<CostDTOResponse>().id

        // B tenta apontar o custo DELE para a categoria do A.
        val resp = client.patch("/costs/$custoDoB") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
            contentType(ContentType.Application.Json)
            setBody(CostPatch(categoryId = catDoA))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status, "mover para categoria alheia deve ser 404")

        // Prova que NADA mudou: o custo continua na categoria do B.
        val depois = client.get("/costs/$custoDoB") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }.body<CostDTOResponse>()
        assertEquals(catDoB, depois.category.id, "o custo não pode ter mudado de categoria")
    }

    // Contraprova do P2: a correção não pode quebrar o caso legítimo — mover para categoria
    // GLOBAL (userId null) tem que continuar funcionando, como no addCost (H4).
    @Test
    fun `PATCH costs pode mover custo para categoria global (P2 - contraprova)`() = testApplication {
        bootH2()
        val client = jsonClient()

        val token = client.registrarELogar("p2g@exemplo.com", "p2global")
        val minhaCategoria = client.post("/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CategoryDTO(name = "Minha"))
        }.body<Category>().id!!

        val custo = client.post("/costs") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                CostDTO(
                    title = "Uber",
                    categoryId = minhaCategoria,
                    value = BigDecimal("30.00"),
                    type = TransactionType.OUTFLOW
                )
            )
        }.body<CostDTOResponse>().id

        // "Transporte" é uma das categorias globais semeadas no boot (userId null).
        val global = client.get("/categories") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<List<Category>>().first { it.name == "Transporte" }

        val resp = client.patch("/costs/$custo") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CostPatch(categoryId = global.id))
        }
        assertEquals(HttpStatusCode.OK, resp.status, "mover para categoria GLOBAL é legítimo")
    }

    // P3 — id não-numérico na URL é erro do CLIENTE (400), não do servidor. Antes, o `toInt()`
    // estourava NumberFormatException -> catch-all do StatusPages -> 500. O corpo prova que a
    // resposta saiu do NOSSO handler de BadRequestException, e não de outro lugar qualquer.
    @Test
    fun `id nao numerico na URL responde 400 e nao 500 (P3)`() = testApplication {
        bootH2()
        val client = jsonClient()
        val token = client.registrarELogar("p3@exemplo.com", "p3user")
        val auth: HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $token") }

        // Os 3 sites que usavam toInt() + os 2 que já usavam toIntOrNull(): agora todos no pathId().
        for (resp in listOf(
            client.delete("/costs/abc", auth),
            client.get("/costs/abc", auth),
            client.patch("/costs/abc") { auth(); contentType(ContentType.Application.Json); setBody(CostPatch(title = "x")) },
            client.delete("/categories/abc", auth),
            client.patch("/categories/abc") {
                auth(); contentType(ContentType.Application.Json); setBody(
                CategoryPatch(
                    name = "x"
                )
            )
            }
        )) {
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("""{"error":"Requisição Inválida"}""", resp.bodyAsText())
        }
    }

    // P6 — categoria global não é sua para excluir: 404 (não o 400 "Falha ao excluir a categoria",
    // que sugeria erro do servidor) e ela continua existindo. Prova também que o delete não depende
    // mais da pré-checagem: quem decide é o row count do deleteWhere.
    @Test
    fun `DELETE categoria global responde 404 e nao exclui (P6)`() = testApplication {
        bootH2()
        val client = jsonClient()
        val token = client.registrarELogar("p6@exemplo.com", "p6user")
        val auth: HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $token") }

        // userId null identifica a global (M9: o shape da lista traz o userId).
        val global = client.get("/categories", auth).body<List<Category>>().first { it.userId == null }

        assertEquals(HttpStatusCode.NotFound, client.delete("/categories/${global.id}", auth).status)
        assertTrue(
            client.get("/categories", auth).body<List<Category>>().any { it.id == global.id },
            "a categoria global tem que continuar existindo"
        )
    }

    // ---- H9: refresh token ----

    @Test
    fun `login gera refresh e refresh devolve novo par funcional (H9)`() = testApplication {
        bootH2()
        val client = jsonClient()

        val login = client.registrarELogarFull("refresh1@exemplo.com", "refresh1")
        assertTrue(login.refreshToken.isNotBlank(), "login deveria emitir um refresh token")

        val resp = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(login.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, resp.status)

        val par = resp.body<TokenPair>()
        assertTrue(par.accessToken.isNotBlank())
        // Rotação: o refresh devolvido é NOVO, diferente do que foi usado.
        assertNotEquals(login.refreshToken, par.refreshToken)

        // O access token vindo do refresh é válido: entra numa rota protegida (globais seedadas -> 200).
        val protegida = client.get("/categories") {
            header(HttpHeaders.Authorization, "Bearer ${par.accessToken}")
        }
        assertEquals(HttpStatusCode.OK, protegida.status)
    }

    @Test
    fun `refresh reusado revoga a familia inteira (H9)`() = testApplication {
        bootH2()
        val client = jsonClient()

        val r1 = client.registrarELogarFull("refresh2@exemplo.com", "refresh2").refreshToken

        // Uso legítimo de R1 -> recebe R2 (mesma família).
        val primeiro = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(r1))
        }
        assertEquals(HttpStatusCode.OK, primeiro.status)
        val r2 = primeiro.body<TokenPair>().refreshToken

        // Reuso de R1 (já rotacionado = revogado) -> 401. Prova a rotação: o antigo não vale mais.
        val reuso = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(r1))
        }
        assertEquals(HttpStatusCode.Unauthorized, reuso.status)

        // Detecção de roubo: o reuso acima matou a FAMÍLIA -> R2, mesmo nunca tendo sido reusado,
        // agora também é 401. É o "scorched earth" que protege contra token roubado.
        val familiaMorta = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(r2))
        }
        assertEquals(HttpStatusCode.Unauthorized, familiaMorta.status)
    }

    @Test
    fun `logout revoga o refresh token (H9)`() = testApplication {
        bootH2()
        val client = jsonClient()

        val login = client.registrarELogarFull("logout1@exemplo.com", "logout1")

        val logout = client.post("/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer ${login.token}")
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(login.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, logout.status)

        // Depois do logout, o refresh não troca mais por par nenhum.
        val depois = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(login.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, depois.status)
    }

    @Test
    fun `refresh com token desconhecido retorna 401 (H9)`() = testApplication {
        bootH2()
        val client = jsonClient()

        val resp = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest("token-que-nunca-foi-emitido"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `cadastro de terceiro nao derruba o login da vítima (S1)`() = testApplication {
        bootH2()

        val client = jsonClient()

        client.registrarELogarFull("vitima-s1@exemplo.com", "vitima-s1")

        client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody(
                UserDTO(
                    email = "atacante-s1@exemplo.com",
                    password = "senha1234",
                    username = "vitima-s1@exemplo.com"
                )
            )
        }

        val login = client.post("/users/login") {
            contentType(ContentType.Application.Json)
            setBody(UserLogin(identifier = "vitima-s1@exemplo.com", password = "senha1234"))

        }

        assertEquals(HttpStatusCode.OK, login.status, "o cadastro de um terceiro derrubou o login da vítima")
    }

    @Test
    fun `cadastro com username repetido nao derruba o login por username (S1)`() = testApplication {
        bootH2()
        val client = jsonClient()

        client.registrarELogarFull("vitima-s1b@exemplo.com", "vitima-s1b")

        // Atacante cadastra com o MESMO username da vítima. SEM @.
        client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody(UserDTO(
                email = "atacante-s1b@exemplo.com",
                password = "senha1234",
                username = "vitima-s1b"
            ))
        }

        // A vítima continua entrando pelo USERNAME dela.
        val login = client.post("/users/login") {
            contentType(ContentType.Application.Json)
            setBody(UserLogin(identifier = "vitima-s1b", password = "senha1234"))
        }
        assertEquals(HttpStatusCode.OK, login.status, "username duplicado derrubou o login por username")
    }

    @Test
    fun `cadastro recusa username com arroba, em branco e repetido (S1 - mecanismo)`() = testApplication {
        bootH2()
        val client = jsonClient()

        // Com @ -> 400 pela validação, sem tocar no banco.
        val comArroba = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody(UserDTO(email = "mec1@exemplo.com", password = "senha1234", username = "tem@arroba"))
        }
        assertEquals(HttpStatusCode.BadRequest, comArroba.status, "username com @ deveria ser recusado")

        // Só espaços -> 400. É o caso que isEmpty() deixaria passar.
        val emBranco = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody(UserDTO(email = "mec2@exemplo.com", password = "senha1234", username = "   "))
        }
        assertEquals(HttpStatusCode.BadRequest, emBranco.status, "username em branco deveria ser recusado")

        // Repetido -> 409, vindo do uniqueIndex via handler de ExposedSQLException.
        client.registrarELogarFull("mec3@exemplo.com", "username-do-mec3")
        val repetido = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody(UserDTO(email = "mec4@exemplo.com", password = "senha1234", username = "username-do-mec3"))
        }
        assertEquals(HttpStatusCode.Conflict, repetido.status, "username repetido deveria dar 409")
    }
}