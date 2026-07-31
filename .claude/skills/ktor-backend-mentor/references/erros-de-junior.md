# Catálogo de erros de júnior — o radar do mentor

Consulte este arquivo SEMPRE que revisar código do usuário e ao ensinar tópicos de risco. Formato de cada item: sintoma → por que quebra de verdade → correção. Ao apontar um erro, use o tom do protocolo: reconheça o que está certo primeiro, explique o porquê real (não "é má prática"), e generalize a lição.

## 1. `runBlocking` dentro de handler
**Sintoma:** funciona no teste local; sob carga, TODAS as rotas ficam lentas.
**Por quê:** o handler já é uma coroutine numa thread do Netty; `runBlocking` amarra essa thread até o fim do bloco. Poucas threads amarradas = servidor inteiro engasgado.
**Correção:** o handler já é `suspend` — chame funções `suspend` diretamente. Se a API é bloqueante, `withContext(Dispatchers.IO)`.

## 2. JDBC/IO bloqueante fora de `Dispatchers.IO`
**Sintoma:** igual ao item 1; `transaction { }` síncrono chamado num handler suspend.
**Por quê:** query JDBC bloqueia a thread durante toda a espera do banco.
**Correção:** `newSuspendedTransaction(Dispatchers.IO) { ... }` (o helper `dbQuery`) para Exposed; `withContext(Dispatchers.IO)` para arquivos e afins.

## 3. `GlobalScope.launch` para tarefa em segundo plano
**Sintoma:** e-mails que às vezes não saem, erros que somem sem log, tarefas rodando após shutdown.
**Por quê:** coroutine órfã — sem supervisão, sem cancelamento estruturado, sem dono.
**Correção:** escopo de aplicação (`CoroutineScope(SupervisorJob() + ...)`) cancelado no `ApplicationStopping`, com `runCatching` + log dentro da tarefa.

## 4. Entidade/linha do banco exposta como resposta da API
**Sintoma:** o mesmo tipo anotado para Exposed E `@Serializable` saindo no JSON.
**Por quê:** acoplamento total contrato↔schema. Coluna nova sensível (hash, custo, margem) vaza automaticamente; mudar o banco quebra clientes.
**Correção:** DTOs de request/response separados + função de mapeamento. É boilerplate que compra liberdade.

## 5. Senha em texto plano ou hash rápido (MD5/SHA)
**Por quê:** vazou o banco, vazaram as contas — inclusive as senhas que usuários repetem em outros serviços. Hash rápido cai para GPU/rainbow table em horas.
**Correção:** BCrypt (custo ≥ 12) via `at.favre.lib:bcrypt`. Nunca logar senha, nem no debug.

## 6. Secrets hardcoded / commitados
**Sintoma:** `val secret = "minha-chave-jwt"` no código; `.env` no Git.
**Por quê:** histórico do Git é eterno; chave vazada = qualquer um assina tokens válidos.
**Correção:** variáveis de ambiente lidas via config (`"$JWT_SECRET:..."` no yaml). Se já commitou: trocar a chave, não só apagar o arquivo.

## 7. `catch (e: Exception) { }` silencioso
**Sintoma:** "o sistema não dá erro, mas também não funciona".
**Por quê:** o erro não sumiu — só ficou invisível. Debugging vira arqueologia. E engolir `CancellationException` cria coroutines-zumbi (ver item 8).
**Correção:** capture o MAIS específico possível; sempre logue com contexto; deixe o inesperado subir até o StatusPages, que loga e responde 500 limpo.

## 8. Engolir `CancellationException`
**Sintoma:** trabalho continua rodando depois que o cliente desconectou; timeouts que "não funcionam".
**Por quê:** cancelamento em coroutines é cooperativo e viaja como exceção; capturá-la sem relançar quebra o mecanismo.
**Correção:** em catch amplo: `if (e is CancellationException) throw e` antes de tratar o resto.

## 9. Status 200 para tudo (inclusive erro)
**Sintoma:** `{"sucesso": false}` com HTTP 200; 404 devolvendo `null` com 200.
**Por quê:** HTTP É o protocolo de status. Clientes, caches, monitoramento e retries decidem pelo código — 200 mentiroso quebra tudo isso.
**Correção:** tabela de status em `rotas-plugins-serializacao.md`; erros de domínio → exceções → StatusPages.

## 10. Vazar stacktrace/detalhe interno no corpo do erro
**Por quê:** stacktrace revela libs, versões, paths e queries — mapa de graça para atacante.
**Correção:** StatusPages: log completo no servidor, mensagem genérica no cliente.

## 11. `!!` espalhado
**Sintoma:** `call.parameters["id"]!!.toLong()` e NPEs em produção.
**Por quê:** `!!` é aposta ("juro que não é null") — quando perde, é crash 500. O sistema de tipos do Kotlin existe justamente para evitar isso.
**Correção:** `?: return@get call.respond(BadRequest, ...)`, `?.let`, valores default. `!!` aceitável só quando invariante é garantida logo acima (e mesmo assim, prefira `checkNotNull` com mensagem).

## 12. N+1 queries
**Sintoma:** listar 50 pedidos dispara 51 queries (1 da lista + 1 por item para buscar o cliente). Lento e piora com o volume.
**Por quê:** query dentro de loop; latência de rede multiplicada.
**Correção:** JOIN na consulta ou busca em lote (`where { id inList ids }`) e montagem em memória.

## 13. Trabalho lento dentro da transação
**Sintoma:** chamada HTTP externa ou processamento pesado dentro de `dbQuery`; pool esgota sob carga.
**Por quê:** a conexão fica emprestada (e locks segurados) durante toda a lentidão alheia.
**Correção:** transação curta: leia → feche → chame o externo → abra outra p/ gravar. Se precisar de atomicidade com sistema externo, isso é problema de design (outbox pattern) — sinal de evoluir a arquitetura, não de esticar a transação.

## 14. Validar só no frontend
**Por quê:** o frontend é sugestão; `curl` fala com sua API sem passar por ele.
**Correção:** servidor valida TUDO (DTO `.validar()` / RequestValidation) + constraints no banco como última linha.

## 15. Estado mutável compartilhado entre requests
**Sintoma:** `val cache = mutableListOf<...>()` no topo do arquivo; contadores `var`; dados "trocando de usuário" esporadicamente.
**Por quê:** múltiplas coroutines em múltiplas threads acessando a mesma estrutura sem sincronização = race condition — o pior tipo de bug: intermitente.
**Correção:** estado por-request fica no handler; estado global legítimo usa estruturas concorrentes (`ConcurrentHashMap`, `Mutex`) ou, melhor, mora no banco/cache externo.

## 16. Recriar objetos caros por request
**Sintoma:** `HttpClient(...)`, `HikariDataSource(...)` ou `Json { }` instanciados DENTRO do handler.
**Por quê:** são objetos com pools e recursos próprios — criar por request vaza conexões e mata performance. (O `HttpClient` do Ktor inclusive precisa de `close()`.)
**Correção:** criar uma vez (módulo/DI) e injetar; fechar no shutdown da aplicação.

## 17. Esquecer ContentNegotiation (server ou client de teste)
**Sintoma:** 415 Unsupported Media Type; `NoTransformationFoundException` no teste.
**Por quê:** sem o plugin, o Ktor não sabe converter JSON⇄objeto — nem no servidor, nem no client de `testApplication`.
**Correção:** `install(ContentNegotiation) { json() }` nos dois lados. Nos testes, é o `createClient { install(...) }`.
