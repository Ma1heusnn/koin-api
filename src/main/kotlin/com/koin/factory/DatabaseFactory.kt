package com.koin.factory

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import com.koin.services.categories.CategoryService
import javax.sql.DataSource

object DatabaseFactory {

    // Conexão que dbQuery deve usar. É estado global mutável DE PROPÓSITO (ver trade-off no relatório):
    // DatabaseFactory é um `object` (singleton na JVM), então isto é compartilhado por todo o processo.
    // `lateinit`: se dbQuery rodar antes de init(), estoura UninitializedPropertyAccessException com
    // mensagem clara (fail-fast) — bem melhor que cair silenciosamente no "default global" do Exposed.
    // `private set`: só o próprio init() define quem é o banco; ninguém de fora troca a conexão.
    lateinit var database: Database
        private set

    fun getEnvData(): DataSource {
        // Segredos saem do código e vêm do ambiente. `?: error(...)` = fail-fast:
        // sem a env var o boot para com mensagem clara, em vez de subir com valor errado.
        // System.getenv lê o ambiente REAL do processo (não um arquivo .env).
        val jdbcUrl = System.getenv("DB_URL") ?: error("DB_URL não definida")
        val user = System.getenv("DB_USER") ?: error("DB_USER não definida")
        val password = System.getenv("DB_PASSWORD") ?: error("DB_PASSWORD não definida")

        // Pool de conexões: abrir uma conexão JDBC é CARO (handshake TCP + autenticação + setup,
        // dezenas de ms). Sem pool, cada request pagaria esse custo e o banco viraria gargalo sob
        // carga. O Hikari mantém um punhado de conexões PRONTAS e as empresta a cada query,
        // devolvendo ao pool no fim — a request só paga o "aluguel", não a "construção".
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            driverClassName = "com.mysql.cj.jdbc.Driver"

            // ~10 é um bom teto para a maioria das apps. Contra-intuitivo p/ quem vem de PHP:
            // MAIS conexões não é mais rápido — passar do ponto gera contenção no banco e piora.
            // Poucas conexões bem reaproveitadas batem muitas ociosas.
            maximumPoolSize = 10

            // Quem controla begin/commit/rollback é o Exposed. Se o driver comitasse sozinho a cada
            // statement (auto-commit), a transação do Exposed perderia o sentido — um erro no meio
            // não faria rollback do que já rodou. Desligamos para o Exposed mandar na transação.
            isAutoCommit = false

            // Isolamento explícito: REPEATABLE_READ já é o default do InnoDB, mas fixar evita surpresa
            // se o servidor tiver outro default. Uma request não enxerga escrita não-comitada de outra.
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"

            // validate() falha AGORA, no boot, se a config estiver incoerente — em vez de estourar
            // na primeira query em produção. Mesma filosofia fail-fast do `?: error()` acima.
            validate()
        }

        // UMA instância de pool para o processo inteiro. Recriar HikariDataSource a cada request
        // seria o oposto do propósito do pool. M2: devolvemos o DataSource CRU — quem conecta o
        // Exposed é o init(), depois do Flyway migrar. Flyway e Exposed consomem a MESMA fonte.
        return HikariDataSource(config)
    }

    fun init(dataSource: DataSource) {
        // M2: o schema agora nasce de MIGRATIONS versionadas (Flyway), não mais de SchemaUtils.create.
        // Diferença crucial: SchemaUtils só cria a tabela que falta e IGNORA em silêncio mudança de
        // coluna num schema já existente; o Flyway roda cada V*.sql UMA vez, registra em
        // flyway_schema_history e versiona a evolução. As migrations vivem em
        // src/com.koin.main/resources/db/migration (V1__baseline.sql). Flyway e Exposed usam o MESMO
        // DataSource -> uma origem de conexão só (lição do C5).
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()

        // Só DEPOIS de o schema existir, o Exposed conecta no mesmo pool. dbQuery honra este database.
        this.database = Database.connect(dataSource)
    }

    // H6: seed exposto como suspend, chamado do com.koin.module() FORA de qualquer transaction {}.
    // sendGlobalCategories() já abre a PRÓPRIA transação (via dbQuery) e é idempotente — não
    // insere se as categorias globais já existem. Assim schema e seed ficam desacoplados e nenhuma
    // transação fica aninhada. Ideal futuro: seed idempotente via migration Flyway versionada (M2),
    // tirando o seed do código de boot de vez.
    suspend fun seedGlobalCategories() {
        CategoryService().sendGlobalCategories()
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        // db = database: honra EXPLICITAMENTE a conexão do init(). Antes, sem o `db`, o Exposed caía
        // no "default global" (a última conexão aberta na JVM) — a bomba-relógio do C5: com múltiplas
        // conexões na mesma JVM, uma query podia ir parar no banco errado ("passa sozinho, falha em
        // grupo"). Agora cada query sabe exatamente em qual banco roda.
        newSuspendedTransaction(db = database) { block() }
}
