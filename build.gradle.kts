import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "com.serraf"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    // Java 25 é LTS (set/2025) — a nota antiga de "bleeding edge" venceu. Fixar o toolchain faz o
    // build usar o MESMO JDK em qualquer máquina/CI, independente do java instalado no PATH.
    jvmToolchain(25)
}

// M7: TODA dependência vem do version catalog (gradle/libs.versions.toml). Antes metade estava
// hardcoded aqui e metade no catálogo — inclusive o logback DUAS vezes com versões diferentes
// (1.4.14 no catálogo, 1.5.6 aqui). Duas fontes de verdade = a versão que roda é a que o Gradle
// resolve por acaso, não a que você escolheu.
dependencies {
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)

    // BCrypt para gerar hash de senhas
    implementation(libs.jbcrypt)

    // Exposed (ORM para Kotlin)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)

    // Driver do MySQL e Pool de Conexões
    implementation(libs.mysql.connector)
    implementation(libs.hikaricp)

    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    // M6: observabilidade (CallLogging) e freio de força bruta no /login (RateLimit).
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.rate.limit)
    // O requestKey do RateLimit precisa LER o corpo (para extrair o identifier), e o handler lê de
    // novo depois. Sem DoubleReceive, a 2ª leitura estoura RequestAlreadyConsumedException.
    implementation(libs.ktor.server.double.receive)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)

    // H2 só existe para os testes (C5). Estava como `implementation` -> ia junto no .jar de
    // produção sem nenhum código de produção referenciá-lo. testImplementation tira do artefato.
    testImplementation(libs.h2)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    // Só para o CLIENT de teste falar JSON (o server já tem o seu content-negotiation).
    testImplementation(libs.ktor.client.content.negotiation)
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}