package db.tables.users

import factory.DatabaseFactory
import models.UserDTO
import org.jetbrains.exposed.sql.selectAll
import org.mindrot.jbcrypt.BCrypt

object UsersRepository {
    fun transformPasswordInHash(password: String): String {
        return BCrypt.hashpw(password, BCrypt.gensalt())
    }
}