package routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.CostDTO
import models.CostPatch
import models.validate
import security.userId
import services.costs.CostService

fun Route.costRoutes(costsService: CostService) {
    authenticate {
        route("/costs") {
            get {
                val userId = call.userId()
                val costs = costsService.getCostsByUser(userId)
                call.respond(costs)
            }

            post {
                val userId = call.userId()

                val costDTO = call.receive<CostDTO>()

                // H8: valida FORMATO/obrigatoriedade ANTES de tocar no banco. A validação é barata
                // (em memória); a query é cara (I/O). Se o payload é insano (título vazio, valor <= 0),
                // respondemos 400 sem gastar uma ida ao banco. Ordem correta: valida -> query.
                val erros = costDTO.validate()
                if (erros.isNotEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, erros)
                }

                // H4: addCost agora VALIDA a categoria (existência + dono) e insere na MESMA
                // transação. null = categoria inválida -> nada foi gravado (fim do custo órfão).
                val response = costsService.addCost(costDTO, userId)

                if (response != null) {
                    call.respond(HttpStatusCode.Created, response)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Categoria não encontrada")
                }
            }

            route("/{id}") {
                get {
                    val id = call.pathId()
                    val userId = call.userId()

                    val cost = costsService.getCostById(id, userId)
                    if (cost != null) {
                        call.respond(cost)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Custo não encontrado")
                    }
                }
                patch {
                    val id = call.pathId()
                    val patchData = call.receive<CostPatch>()
                    val patchError = patchData.validate()
                    if (patchError.isNotEmpty()) {
                        return@patch call.respond(HttpStatusCode.BadRequest, patchError)
                    }
                    val userId = call.userId()

                    val success = costsService.patchCost(id, userId, patchData)
                    if (success) call.respond(
                        HttpStatusCode.OK,
                        "O custo com id $id foi editado"
                    ) else call.respond(
                        HttpStatusCode.NotFound, "Custo não encontrado"
                    )
                }
                delete {
                    val id = call.pathId()
                    val userId = call.userId()

                    val sucess = costsService.deleteCostById(id, userId = userId)
                    if (sucess) {
                        call.respondText("Custo deletado com sucesso: $id")
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Custo não encontrado")
                    }
                }
            }
        }
    }
}
