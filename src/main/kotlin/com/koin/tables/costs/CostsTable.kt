package com.koin.tables.costs

import com.koin.models.TransactionType
import org.jetbrains.exposed.dao.id.IntIdTable
import com.koin.tables.categories.CategoriesTable
import com.koin.tables.users.UsersTable
import java.math.BigDecimal

object CostsTable : IntIdTable("costs") {
    val userId = integer("user_id").references(UsersTable.id)
    val title = varchar("title", 128)
    val description = varchar("description", 255)
    val categoryId = integer("category_id").references(CategoriesTable.id)
    val value = decimal("value", 13, 2)
    val type = enumerationByName("type", 10, TransactionType::class )

    init {
        // H8 — ÚLTIMA linha de defesa. A validação da app (CostDTO.validate) é a 1ª linha e dá a
        // mensagem amigável (400). Mas se alguém inserir contornando o service (um script, um bug
        // futuro, outra rota), o banco RECUSA valor negativo. Invariante de dado mora no banco;
        // regra de UX mora na app. (MySQL 8+ e H2 enforçam CHECK; MySQL < 8.0.16 ignora em silêncio.)
        check("chk_cost_value_non_negative") { value greaterEq BigDecimal.ZERO }
    }
}
