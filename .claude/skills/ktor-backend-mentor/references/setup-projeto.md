# Setup de projeto Ktor — do zero ao servidor rodando

Referência para criar/estruturar projetos. Todo bloco vem com o porquê — use essas explicações na mentoria.

## Estrutura de pastas recomendada

Organize por **feature**, não por camada técnica pura. Explicação para o júnior: quando tudo de "produto" mora junto (rota, service, repository), você abre UMA pasta para mexer numa feature. Na organização por camada (`controllers/`, `services/`, `repositories/`), cada feature fica espalhada em 3+ pastas — funciona, mas escala pior e dificulta remover/mover features.

```
meu-projeto/
├── gradle/libs.versions.toml        # catálogo de versões (uma fonte da verdade)
├── build.gradle.kts
├── settings.gradle.kts
├── src/main/kotlin/com/exemplo/
│   ├── Application.kt               # ponto de entrada + módulo principal
│   ├── plugins/                     # configuração de cada plugin, um arquivo por assunto
│   │   ├── Serialization.kt
│   │   ├── Databases.kt
│   │   ├── Security.kt
│   │   └── StatusPages.kt
│   ├── produto/                     # uma pasta por feature
│   │   ├── ProdutoRoutes.kt
│   │   ├── ProdutoService.kt
│   │   ├── ProdutoRepository.kt
│   │   └── ProdutoDtos.kt
│   └── usuario/
│       └── ...
├── src/main/resources/
│   ├── application.yaml
│   ├── logback.xml
│   └── db/migration/                # migrations do Flyway (V1__..., V2__...)
└── src/test/kotlin/...
```

## Version catalog (`gradle/libs.versions.toml`)

Por que catálogo: versões declaradas UMA vez, com autocomplete no `build.gradle.kts`, e módulos futuros reutilizam. É o padrão moderno do Gradle.

```toml
[versions]
kotlin = "2.2.0"          # confira a estável atual em kotlinlang.org
ktor = "3.5.0"            # confira em ktor.io (releases frequentes)
exposed = "0.61.0"        # atenção: Exposed 1.x reorganizou pacotes — veja persistencia-exposed.md
hikari = "6.3.0"
postgres = "42.7.5"
flyway = "11.8.0"
logback = "1.5.18"
koin = "4.0.4"            # só se optar por Koin em vez da DI nativa
bcrypt = "0.10.2"

[libraries]
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-server-status-pages = { module = "io.ktor:ktor-server-status-pages", version.ref = "ktor" }
ktor-server-call-logging = { module = "io.ktor:ktor-server-call-logging", version.ref = "ktor" }
ktor-server-auth = { module = "io.ktor:ktor-server-auth", version.ref = "ktor" }
ktor-server-auth-jwt = { module = "io.ktor:ktor-server-auth-jwt", version.ref = "ktor" }
ktor-server-config-yaml = { module = "io.ktor:ktor-server-config-yaml", version.ref = "ktor" }
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
exposed-core = { module = "org.jetbrains.exposed:exposed-core", version.ref = "exposed" }
exposed-jdbc = { module = "org.jetbrains.exposed:exposed-jdbc", version.ref = "exposed" }
hikari = { module = "com.zaxxer:HikariCP", version.ref = "hikari" }
postgres = { module = "org.postgresql:postgresql", version.ref = "postgres" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-postgres = { module = "org.flywaydb:flyway-database-postgresql", version.ref = "flyway" }
logback = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
bcrypt = { module = "at.favre.lib:bcrypt", version.ref = "bcrypt" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ktor = { id = "io.ktor.plugin", version.ref = "ktor" }
```

Se tiver acesso à web, confirme as versões estáveis atuais antes de entregar; senão, avise o usuário para conferir (start.ktor.io gera projeto com versões atuais).

## `build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Plugin de serialização: gera em COMPILAÇÃO os serializers das classes
    // @Serializable. Sem reflection em runtime -> mais rápido e mais seguro.
    alias(libs.plugins.kotlin.serialization)
    // Plugin do Ktor: adiciona tasks úteis (run com auto-reload, buildFatJar p/ deploy).
    alias(libs.plugins.ktor)
}

group = "com.exemplo"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

tables { mavenCentral() }

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.logback)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlin.test)
}
```

## Ponto de entrada: `embeddedServer` vs `EngineMain`

Trade-off a ensinar:

- **`embeddedServer`**: servidor configurado em código Kotlin. Ótimo para exemplos rápidos e controle programático total.
- **`EngineMain`** (recomendado): lê `application.yaml` dos resources. Configuração fora do código = mudar porta/ambiente sem recompilar. É o que projetos reais usam.

```kotlin
// Application.kt
package com.exemplo

import io.ktor.server.application.*

// EngineMain lê application.yaml e chama os módulos declarados lá.
fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

// "Módulo" no Ktor = extension function de Application que configura o servidor.
// Quebramos a configuração em funções pequenas (uma por assunto) por dois motivos:
// 1. Legibilidade: cada arquivo conta uma história só.
// 2. Testabilidade: nos testes dá para montar só os módulos necessários.
fun Application.module() {
    configureSerialization()   // plugins/Serialization.kt
    configureStatusPages()     // plugins/StatusPages.kt
    configureDatabases()       // plugins/Databases.kt
    configureRouting()         // registra as rotas de cada feature
}
```

## `application.yaml`

```yaml
ktor:
  application:
    modules:
      - com.exemplo.ApplicationKt.module
  deployment:
    port: 8080

# Config própria da aplicação. Segredos vêm de variável de ambiente:
# a sintaxe "$VAR:default" usa a env var VAR se existir, senão o default.
# Assim o repositório nunca contém credenciais reais.
db:
  url: "$DB_URL:jdbc:postgresql://localhost:5432/meubanco"
  user: "$DB_USER:dev"
  password: "$DB_PASSWORD:dev"

jwt:
  secret: "$JWT_SECRET:troque-isto-em-producao"
  issuer: "com.exemplo"
  audience: "com.exemplo.api"
```

Lendo config no código:

```kotlin
val dbUrl = environment.config.property("db.url").getString()
```

## Rodando

```bash
./gradlew run          # sobe o servidor (plugin do Ktor habilita auto-reload em dev)
./gradlew test         # roda os testes
./gradlew buildFatJar  # gera JAR único para deploy: java -jar build/libs/*-all.jar
```

## Checklist do esqueleto pronto

Ao entregar um projeto novo, confirme com o usuário que ele entendeu: (1) onde muda a porta, (2) onde entram secrets, (3) onde nasce uma feature nova, (4) como roda e testa. Se ele souber responder os quatro, o setup cumpriu o papel didático.
