package com.koin.services.auth

import com.koin.factory.DatabaseFactory
import com.koin.models.TokenPair
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import com.koin.tables.refresh.RefreshTokensTable
import com.koin.security.TokenConfig
import com.koin.security.generateAccessToken
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * H9 — ciclo de vida do refresh token: emitir (login), rotacionar (/auth/refresh) e revogar (logout).
 *
 * Modelo mental: o access token é um crachá descartável de 30 min (irrevogável, só expira). O refresh
 * é a "chave da sala de crachás": longa duração, guardada no banco, e que a gente pode CASSAR. Cada uso
 * do refresh troca a chave por uma nova (rotação) — assim uma chave roubada só vale até o dono usar a
 * dele, momento em que a fraude é detectada e a família toda é cassada.
 */
class RefreshTokenService(private val tokenConfig: TokenConfig) {

    private val logger = LoggerFactory.getLogger(RefreshTokenService::class.java)
    private val secureRandom = SecureRandom()

    /** Emite o PRIMEIRO refresh de uma nova família (= uma sessão/dispositivo). Chamado no login. */
    suspend fun issue(userId: Int): String = DatabaseFactory.dbQuery {
        insertTokenInTx(userId, UUID.randomUUID().toString())
    }

    /**
     * Núcleo do H9: valida -> rotaciona -> devolve novo par (access + refresh).
     * Retorna null em QUALQUER falha (desconhecido, expirado, revogado, reuso) -> a rota responde 401
     * com mensagem única, sem revelar qual token "existe" (mesma filosofia anti-enumeração do H7).
     *
     * Tudo roda numa ÚNICA transação (dbQuery). Nada de dbQuery aninhado aqui — os helpers *InTx
     * usam DSL do Exposed direto, sem abrir transação nova (a armadilha de nested transaction do H6).
     **/

    suspend fun rotate(rawToken: String): TokenPair? = DatabaseFactory.dbQuery {
        val hash = sha256(rawToken)
        val row = RefreshTokensTable.selectAll()
            .where { RefreshTokensTable.tokenHash eq hash }
            .singleOrNull() ?: return@dbQuery null            // token desconhecido

        val familyId = row[RefreshTokensTable.familyId]
        val userId = row[RefreshTokensTable.userId]

        // Reuso: um refresh JÁ revogado (rotacionado antes, ou deslogado) reaparecendo = sinal de roubo.
        // Terra arrasada: revoga a família inteira. Se o ladrão rotacionou primeiro, o dono cai aqui e
        // mata a cadeia do ladrão; se o dono rotacionou, o ladrão cai aqui. Nos dois casos a sessão morre.
        if (row[RefreshTokensTable.revoked]) {
            logger.warn("Reuso de refresh detectado (familia={}, user={}). Revogando familia.", familyId, userId)
            revokeFamilyInTx(familyId)
            return@dbQuery null
        }

        // Expirado -> inútil (não é reuso, só acabou o prazo).
        if (row[RefreshTokensTable.expiresAt] < System.currentTimeMillis()) {
            return@dbQuery null
        }

        // Consumo ATÔMICO: o UPDATE só afeta a linha se ela AINDA estiver revoked=false. Dois requests
        // concorrentes com o mesmo token: só um vira false->true; o outro afeta 0 linhas. O count é o
        // guarda de corrida (mesma ideia do `where` do IDOR no C1). Quem perde = reuso -> mata família.
        val consumed = RefreshTokensTable.update({
            (RefreshTokensTable.tokenHash eq hash) and (RefreshTokensTable.revoked eq false)
        }) { it[revoked] = true } > 0

        if (!consumed) {
            logger.warn("Corrida no refresh (familia={}). Tratando como reuso.", familyId)
            revokeFamilyInTx(familyId)
            return@dbQuery null
        }

        // Rotaciona: novo refresh na MESMA família (continua a cadeia) + access token novo.
        val newRefresh = insertTokenInTx(userId, familyId)
        val newAccess = generateAccessToken(tokenConfig, userId)
        TokenPair(accessToken = newAccess, refreshToken = newRefresh)
    }

    /** Logout desta sessão: revoga a família do token apresentado, se pertencer ao usuário autenticado. */
    suspend fun revokeFamilyOf(userId: Int, rawToken: String): Boolean = DatabaseFactory.dbQuery {
        val row = RefreshTokensTable.selectAll()
            .where { (RefreshTokensTable.tokenHash eq sha256(rawToken)) and (RefreshTokensTable.userId eq userId) }
            .singleOrNull() ?: return@dbQuery false
        revokeFamilyInTx(row[RefreshTokensTable.familyId])
        true
    }

    /** "Sair de todos os dispositivos": revoga TODOS os refresh do usuário (todas as famílias). */
    suspend fun revokeAllForUser(userId: Int) {
        DatabaseFactory.dbQuery {
            RefreshTokensTable.update({ RefreshTokensTable.userId eq userId }) { it[revoked] = true }
        }
    }

    // ---- Helpers que rodam DENTRO de uma transação já aberta (não abrem dbQuery -> não aninham) ----

    private fun insertTokenInTx(userId: Int, familyId: String): String {
        val raw = generateRawToken()
        RefreshTokensTable.insert {
            it[tokenHash] = sha256(raw)
            it[RefreshTokensTable.userId] = userId       // qualificado: 'userId' aqui é o parâmetro
            it[RefreshTokensTable.familyId] = familyId    // idem
            it[expiresAt] = System.currentTimeMillis() + REFRESH_TTL_MS
            it[revoked] = false
        }
        return raw
    }

    private fun revokeFamilyInTx(familyId: String) {
        RefreshTokensTable.update({ RefreshTokensTable.familyId eq familyId }) {
            it[revoked] = true
        }
    }

    /**
     * Token OPACO de 256 bits de entropia. Por que aleatório (e não um JWT): o refresh é um SEGREDO
     * de longa vida guardado no banco — não precisa carregar claims, precisa ser imprevisível e cassável.
     */
    private fun generateRawToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * SHA-256, NÃO bcrypt — e isso é intencional. Bcrypt é lento de propósito para conter força bruta
     * em senhas HUMANAS (baixa entropia, dicionário). O refresh é aleatório de 256 bits: não há dicionário
     * nem brute force viável, então basta um hash rápido de mão única. Guardar só o hash é o que importa:
     * lookup por hash (índice único) + banco vazado não entrega tokens usáveis.
     */
    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        // 14 dias. Constante no código (não no application.conf) pela MESMA razão do access token (ver
        // Application.com.koin.module): mover para config exigiria replicar a property no MapApplicationConfig dos
        // testes, ou eles quebrariam. Trade-off consciente: menos flexível, testes mais simples.
        private const val REFRESH_TTL_MS = 14L * 24 * 60 * 60 * 1000
    }
}
