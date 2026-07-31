package routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.CategoryDTO
import models.CategoryPatch
import models.validate
import security.userId
import services.categories.CategoryService

fun Route.categoryRoutes(categoriesService: CategoryService) {
    authenticate {
        route("/categories") {
            get {
                // H5: call.userId() substitui o principal!!...asInt() + o "null-check" quebrado
                // (!userId.equals(null), que comparava um Int a null e era SEMPRE verdadeiro).
                val userId = call.userId()
                // P5: coleção vazia é 200 com [], não 404. O recurso /categories existe; o que está
                // vazio é o conteúdo. 404 aqui colidiria com o 404 de verdade (id inexistente / de
                // outro dono) e obrigaria o app a tratar erro como estado normal.
                call.respond(HttpStatusCode.OK, categoriesService.getCategories(userId))
            }

            post {
                val userId = call.userId()

                val categoryDTO = call.receive<CategoryDTO>()

                val erros = categoryDTO.validate()
                if (erros.isNotEmpty()){
                    return@post call.respond(HttpStatusCode.BadRequest, erros)
                }

                val newCategory = categoriesService.createUserCategory(
                    userId,
                    categoryDTO
                )

                call.respond(HttpStatusCode.Created, newCategory)
            }
            route("/{id}") {
                patch {
                    val id = call.pathId()
                    val userId = call.userId()

                    val patchData = call.receive<CategoryPatch>()
                    val patchError = patchData.validate()

                    if (patchError.isNotEmpty()){
                        return@patch call.respond(HttpStatusCode.BadRequest, patchError)
                    }
                    val success = categoriesService.editCategory(id, userId = userId, patchData)
                    if (success) {
                        call.respond(HttpStatusCode.OK, "Categoria editada com sucesso")
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Nenhuma categoria encontrada")
                    }
                }
                delete {
                    val id = call.pathId()
                    val userId = call.userId()

                    // P6: o row count do deleteWhere É a autorização (mesmo guarda de corrida do C1),
                    // então não há pré-checagem. Antes havia um getCategoryById que ACEITA categoria
                    // global (userId null) seguido de um deleteCategoryById que NÃO aceita: a global
                    // passava na primeira query, falhava na segunda e caía num 400 "Falha ao excluir"
                    // que sugeria erro do servidor. Agora 0 linhas = não existe / não é sua / é global
                    // -> 404, um significado só e igual ao que o patch vizinho já respondia.
                    //
                    // Por que não 403 para a global (mais honesto, e não vazaria nada — toda global
                    // aparece no GET /categories): distinguir "global" de "de outro dono" custa uma
                    // segunda query, que é justamente a pré-checagem removida aqui, e traria de volta
                    // a janela entre o SELECT e o DELETE.
                    if (categoriesService.deleteCategoryById(id, userId)) {
                        call.respond(HttpStatusCode.OK, "Categoria excluída com sucesso")
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Nenhuma categoria encontrada")
                    }
                }
            }
        }
    }
}
