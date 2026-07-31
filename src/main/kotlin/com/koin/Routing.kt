package com.koin

import com.koin.services.costs.CostService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import com.koin.routes.authRoutes
import com.koin.routes.categoryRoutes
import com.koin.routes.costRoutes
import com.koin.routes.userRoutes
import com.koin.security.TokenConfig
import com.koin.services.auth.RefreshTokenService
import com.koin.services.categories.CategoryService
import com.koin.services.users.UserService


fun Application.configureRouting(tokenConfig: TokenConfig) {
    val costsService = CostService()
    // H9: o RefreshTokenService é compartilhado entre o login (UserService, que emite o refresh) e
    // as rotas /auth (que rotacionam/revogam). Uma instância só, injetada em ambos.
    val refreshTokenService = RefreshTokenService(tokenConfig)
    val usersService = UserService(tokenConfig, refreshTokenService)
    val categoriesService = CategoryService()
    routing {
        costRoutes(costsService)
        userRoutes(usersService)
        categoryRoutes(categoriesService)
        authRoutes(refreshTokenService)
    }
}
