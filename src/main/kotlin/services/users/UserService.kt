package services.users

import factory.DatabaseFactory
import models.LoginResponse

import models.UserDTO
import models.UserLogin
import models.UserPatch
import models.UserResponse
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.mindrot.jbcrypt.BCrypt
import db.tables.users.UsersRepository.transformPasswordInHash
import db.tables.users.UsersTable
import security.TokenConfig
import security.generateToken
import services.auth.RefreshTokenService
import java.util.UUID

// Hash bcrypt VÁLIDO computado 1x no load, para o caminho "usuário não existe" gastar o mesmo
// tempo de CPU do caminho "senha errada" (H7 fase 2).
// A entrada é ALEATÓRIA (era a string fixa "string-para-dummy"): com valor conhecido, quem
// mandasse exatamente essa senha para um usuário inexistente faria o checkpw devolver `true`.
// Não chegava a autenticar ninguém (não há usuário para devolver), mas é um caminho estranho
// que some de graça usando um valor que ninguém pode adivinhar.
val DUMMY_HASH: String = BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt())

class UserService(
    private val tokenConfig: TokenConfig,
    private val refreshTokenService: RefreshTokenService
) {

    suspend fun createUser(user: UserDTO): UserResponse {
        // BCrypt ANTES de abrir a transação. Estava dentro do dbQuery: hashear é CPU pura e LENTA
        // de propósito (custo 10 = dezenas de ms) — feito lá dentro, segurava uma das 10 conexões
        // do pool parada, queimando CPU, enquanto ela podia servir outra query. Mesma lição do H6:
        // transação é para I/O de banco, trabalho caro fica fora.
        val passwordHash = transformPasswordInHash(user.password)

        return DatabaseFactory.dbQuery {
            val generatedId = UsersTable.insertAndGetId {
                it[email] = user.email
                it[username] = user.username
                it[password] = passwordHash
                it[balance] = 0.toBigDecimal()
            }
            UserResponse(
                email = user.email,
                username = user.username,
                balance = 0.toBigDecimal(),
                id = generatedId.value
            )
        }
    }

    suspend fun loginUser(user: UserLogin): LoginResponse? {
        // Transação 1: SÓ a leitura. Devolve o candidato (dados públicos + hash) ou null e a
        // conexão volta imediatamente para o pool.
        // Antes, os dois BCrypt.checkpw rodavam AQUI DENTRO: cada tentativa de login segurava uma
        // das 10 conexões por dezenas de ms fazendo CPU, não I/O. Com 10 conexões, uma rajada de
        // logins esgotava o pool e travava TODA a API — inclusive rotas que nada têm a ver com
        // login. É o /login, o caminho mais quente da API, então o custo é o pior possível.
        val candidato = DatabaseFactory.dbQuery {
            UsersTable.selectAll()
                .where { (UsersTable.email eq user.identifier) or (UsersTable.username eq user.identifier) }
                .singleOrNull()
                ?.let { row ->
                    UserResponse(
                        id = row[UsersTable.id].value,
                        email = row[UsersTable.email],
                        username = row[UsersTable.username],
                        balance = row[UsersTable.balance]
                    ) to row[UsersTable.password]
                }
        }

        // BCrypt FORA da transação. O H7 fica intacto e até mais claro: existindo ou não o usuário,
        // roda EXATAMENTE um checkpw de mesmo custo (o hash real ou o DUMMY_HASH) — nenhum caminho
        // responde mais rápido que o outro, que é o canal lateral que o H7 fase 2 fechou.
        val hashParaComparar = candidato?.second ?: DUMMY_HASH
        if (!BCrypt.checkpw(user.password, hashParaComparar)) return null

        // Cinto de segurança: se o checkpw passou contra o DUMMY_HASH (só possível adivinhando um
        // UUID aleatório), não há usuário nenhum para autenticar — cai fora do mesmo jeito.
        val userResponse = candidato?.first ?: return null

        // H9 + H6: o refresh é emitido AQUI, FORA da transação acima — issue() abre a própria
        // transação. Emitir dentro do dbQuery de cima aninharia newSuspendedTransaction dentro de
        // newSuspendedTransaction (a armadilha de nested transaction que o H6 nos ensinou a evitar).
        // Duas transações sequenciais curtas > uma aninhada de semântica frágil.
        val accessToken = generateToken(tokenConfig, userResponse)
        val refreshToken = refreshTokenService.issue(userResponse.id)

        return LoginResponse(userResponse, accessToken, refreshToken)
    }

    suspend fun updateUser(id: Int, patch: UserPatch): Boolean {
        // Mesma hoisting do createUser, e aqui havia um agravante: o Exposed REPETE a transação até
        // 3x (defaultMaxAttempts = 3) em qualquer SQLException, sem distinguir falha transitória
        // (deadlock, timeout — onde repetir ajuda) de falha determinística (violação de unique —
        // onde repetir é garantidamente inútil). Com o BCrypt lá dentro, um PATCH trocando email
        // duplicado E senha pagava o hash 3 VEZES antes de responder 409. Fora da transação, o
        // retry volta a custar só o round-trip.
        val novoHash = patch.password?.let { transformPasswordInHash(it) }

        return DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.id eq id }) {
                patch.email?.let { newEmail -> it[email] = newEmail }
                patch.username?.let { newUsername -> it[username] = newUsername }
                novoHash?.let { hash -> it[password] = hash }
            } > 0
        }
    }
}