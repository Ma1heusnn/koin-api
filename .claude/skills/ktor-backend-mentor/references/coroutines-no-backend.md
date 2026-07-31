# Coroutines no backend: o que realmente importa num servidor Ktor

Este é o tema onde mais júnior se perde e onde o Ktor mais difere do PHP tradicional (um processo/thread por request). Ensine o modelo mental com calma — o resto decorre dele.

## Modelo mental: o garçom

Um restaurante (servidor) com poucos garçons (threads do Netty). No modelo bloqueante, o garçom leva seu pedido à cozinha **e fica parado esperando** o prato sair — com 4 garçons, o 5º cliente espera na porta mesmo com mesas vazias. No modelo do Ktor, o garçom anota o pedido, entrega à cozinha e **vai atender outra mesa**; quando o prato fica pronto, qualquer garçom livre o leva. 

Tradução técnica: cada requisição vira uma **coroutine** — barata (milhares coexistem), e quando ela `suspende` (esperando banco, HTTP externo, delay), a thread fica LIVRE para outras requisições. **Suspender ≠ bloquear.** Bloquear a thread é amarrar o garçom na cozinha.

Consequência que o júnior precisa gravar: o handler de rota já É uma coroutine. Você não "cria threads" no Ktor; você escreve funções `suspend` e o runtime orquestra.

## A regra de ouro dos dispatchers

Nem tudo sabe suspender. APIs bloqueantes (JDBC, `File`, libs Java antigas) precisam rodar num pool de threads separado, feito para esperar:

| O que você vai chamar | Como chamar |
|---|---|
| Função `suspend` nativa (HttpClient do Ktor, `delay`, Exposed via `newSuspendedTransaction`) | Direto — já coopera com o sistema |
| API **bloqueante** (JDBC puro, arquivos, driver antigo) | `withContext(Dispatchers.IO) { ... }` |
| Cálculo pesado de CPU (processar imagem, criptografia em lote) | `withContext(Dispatchers.Default) { ... }` |

```kotlin
get("/relatorio") {
    // Ler arquivo é IO bloqueante -> muda de "pista" para não travar o Netty:
    val conteudo = withContext(Dispatchers.IO) { File("relatorio.csv").readText() }
    call.respondText(conteudo)
}
```

## Paralelismo: quando duas esperas podem virar uma

Sequencial soma tempos; paralelo fica no maior. Se as chamadas são **independentes**, dispare juntas:

```kotlin
get("/dashboard") {
    // coroutineScope: cria um escopo que SÓ termina quando os filhos terminam,
    // e se um falhar, cancela os irmãos e propaga o erro. Isso é structured
    // concurrency: nenhuma tarefa fica órfã rodando por aí.
    val dashboard = coroutineScope {
        val vendas = async { vendasService.resumo() }      // dispara e NÃO espera ainda
        val estoque = async { estoqueService.resumo() }    // dispara em paralelo
        Dashboard(vendas.await(), estoque.await())         // agora espera os dois
    }
    call.respond(dashboard)
}
```

Regra didática: `async` para quando você **precisa do resultado**; sempre dentro de um escopo (`coroutineScope`), nunca solto.

## Timeout em chamadas externas

Serviço de terceiro lento não pode segurar sua requisição para sempre:

```kotlin
val cotacao = withTimeoutOrNull(3.seconds) { cotacaoClient.buscar(moeda) }
    ?: return@get call.respond(HttpStatusCode.GatewayTimeout, ErroResponse("cotação indisponível"))
```

## Cancelamento: o cliente desistiu

Se o cliente fecha a conexão, o Ktor **cancela a coroutine** daquela requisição — as suspensões seguintes lançam `CancellationException` e o trabalho para. Isso é feature (não desperdiça recurso com quem foi embora). Duas implicações a ensinar: (1) nunca engula `CancellationException` num `catch (e: Exception)` genérico sem relançar; (2) trabalho que DEVE continuar mesmo se o cliente sumir (ex.: registrar um pagamento já confirmado) não pode viver no escopo da requisição — veja abaixo.

## Fire-and-forget do jeito certo

⚠️ O júnior tenta `GlobalScope.launch { ... }`. Problemas: vive fora de qualquer supervisão (vaza se acumular), erro some sem log, nada cancela no shutdown. O jeito certo: um escopo que a APLICAÇÃO possui e encerra:

```kotlin
// Escopo da aplicação: SupervisorJob = a falha de UMA tarefa não mata as irmãs.
val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

monitor.subscribe(ApplicationStopping) { backgroundScope.cancel() }  // shutdown limpo

post("/pedidos") {
    val pedido = pedidoService.criar(call.receive())
    call.respond(HttpStatusCode.Created, pedido)   // responde JÁ...
    backgroundScope.launch {                        // ...e-mail vai em segundo plano,
        runCatching { emailService.confirmacao(pedido) }   // sobrevive ao fim da request.
            .onFailure { call.application.log.error("email falhou p/ pedido ${pedido.id}", it) }
    }
}
```

## Proibições com o porquê (para citar em code review)

- **`runBlocking` em handler**: transforma suspensão em bloqueio — amarra o garçom. Sob carga, o servidor inteiro (todas as rotas) degrada. `runBlocking` só existe legitimamente em `fun main` de scripts e em alguns testes.
- **`Thread.sleep` em código suspend**: bloqueia a thread. Use `delay()` — suspende sem ocupar ninguém.
- **`GlobalScope`**: tarefa órfã, sem cancelamento estruturado, sem dono. Use escopo da aplicação (acima) ou `coroutineScope`.
- **`catch (e: Exception)` que engole `CancellationException`**: quebra o cancelamento cooperativo; a coroutine "zumbi" continua rodando após o cliente sumir. Se capturar Exception amplo, relance cancelamento: `if (e is CancellationException) throw e`.

## Diagnóstico: "meu servidor trava/fica lento sob carga"

Roteiro de investigação a ensinar: (1) procure JDBC/IO fora de `Dispatchers.IO` (causa nº 1 — thread do Netty presa); (2) procure `runBlocking`; (3) transação segurando conexão durante trabalho lento (HTTP externo dentro de `dbQuery`); (4) pool do Hikari pequeno demais para a concorrência real; (5) chamadas em série que podiam ser paralelas. Um `jstack` (thread dump) mostrando threads `eventLoopGroup` em estado RUNNABLE dentro de driver JDBC confirma o diagnóstico nº 1 — mostre isso ao usuário como um sênior mostraria.
