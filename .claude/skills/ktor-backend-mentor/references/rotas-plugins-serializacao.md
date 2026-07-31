# Rotas, plugins, serialização e erros HTTP

## Modelo mental: o pipeline de plugins

Analogia para ensinar ANTES da sintaxe: uma requisição no Ktor é um carro numa estrada com pedágios. Cada `install(Plugin)` adiciona um pedágio, **na ordem de instalação**. O carro (request) passa por todos até chegar ao destino (sua rota), e a resposta volta pelo mesmo caminho. `CallLogging` anota a passagem, `ContentNegotiation` traduz JSON⇄objeto, `Authentication` barra quem não tem crachá, `StatusPages` é o guincho que socorre quando algo explode no caminho.

Consequência prática que o júnior precisa ouvir: **sem `ContentNegotiation` instalado, `call.receive<MeuDto>()` falha** — o Ktor não sabe converter JSON em objeto sozinho.

## ContentNegotiation + kotlinx.serialization

```kotlin
// plugins/Serialization.kt
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            // Cliente mandou campo extra que você não conhece? Ignora em vez de
            // dar erro 400. Essencial p/ evoluir a API sem quebrar clientes antigos.
            ignoreUnknownKeys = true
            // JSON legível em dev; em produção pode desligar p/ economizar bytes.
            prettyPrint = true
        })
    }
}
```

## DTOs: o contrato da API

Regra de ouro (inegociável): **entidade de banco nunca sai pela API**. DTO é o contrato público; entidade é detalhe interno. Motivos para ensinar: (1) tabela ganhou coluna `senha_hash`? Sem DTO, ela vaza no JSON automaticamente; (2) você pode mudar o banco sem quebrar clientes; (3) request e response quase nunca têm o mesmo formato (ninguém envia `id` no POST).

```kotlin
// produto/ProdutoDtos.kt
import kotlinx.serialization.Serializable

// Um DTO para entrada, outro para saída — formatos diferentes, contratos diferentes.
@Serializable
data class CriarProdutoRequest(
    val nome: String,
    val precoCentavos: Long,   // dinheiro em centavos (Long), nunca Double:
                               // ponto flutuante acumula erro de arredondamento.
    val estoque: Int = 0,
)

@Serializable
data class ProdutoResponse(
    val id: Long,
    val nome: String,
    val precoCentavos: Long,
    val estoque: Int,
)
```

## Rotas: organização por feature

Cada feature expõe uma extension function de `Route` que recebe suas dependências. Isso mantém `configureRouting` como um índice legível e facilita teste (dá para montar só uma rota com um service fake).

```kotlin
// produto/ProdutoRoutes.kt
fun Route.produtoRoutes(service: ProdutoService) {
    route("/produtos") {

        get {
            // Query params são sempre String? — converter e validar é SEU trabalho.
            val pagina = call.request.queryParameters["pagina"]?.toIntOrNull() ?: 1
            call.respond(service.listar(pagina))
        }

        get("{id}") {
            // Falhou a conversão? Requisição malformada -> 400, com mensagem clara.
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErroResponse("id inválido"))

            val produto = service.buscar(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErroResponse("produto $id não existe"))

            call.respond(produto)
        }

        post {
            val req = call.receive<CriarProdutoRequest>()  // JSON -> objeto (via ContentNegotiation)
            val erros = req.validar()
            if (erros.isNotEmpty())
                return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrosResponse(erros))

            val criado = service.criar(req)
            // 201 Created é o status correto p/ criação — 200 genérico esconde semântica.
            call.respond(HttpStatusCode.Created, criado)
        }

        delete("{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErroResponse("id inválido"))
            if (service.remover(id)) call.respond(HttpStatusCode.NoContent)  // 204: sucesso sem corpo
            else call.respond(HttpStatusCode.NotFound, ErroResponse("produto $id não existe"))
        }
    }
}

// Registro central — um índice de features:
fun Application.configureRouting() {
    routing {
        produtoRoutes(ProdutoService(ProdutoRepositoryExposed()))
        // usuarioRoutes(...), pedidoRoutes(...)
    }
}
```

## Validação de entrada

Ensine o princípio antes da técnica: **o servidor nunca confia no cliente**. Validação no frontend é UX; validação no backend é segurança e integridade. Padrão simples e testável — função de validação no próprio DTO:

```kotlin
fun CriarProdutoRequest.validar(): List<String> = buildList {
    if (nome.isBlank()) add("nome é obrigatório")
    if (nome.length > 120) add("nome deve ter no máximo 120 caracteres")
    if (precoCentavos <= 0) add("preço deve ser positivo")
    if (estoque < 0) add("estoque não pode ser negativo")
}
```

Para projetos maiores, mencione o plugin `RequestValidation` do Ktor (valida no pipeline e lança `RequestValidationException`, capturável no StatusPages) — mesmo princípio, mais automação.

## StatusPages: tratamento de erro centralizado

Sem isso, exceção vira 500 sem corpo e o stacktrace pode vazar. Com isso, todo erro tem UMA porta de saída, com formato consistente:

```kotlin
@Serializable data class ErroResponse(val mensagem: String)
@Serializable data class ErrosResponse(val erros: List<String>)

// Exceções de domínio: o service lança semanticamente, o StatusPages traduz p/ HTTP.
class NaoEncontradoException(msg: String) : RuntimeException(msg)
class ConflitoException(msg: String) : RuntimeException(msg)

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<NaoEncontradoException> { call, e ->
            call.respond(HttpStatusCode.NotFound, ErroResponse(e.message ?: "não encontrado"))
        }
        exception<ConflitoException> { call, e ->
            call.respond(HttpStatusCode.Conflict, ErroResponse(e.message ?: "conflito"))
        }
        exception<BadRequestException> { call, e ->  // ex.: JSON malformado no receive()
            call.respond(HttpStatusCode.BadRequest, ErroResponse("requisição inválida"))
        }
        exception<Throwable> { call, e ->
            // Log completo NO SERVIDOR (com stacktrace, p/ você investigar)...
            call.application.log.error("Erro não tratado em ${call.request.uri}", e)
            // ...mensagem genérica PARA O CLIENTE (stacktrace revela estrutura interna
            // do sistema — informação de ataque de graça).
            call.respond(HttpStatusCode.InternalServerError, ErroResponse("erro interno"))
        }
    }
}
```

## Status codes: cola rápida para ensinar

| Código | Quando | Erro comum de júnior |
|---|---|---|
| 200 | Sucesso genérico com corpo | Usar para TUDO |
| 201 | Recurso criado (POST) | Devolver 200 na criação |
| 204 | Sucesso sem corpo (DELETE) | Devolver 200 com corpo vazio |
| 400 | Requisição malformada (JSON quebrado, id não numérico) | Confundir com 422 |
| 401 | Não autenticado (sem/inválido token) | Confundir com 403 |
| 403 | Autenticado mas sem permissão | Confundir com 401 |
| 404 | Recurso não existe | Devolver 200 com `null` |
| 409 | Conflito de estado (e-mail já cadastrado) | Devolver 400 genérico |
| 422 | Sintaxe ok, semântica inválida (preço negativo) | Devolver 400 |
| 500 | Bug/falha do servidor | Vazar stacktrace no corpo |

## Rotas type-safe (Resources) — mencionar quando fizer sentido

Para APIs maiores, o plugin `ktor-server-resources` permite declarar rotas como classes (`@Resource("/produtos/{id}")`), com parâmetros tipados verificados em compilação. Apresente como evolução natural depois que o usuário dominar o routing básico com strings — introduzir cedo demais adiciona conceito sem dor sentida.
