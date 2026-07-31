# Persistência: Exposed + HikariCP + Flyway

## Por que Exposed (contextualizar a escolha)

Exposed é o ORM/SQL-DSL da JetBrains: você escreve consultas em Kotlin tipado e o compilador pega erro de coluna/tipo antes do runtime. Alternativas a citar no trade-off: JDBC puro (controle total, muito boilerplate), Hibernate/JPA (padrão no mundo Java/Spring, pesado e cheio de "mágica" para quem está aprendendo), jOOQ (excelente, licença paga para bancos comerciais). Exposed tem dois sabores — **DSL** (consultas explícitas, parece SQL) e **DAO** (objetos ativos, parece ORM clássico). **Ensine com DSL**: o júnior enxerga o SQL por trás e aprende o que realmente acontece.

⚠️ **Nota de versão:** Exposed 1.x (2025+) reorganizou pacotes (`org.jetbrains.exposed.v1.*`) e artefatos (ex.: `exposed-jdbc` → API R2DBC separada). O código abaixo usa a API estável da linha 0.x, ainda amplamente usada. Antes de gerar código para o projeto do usuário, confira a versão no `libs.versions.toml` dele e adapte; se tiver web, confira a doc atual.

## Pool de conexões: o porquê antes do código

Analogia: abrir conexão com o banco é como contratar um funcionário para atender UM cliente e demiti-lo em seguida — caro e lento (handshake TCP + autenticação a cada vez). O **pool** (HikariCP) mantém um time fixo de conexões prontas; cada requisição pega uma emprestada e devolve. Consequência prática: transação longa = conexão presa = outras requisições esperando na fila.

```kotlin
// plugins/Databases.kt
fun Application.configureDatabases() {
    val config = HikariConfig().apply {
        jdbcUrl = environment.config.property("db.url").getString()
        username = environment.config.property("db.user").getString()
        password = environment.config.property("db.password").getString()
        maximumPoolSize = 10  // regra prática inicial; ajuste com métricas reais
    }
    val dataSource = HikariDataSource(config)

    // Migrations ANTES de conectar o app: o schema sempre está no estado esperado.
    Flyway.configure().dataSource(dataSource).load().migrate()

    Database.connect(dataSource)
}
```

## Definindo tabelas (DSL)

```kotlin
// produto/ProdutoRepository.kt
object Produtos : Table("produtos") {
    val id = long("id").autoIncrement()
    val nome = varchar("nome", 120)
    val precoCentavos = long("preco_centavos")
    val estoque = integer("estoque")
    val criadoEm = timestamp("criado_em")
    override val primaryKey = PrimaryKey(id)
}
```

Ponto didático: este `object` é o **espelho Kotlin** da tabela — quem cria a tabela de verdade é a migration (fonte da verdade do schema). Manter os dois em sincronia é disciplina sua; o Flyway garante que todo ambiente executou as mesmas migrations na mesma ordem.

## A pegadinha nº 1: transações bloqueiam

Este é O conceito que separa quem entende Ktor de quem copia código — explique com calma:

JDBC é **bloqueante**: enquanto a query roda, a thread fica parada esperando. O Ktor atende milhares de requisições com POUCAS threads (event loop do Netty); se você bloquear essas threads com JDBC, o servidor inteiro engasga — inclusive rotas que nem usam banco.

A solução: rodar o código bloqueante em um pool de threads separado, feito para isso (`Dispatchers.IO`). O Exposed já embala isso:

```kotlin
// Helper do módulo: toda query do repositório passa por aqui.
// newSuspendedTransaction = transaction do Exposed que:
// 1. roda o bloco em Dispatchers.IO (threads p/ trabalho bloqueante),
// 2. suspende a coroutine da requisição em vez de bloquear a thread do Netty,
// 3. abre/commita/faz rollback da transação automaticamente.
suspend fun <T> dbQuery(block: () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }
```

⚠️ Erro clássico a apontar: usar `transaction { }` (a versão síncrona) direto dentro de um handler `suspend`. Compila, funciona no teste local com 1 usuário, e derruba o servidor sob carga. Veja `erros-de-junior.md` item 2.

## Repository: interface + implementação

