package com.koin.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class UserDTO(
    val email: String,
    val password: String,
    val username: String
)

@Serializable
data class UserResponse(
    val email: String,
    val username: String,
    @Contextual
    val balance: BigDecimal,
    val id: Int
)

@Serializable
data class UserLogin(
    val identifier: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val userResponse: UserResponse,
    val token: String,          // access token (JWT curto, 30 min)
    val refreshToken: String    // H9: refresh opaco (troca por novo par em POST /auth/refresh)
)

// H9: corpo do POST /auth/refresh e /auth/logout — o cliente manda o refresh cru.
@Serializable
data class RefreshRequest(val refreshToken: String)

// H9: resposta do POST /auth/refresh — novo par emitido pela rotação.
@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String
)

@Serializable
data class UserPatch(
    val email: String? = null,
    val password: String? = null,
    val username: String? = null
)

// E2: pura e síncrona, como CategoryDTO.validate() e CostDTO.validate(). A pré-checagem de e-mail
// duplicado saiu daqui de propósito: ela consultava o banco (forçando `suspend`) e ainda era um
// TOCTOU — dois cadastros simultâneos com o mesmo e-mail passam os dois pelo SELECT e o INSERT
// estoura mesmo assim. Quem garante unicidade é o uniqueIndex de UsersTable.email; o duplicado
// vira 409 pelo handler de ExposedSQLException, mesmo caminho que o PATCH /profile já usava.
fun UserDTO.validate(): List<String> = buildList {
    if (email.isEmpty()) add("O email não deve ser vazio")
    if (!email.contains("@")) add("Email inválido")

    if ((password.isEmpty() || password.length < 8)) add("Sua senha deve possuir ao menos 8 caracteres")

    if (username.isBlank()) add("O nome não deve ser vazio")
    if (username.contains('@')) add("O nome de usuário não deve conter @")
}