package com.koin.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.koin.models.UserResponse
import java.util.*

data class TokenConfig(
    val issuer: String,
    val audience: String,
    val expiresIn: Long,
    val secret: String
)

fun generateToken(config: TokenConfig, user: UserResponse): String {
    // This ensures we never create a token for a user without an ID.
    requireNotNull(user.id) { "Cannot generate token for a user with a null ID" }
    return generateAccessToken(config, user.id)
}

/**
 * H9 — gera um access token a partir do userId puro. O fluxo de refresh (/auth/refresh) só tem o
 * userId em mãos (lido da linha do refresh no banco), não um UserResponse inteiro — por isso este
 * ponto de entrada. O generateToken acima delega aqui: a assinatura do JWT mora num lugar só.
 */
fun generateAccessToken(config: TokenConfig, userId: Int): String =
    JWT.create()
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .withClaim("userId", userId)
        .withExpiresAt(Date(System.currentTimeMillis() + config.expiresIn))
        .sign(Algorithm.HMAC256(config.secret))
