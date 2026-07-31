# Segurança e autenticação: JWT, senhas, CORS

## Modelo mental do JWT (ensinar antes do código)

JWT é um **crachá assinado**. Três partes separadas por ponto: `header.payload.assinatura`. O payload é apenas **codificado** em Base64 (qualquer um lê — nunca coloque dado sensível ali!); a assinatura é o que importa: só quem tem o `secret` consegue gerá-la. O servidor não guarda sessão nenhuma — ele só verifica se a assinatura bate e se não expirou. É por isso que JWT escala bem (stateless) e é por isso que **não dá para "deslogar" um token antes de expirar** (trade-off honesto: por isso tokens de acesso são CURTOS).

Trade-off a apresentar: **sessão em cookie** (estado no servidor, revogação fácil, ótimo para web tradicional server-rendered) vs **JWT** (stateless, ideal para APIs consumidas por SPA/mobile/outros serviços). Para API REST, JWT é o padrão de mercado.

## Dependências

`ktor-server-auth`, `ktor-server-auth-jwt` e, para senhas, `at.favre.lib:bcrypt`.

## Configurando a verificação

```kotlin
// plugins/Security.kt
fun Application.configureSecurity() {
    val secret = environment.config.property("jwt.secret").getString()
    val issuer = environment.config.property("jwt.issuer").getString()
    val audience = environment.config.property("jwt.audience").getString()

    install(Authentication) {
        jwt("auth-jwt") {          // nome do "esquema" — as rotas referenciam por ele
            realm = "api"
            verifier(
                JWT.require(Algorithm.HMAC256(secret))  // mesma chave que assina, verifica
                    .withIssuer(issuer)      // recusa token emitido por outro sistema
                    .withAudience(audience)  // recusa token emitido PARA outro sistema
                    .build()
            )
            validate { credential ->
                // Última checagem sua: o token é válido, mas faz sentido?
                // Retornar null = 401. Aqui dá p/ conferir claims, usuário ativo, etc.
                val userId = credential.payload.getClaim("userId").asLong()
                if (userId != null && userId > 0) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                // Resposta padronizada quando o token falta ou é inválido.
                call.respond(HttpStatusCode.Unauthorized, ErroResponse("token ausente ou inválido"))
            }
        }
    }
}
```

## Emitindo tokens: login + refresh

Padrão de mercado a ensinar: **access token curto** (15 min — se vazar, a janela de dano é pequena) + **refresh token longo** (dias, usado SÓ para pedir um access novo). 

```kotlin
class TokenService(private val secret: String, private val issuer: String, private val audience: String) {

    fun gerarAccessToken(userId: Long): String = JWT.create()
        .withIssuer(issuer)
        .withAudience(audience)
        .withClaim("userId", userId)
        .withExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
        .sign(Algorithm.HMAC256(secret))

    fun gerarRefreshToken(userId: Long): String = JWT.create()
        .withIssuer(issuer)
        .withClaim("userId", userId)
        .withClaim("tipo", "refresh")   // impede usar refresh no lugar de access
        .withExpiresAt(Instant.now().plus(14, ChronoUnit.DAYS))
        .sign(Algorithm.HMAC256(secret))
}
```

## Senhas: BCrypt, e por quê

Explicação que o júnior precisa ouvir uma vez na vida: senha nunca se guarda, nem "criptografada" (criptografia é reversível!). Guarda-se um **hash** — função de mão única. E não pode ser hash rápido (MD5/SHA-256 puro): GPUs testam bilhões por segundo e tabelas prontas (rainbow tables) revertem hashes comuns. **BCrypt** resolve os dois problemas: é deliberadamente lento (custo ajustável) e embute um **salt** aleatório por senha — duas pessoas com a senha "123456" têm hashes diferentes.

```kotlin
object Senhas {
    // custo 12 ≈ centenas de ms por hash: imperceptível num login,
    // proibitivo para quem tenta bilhões de combinações.
    fun hash(senha: String): String =
        BCrypt.withDefaults().hashToString(12, senha.toCharArray())

    fun verificar(senha: String, hash: String): Boolean =
        BCrypt.verifyer().verify(senha.toCharArray(), hash).verified
}
```

## Fluxo de login (rota)

```kotlin
fun Route.authRoutes(usuarios: UsuarioRepository, tokens: TokenService) {
    post("/login") {
        val req = call.receive<LoginRequest>()
        val usuario = usuarios.buscarPorEmail(req.email)

        // Mesma resposta p/ "email não existe" e "senha errada": não dê ao
        // atacante um jeito de descobrir quais emails estão cadastrados.
        if (usuario == null || !Senhas.verificar(req.senha, usuario.senhaHash))
            return@post call.respond(HttpStatusCode.Unauthorized, ErroResponse("credenciais inválidas"))

        call.respond(TokenResponse(
            accessToken = tokens.gerarAccessToken(usuario.id),
            refreshToken = tokens.gerarRefreshToken(usuario.id),
        ))
    }
}
```

## Protegendo rotas e lendo o usuário logado

```kotlin
routing {
    authRoutes(usuarios, tokens)          // públicas

    authenticate("auth-jwt") {            // tudo aqui dentro exige token válido
        get("/me") {
            // O plugin já validou; o principal carrega os claims do payload.
            val principal = call.principal<JWTPrincipal>()!!
            val userId = principal.payload.getClaim("userId").asLong()
            call.respond(usuarios.buscar(userId) ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        // rotas de produto que exigem login, etc.
    }
}
```

Ponto didático 401 vs 403: o `authenticate` resolve o **401** (quem é você?). **Autorização** (403 — você PODE fazer isso?) é regra sua: ex., conferir claim `role` ou se o recurso pertence ao `userId` do token antes de alterar.

## CORS: o que é e como não fazer errado

CORS é o navegador perguntando ao SEU servidor se um site de outra origem pode consumi-lo. Só afeta browsers (Postman/apps ignoram). O erro comum é liberar tudo (`anyHost()`) para "fazer funcionar" e esquecer assim em produção:

```kotlin
install(CORS) {
    allowHost("app.meusite.com.br", schemes = listOf("https"))  // só quem você conhece
    allowHeader(HttpHeaders.ContentType)
    allowHeader(HttpHeaders.Authorization)
    allowMethod(HttpMethod.Put)
    allowMethod(HttpMethod.Delete)
    // anyHost() SÓ em dev — e com comentário gritando isso.
}
```

## Rate limiting básico

Protege login de força bruta e a API de abuso:

```kotlin
install(RateLimit) {
    register(RateLimitName("login")) {
        rateLimiter(limit = 5, refillPeriod = 60.seconds)  // 5 tentativas/min por chave
    }
}
// na rota:  rateLimit(RateLimitName("login")) { post("/login") { ... } }
```

## Checklist de segurança para revisar qualquer endpoint

1. Entrada validada no servidor? 2. Precisa de auth? Está dentro de `authenticate`? 3. Precisa de autorização além de auth (dono do recurso, role)? 4. Algum dado sensível no response/log? (nunca logar senha/token) 5. Erros vazam detalhe interno? 6. Secret veio de env, não do código?
