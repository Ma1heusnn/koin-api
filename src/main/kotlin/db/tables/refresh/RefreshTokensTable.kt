package db.tables.refresh

import org.jetbrains.exposed.dao.id.IntIdTable
import db.tables.users.UsersTable

/**
 * H9 — refresh token STATEFUL. O access token (JWT) é stateless e irrevogável: só expira.
 * Para poder REVOGAR uma sessão antes da hora (logout, roubo), o refresh precisa de estado no
 * banco. Guardamos aqui o mínimo para validar, rotacionar e revogar.
 *
 * Colunas:
 *  - tokenHash: SHA-256 (hex, 64 chars) do token cru. NUNCA guardamos o token em si — se o banco
 *    vazar, o atacante leva só hashes inúteis (não dá para reverter um valor aleatório de 256 bits).
 *  - userId: dono do token (usado para "sair de todos os dispositivos").
 *  - familyId: agrupa a "cadeia" de tokens de UMA sessão/login. A rotação emite um novo token na
 *    MESMA família; se um token já revogado reaparece (reuso = roubo), revogamos a família inteira.
 *  - expiresAt: epoch millis. Long simples de propósito — sem depender de exposed-java-time, e o
 *    comportamento é idêntico em H2 e MySQL (evita atrito de tipo datetime entre dialetos).
 *  - revoked: marca "já usado/rotacionado" OU "revogado por logout". Um refresh só vale uma vez.
 */
object RefreshTokensTable : IntIdTable("refresh_tokens") {
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val userId = integer("user_id").references(UsersTable.id)
    val familyId = varchar("family_id", 36)
    val expiresAt = long("expires_at")
    val revoked = bool("revoked").default(false)
}