Por que interface: o service depende de "algo que salva produtos", não de "Exposed". Nos testes você injeta um fake em memória sem tocar em banco. É inversão de dependência com propósito concreto, não cerimônia.

```kotlin
interface ProdutoRepository {
    suspend fun listar(pagina: Int, tamanho: Int): List<Produto>
    suspend fun buscar(id: Long): Produto?
    suspend fun criar(nome: String, precoCentavos: Long, estoque: Int): Produto
    suspend fun remover(id: Long): Boolean
}

class ProdutoRepositoryExposed : ProdutoRepository {

    // Conversão linha->domínio num lugar só: mudou a tabela, mexe aqui, não em 10 lugares.
    private fun ResultRow.paraProduto() = Produto(
        id = this[Produtos.id],
        nome = this[Produtos.nome],
        precoCentavos = this[Produtos.precoCentavos],
        estoque = this[Produtos.estoque],
    )

    override suspend fun listar(pagina: Int, tamanho: Int): List<Produto> = dbQuery {
        Produtos.selectAll()
            .orderBy(Produtos.id)                    // paginação SEM order by = ordem imprevisível
            .limit(tamanho).offset(((pagina - 1) * tamanho).toLong())
            .map { it.paraProduto() }
    }

    override suspend fun buscar(id: Long): Produto? = dbQuery {
        Produtos.selectAll().where { Produtos.id eq id }   // eq gera SQL PARAMETRIZADO:
            .singleOrNull()?.paraProduto()                 // SQL injection impossível aqui.
    }

    override suspend fun criar(nome: String, precoCentavos: Long, estoque: Int): Produto = dbQuery {
        val id = Produtos.insert {
            it[Produtos.nome] = nome
            it[Produtos.precoCentavos] = precoCentavos
            it[Produtos.estoque] = estoque
            it[criadoEm] = Instant.now()
        } get Produtos.id
        Produto(id, nome, precoCentavos, estoque)
    }

    override suspend fun remover(id: Long): Boolean = dbQuery {
        Produtos.deleteWhere { Produtos.id eq id } > 0   // retorno = linhas afetadas
    }
}
```

Ponte para quem vem de PHP/PDO: `where { Produtos.id eq id }` é o equivalente do prepared statement com placeholder — só que aqui é o padrão inevitável, não uma escolha. Concatenação de SQL nem existe como caminho fácil.

## Atomicidade: várias escritas, uma transação

```kotlin
// Ou tudo acontece, ou nada acontece — é para isso que transação existe.
suspend fun transferirEstoque(deId: Long, paraId: Long, qtd: Int) = dbQuery {
    val origem = Produtos.selectAll().where { Produtos.id eq deId }.single()
    require(origem[Produtos.estoque] >= qtd) { "estoque insuficiente" }  // lançou -> rollback automático
    Produtos.update({ Produtos.id eq deId }) { it[estoque] = origem[Produtos.estoque] - qtd }
    Produtos.update({ Produtos.id eq paraId }) {
        with(SqlExpressionBuilder) { it[estoque] = estoque + qtd }
    }
}
```

⚠️ Anti-padrão a vigiar: chamar `dbQuery` DENTRO de outro `dbQuery` sem necessidade, ou fazer trabalho lento (chamada HTTP externa!) dentro da transação — a conexão fica presa no pool o tempo todo.

## Migrations com Flyway

Regra: schema muda SÓ por migration, nunca por `CREATE TABLE` manual no banco. Arquivos em `src/main/resources/db/migration/`, nomeados `V<numero>__<descricao>.sql` (dois underscores!). Flyway registra o que já rodou e aplica só o que falta — todo ambiente converge para o mesmo schema.

```sql
-- V1__criar_produtos.sql
CREATE TABLE produtos (
    id BIGSERIAL PRIMARY KEY,
    nome VARCHAR(120) NOT NULL,
    preco_centavos BIGINT NOT NULL CHECK (preco_centavos > 0),
    estoque INT NOT NULL DEFAULT 0 CHECK (estoque >= 0),
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Ponto didático: os `CHECK`s duplicam a validação do DTO de propósito — o banco é a última linha de defesa contra bug seu ou acesso direto de outro sistema.
