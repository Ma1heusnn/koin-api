package com.koin.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException

/**
 * P3 — fonte ÚNICA de "qual id veio na URL". Mesma ideia do `call.userId()` (H5).
 *
 * O que existia antes: `call.parameters["id"]?.toInt()` em 3 handlers e `?.toIntOrNull()` em 2.
 * O `toInt()` estoura `NumberFormatException` em `/costs/abc` -> cai no catch-all do StatusPages
 * -> **500** para o que é erro do cliente. E o `if (id == null)` que acompanhava era código MORTO:
 * a rota é `/{id}`, então o parâmetro nunca falta, e o `.toInt()` estoura antes de devolver null.
 *
 * Por que LANÇAR e não devolver `Int?`: com nullable a repetição volta em outra forma — cada
 * handler com o seu `?: return@x respond(400)`. É exatamente esse trecho copiado que já divergiu
 * em 3 dos 5 sites. Lançando, o handler vira uma linha e a decisão "id inválido = 400" mora num
 * lugar só. `BadRequestException` é a exceção do próprio Ktor e o StatusPages JÁ a mapeia para
 * 400 (`plugins/StatusPages.kt:20`) — sem classe nova, sem handler novo.
 */
fun ApplicationCall.pathId(): Int =
    parameters["id"]?.toIntOrNull() ?: throw BadRequestException("Parâmetro 'id' inválido")
