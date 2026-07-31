package com.koin.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import com.koin.models.UserDTO
import com.koin.models.UserLogin
import com.koin.models.UserPatch
import com.koin.models.validate
import com.koin.security.userId
import com.koin.services.users.UserService

fun Route.userRoutes(userService: UserService) {
    route("/users") {
        post {
            val user = call.receive<UserDTO>()
            val erros = user.validate()
            if (erros.isNotEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, erros)
            }
            call.respond(HttpStatusCode.Created, userService.createUser(user))
        }
        // M6: só o /login entra no balde de rate limit. Aplicar o limite na API inteira puniria uso
        // normal (uma tela lista categorias e custos em sequência); o alvo é a rota que aceita
        // adivinhação de credencial. Estourou o limite -> o plugin responde 429 + Retry-After
        // sozinho, antes de o handler rodar (nenhum BCrypt é gasto com o atacante).
        rateLimit(RateLimitName("login")) {
            post("/login") {
                val user = call.receive<UserLogin>()
                val loginResponse = userService.loginUser(user)
                if (loginResponse != null) {
                    call.respond(loginResponse)
                } else {
                    // H7: 401 (não 404). 404 = "esse recurso não existe" -> confirmaria pro atacante
                    // que o e-mail/username NÃO está cadastrado (enumeração de usuário). 401 =
                    // "autenticação falhou", sem dizer se foi o identificador ou a senha. A MENSAGEM
                    // é a mesma para "usuário inexistente" e "senha errada" de propósito: qualquer
                    // diferença (status, texto ou até tempo de resposta) vira um oráculo que revela
                    // quais contas existem.
                    call.respond(HttpStatusCode.Unauthorized, "Usuário ou senha inválidos")
                }
            }
        }
        authenticate {
            patch("/profile") {
                val userId = call.userId()
                val user = call.receive<UserPatch>()

                val success = userService.updateUser(userId, user)
                if (success) {
                    call.respond(HttpStatusCode.OK, "Usuário editado com sucesso")
                } else {
                    call.respond(HttpStatusCode.NotFound, "Falha ao editar o usuário")
                }
            }
        }
    }
}