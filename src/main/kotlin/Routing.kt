import services.costs.CostService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import routes.authRoutes
import routes.categoryRoutes
import routes.costRoutes
import routes.userRoutes
import security.TokenConfig
import services.auth.RefreshTokenService
import services.categories.CategoryService
import services.users.UserService


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
