package db.tables.categories

import org.jetbrains.exposed.dao.id.IntIdTable
import db.tables.users.UsersTable

object CategoriesTable : IntIdTable("categories") {
    val name = varchar("name", 128)
    val image = varchar("image", 255)
    val color = varchar("color", 7)
    val userId = integer("user_id").references(UsersTable.id).nullable()

    init {
        uniqueIndex(name, userId)
    }
}

