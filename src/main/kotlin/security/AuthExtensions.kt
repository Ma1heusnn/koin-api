package security

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal

/**
 * Exceção de domínio para "não autenticado / identidade ausente no token".
 * O StatusPages a traduz em 401 (ver plugins/StatusPages.kt), então a ausência de identidade
 * vira uma resposta HTTP limpa e centralizada — nunca um NullPointerException (500) vindo de um
 * `principal!!` espalhado pelos handlers.
 */
class UnauthorizedException(message: String = "Não autenticado") : RuntimeException(message)

/**
 * Fonte ÚNICA de "quem é o usuário desta requisição".
 * Substitui o `principal!!.payload.getClaim("userId").asInt()` que estava copiado em 10 handlers.
 *
 * Por que LANÇAR exceção (e não devolver Int?): assim o `!!` some e cada handler vira uma linha
 * (`val userId = call.userId()`), com o caso de falha tratado UMA vez no StatusPages (401). Se
 * devolvêssemos nullable, a repetição voltaria em outra forma — todo handler com um
 * `?: return@x respond(401)`. Como estes handlers vivem dentro de `authenticate { }` e o `validate`
 * já garante o claim, na prática isto quase nunca dispara aqui: é cinto de segurança para uso
 * indevido futuro (um handler fora do authenticate, um token malformado).
 */
fun ApplicationCall.userId(): Int {
    val principal = principal<JWTPrincipal>() ?: throw UnauthorizedException()
    // asInt() devolve Integer do Java = platform type (Int!), que escaparia do null-check do Kotlin.
    // Anotar Int? força o compilador a nos obrigar a tratar a ausência do claim aqui, no limite.
    val id: Int? = principal.payload.getClaim("userId").asInt()
    return id ?: throw UnauthorizedException("Token sem claim userId válido")
}
