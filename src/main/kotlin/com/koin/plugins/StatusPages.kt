package com.koin.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import com.koin.security.UnauthorizedException

@Serializable
data class ErrorResponse(val error: String)

fun Application.configureStatusPages() {

    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Requisição Inválida"))
        }
        exception<ExposedSQLException> { call, cause ->
            call.application.log.warn("Violação de Integridade em ${call.request.uri}", cause)
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Registro em Conflito"))
        }
        exception<UnauthorizedException> { call, _ ->
            // Identidade ausente/ inválida no token -> 401. Centraliza o que antes era `principal!!`
            // (NPE -> 500) espalhado. A ordem importa: vem ANTES do Throwable genérico.
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Não autenticado"))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.log.error("Erro não tratado em ${call.request.uri}", cause)

            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Erro Interno"))
        }
    }
}