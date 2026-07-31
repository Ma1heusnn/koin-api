package db.tables.users

import org.jetbrains.exposed.dao.id.IntIdTable

object UsersTable : IntIdTable("users") {
    val email = varchar("email", 128).uniqueIndex()
    val username = varchar("username", 128)
    val password = varchar("password_hash", 255)
    val balance = decimal(
        "balance",
        precision = 13,
        scale = 2
    )
}