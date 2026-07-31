package com.koin.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import com.koin.models.RefreshRequest
import com.koin.security.userId
import com.koin.services.auth.RefreshTokenService

fun Route.authRoutes(refreshTokenService: RefreshTokenService) {
    route("/auth") {

        // PÚBLICO de propósito: quando o cliente chama /refresh, o access token JÁ expirou — não dá
        // para exigir `authenticate` aqui. A credencial deste endpoint é o PRÓPRIO refresh token (no
        // corpo), não o JWT. Ele valida -> rotaciona -> devolve um par novo.
        post("/refresh") {
            val request = call.receive<RefreshRequest>()
            val pair = refreshTokenService.rotate(request.refreshToken)
            if (pair != null) {
                call.respond(pair)
            } else {
                // Resposta genérica única para todos os modos de falha (desconhecido/expirado/revogado/
                // reuso). Diferenciar daria ao atacante um oráculo de quais tokens existem — mesma lição
                // anti-enumeração do login (H7).
                call.respond(HttpStatusCode.Unauthorized, "Refresh token inválido")
            }
        }

        // Logout e logout-all exigem access token válido: identificam QUEM está encerrando a sessão.
        authenticate {

            // Logout desta sessão: revoga a FAMÍLIA do refresh apresentado (um login = uma família).
            // Só revoga se a família for do próprio usuário (evita cassar sessão alheia por chute).
            // Os access tokens ainda vivos morrem sozinhos em até 30 min — o refresh é o que cassamos.
            post("/logout") {
                val userId = call.userId()
                val request = call.receive<RefreshRequest>()
                refreshTokenService.revokeFamilyOf(userId, request.refreshToken)
                // Sempre 200 (idempotente): não vazamos se o token existia ou não.
                call.respond(HttpStatusCode.OK, "Sessão encerrada")
            }

            // "Sair de todos os dispositivos": revoga TODAS as famílias do usuário de uma vez.
            post("/logout-all") {
                val userId = call.userId()
                refreshTokenService.revokeAllForUser(userId)
                call.respond(HttpStatusCode.OK, "Todas as sessões encerradas")
            }
        }
    }
}
