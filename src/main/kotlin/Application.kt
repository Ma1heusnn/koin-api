import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import factory.DatabaseFactory
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.request.receive
import models.UserLogin
import org.slf4j.event.Level
import javax.sql.DataSource
import plugins.configureStatusPages
import security.TokenConfig
import serializers.BigDecimalSerializer
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds


fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() = module(DatabaseFactory.getEnvData())

fun Application.module(dataSource: DataSource) {
    val tokenConfig = TokenConfig(
        issuer = environment.config.property("jwt.issuer").getString(),
        audience = environment.config.property("jwt.audience").getString(),
        // Access token CURTO (30 min). Se o token vazar, a janela de abuso é de minutos — não de
        // um ANO, como antes. O custo (usuário reautentica com frequência) é resolvido por um
        // REFRESH token (feature à parte — ver relatório), nunca esticando o access token.
        // Mantido como constante no código de propósito: mover para jwt.expiresIn no application.conf
        // exigiria adicionar a property também no MapApplicationConfig dos testes, senão eles quebram.
        expiresIn = 30L * 60L * 1000L,
        secret = environment.config.property("jwt.secret").getString()
    )
    val realm = environment.config.property("jwt.realm").getString()

    // StatusPages primeiro: ele envolve o pipeline como uma "casca" externa.
    // Instalado cedo, captura exceções lançadas por qualquer plugin/rota que venha depois.
    configureStatusPages()

    // M6: CallLogging. Loga método, rota, status e duração de CADA request. Sem isto, um 500 em
    // produção só aparece no stacktrace do StatusPages — não dá para responder "quantos 401 o
    // /users/login tomou na última hora?". De propósito NÃO logamos corpo nem headers: o corpo do
    // login carrega senha e o Authorization carrega o token — log é texto persistido, tratar como
    // superfície de vazamento (mesma lógica do C3: senha não cruza fronteira nenhuma).
    install(CallLogging) {
        level = Level.INFO
    }

    install(ContentNegotiation) {
        json(Json {
            serializersModule = SerializersModule {
                contextual(BigDecimal::class, BigDecimalSerializer)
            }
        })
    }

    // Permite ler o corpo da request MAIS DE UMA VEZ. Necessário porque o requestKey do RateLimit
    // (abaixo) precisa do identifier, que só existe no corpo — e o handler do /login lê o mesmo
    // corpo depois. Sem isto, a 2ª leitura estoura RequestAlreadyConsumedException.
    // O custo é o corpo ficar em buffer na memória; aqui os payloads são JSON de poucas centenas
    // de bytes. Instalado DEPOIS do ContentNegotiation, que é quem sabe virar UserLogin o JSON.
    install(DoubleReceive)

    // M6: RateLimit no /login. O H7 fechou a ENUMERAÇÃO (não dá pra saber quais contas existem),
    // mas nada impedia 10.000 tentativas de senha na mesma conta. Este é o freio real de força
    // bruta — não a engenharia contra microssegundos de timing.
    install(RateLimit) {
        register(RateLimitName("login")) {
            // 5 tentativas por minuto POR (IP, conta).
            rateLimiter(limit = 5, refillPeriod = 60.seconds)

            // A chave é composta de propósito. As duas alternativas simples são piores:
            //
            //  - Só IP: NAT junta gente demais numa chave só. 200 funcionários (ou uma cidade
            //    inteira atrás do CGNAT da operadora — e o nosso cliente é app Android) saem pelo
            //    MESMO IP público. Com 5 fichas compartilhadas, o 6º funcionário a abrir o app às
            //    8h toma 429 com a senha CERTA. Vira DoS auto-infligido contra o usuário pagante.
            //
            //  - Só identifier: (a) o atacante troca de e-mail a cada tentativa, ganha balde novo
            //    e passa batido — que é exatamente o formato do credential stuffing; (b) qualquer
            //    um trava a conta de um terceiro de fora (DoS por lockout).
            //
            // Compondo, cada par (IP, conta) tem o próprio balde: os 200 funcionários viram 200
            // chaves distintas (NAT resolvido) e o atacante não esgota o balde da vítima, porque
            // (IP_dele, conta_dela) é uma chave diferente de (IP_dela, conta_dela) — a vítima
            // continua entrando normalmente enquanto ele apanha do 429.
            //
            // ponytail: sobra a dimensão de VOLUME — com chave composta, o atacante ganha 5 fichas
            // por e-mail e pode varrer 10.000 contas de um IP só sem encostar no limite. O teto por
            // IP para isso precisa contar FALHAS (login certo não é sinal de ataque; escritório às
            // 8h gera 200 sucessos e 0 falhas, bot gera ~99% de falha), e o plugin decide ANTES do
            // handler, então não sabe se falhou. Exige contador próprio no branch do 401 + estado
            // compartilhado (Redis) se houver mais de uma instância. Fazer quando existir tráfego
            // real ou 2ª instância — não antes.
            //
            // ponytail: atrás de proxy/load balancer o remoteAddress vira o IP do proxy (todos os
            // usuários voltam a compartilhar balde). Ao pôr um proxy na frente, instalar
            // XForwardedHeaders — e só então confiar no origin.
            requestKey { call ->
                val ip = call.request.origin.remoteAddress

                // lowercase() NÃO é cosmético: o MySQL casa e-mail sem diferenciar caixa, então
                // "Ana@x.com" e "ana@x.com" atingem a MESMA conta. Sem normalizar, o atacante
                // variaria a caixa para ganhar um balde novo a cada tentativa — bypass de graça.
                // Corpo malformado: cai no balde só-de-IP em vez de estourar aqui; o 400 sai do
                // handler, via StatusPages, como em qualquer outra rota.
                val identifier = runCatching { call.receive<UserLogin>().identifier }
                    .getOrNull().orEmpty().trim().lowercase()

                "$ip|$identifier"
            }
        }
    }
    install(Authentication) {
        jwt {
            this.realm = realm

            verifier(
                JWT.require(Algorithm.HMAC256(tokenConfig.secret))
                    .withAudience(tokenConfig.audience)
                    .withIssuer(tokenConfig.issuer)
                    .build()
            )

            validate { credential ->
                // asInt() é platform type (Int!): sem a anotação Int? explícita, um claim ausente
                // escaparia como null "invisível" e viraria NPE lá na frente. Anotando, o compilador
                // nos obriga a tratar aqui. Retornar null = credencial inválida -> o plugin JWT
                // responde 401 automaticamente (token sem userId não autentica).
                val userId: Int? = credential.payload.getClaim("userId").asInt()
                if (userId != null) JWTPrincipal(credential.payload) else null
            }
        }
    }
    DatabaseFactory.init(dataSource)

    // H6: seed FORA de transação, no nível do boot. module() não é suspend, então o runBlocking AQUI
    // faz a ponte suspend->bloqueante — e bloquear é DESEJÁVEL: o boot só termina depois do seed, então
    // nenhuma request é servida antes de as categorias globais existirem (determinístico, sem race).
    // O erro do H6 nunca foi o runBlocking em si — foi ele DENTRO da transação, aninhando transações
    // e segurando conexão. Aqui, fora de qualquer transaction {}, é o lugar certo.
    // Alternativa avaliada e REJEITADA: launch em ApplicationStarted (não-bloqueante). Como o boot não
    // esperaria o seed, a 1ª request poderia chegar com a lista de categorias ainda vazia. Não-bloqueante
    // serve para tarefas de fundo das quais nada depende (aquecer cache, métrica) — não para um seed.
    runBlocking { DatabaseFactory.seedGlobalCategories() }

    configureRouting(tokenConfig)
}