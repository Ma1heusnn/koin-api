package services.categories

import factory.DatabaseFactory
import models.Category
import models.CategoryDTO
import models.CategoryPatch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import db.tables.categories.CategoriesTable

class CategoryService {

    suspend fun createUserCategory(userId: Int, category: CategoryDTO): Category = DatabaseFactory.dbQuery {

        val generatedId = CategoriesTable.insertAndGetId { statement ->
            statement[name] = category.name
            statement[image] = category.image
            statement[color] = category.color
            statement[CategoriesTable.userId] = userId
        }
        Category(
            id = generatedId.value,
            name = category.name,
            image = category.image,
            color = category.color,
            userId = userId
        )
    }

    suspend fun sendGlobalCategories() = DatabaseFactory.dbQuery {

        val globalCategoriesExist = CategoriesTable.selectAll()
            .where { CategoriesTable.userId.isNull() }
            .empty()
            .not()

        if (globalCategoriesExist) {
            return@dbQuery
        }
        val defaultCategories = listOf(
            CategoryDTO("Saúde e Bem Estar", image = "R.drawable.health", color = "#00FF00"),
            CategoryDTO("Alimentação", image = "R.drawable.food", color = "#FFCF61"),
            CategoryDTO("Lazer", image = "R.drawable.leisure", color = "#82C8FF"),
            CategoryDTO("Transporte", image = "R.drawable.transport", color = "#CA86FF"),
            CategoryDTO("Educação", image = "R.drawable.education", color = "#306DFF"),
            CategoryDTO("Investimento", image = "R.drawable.investments", color = "#FFCF26"),
            CategoryDTO("Sem Categoria", image = "R.drawable.none", color = "#C2C2C2")
        )


        defaultCategories.forEach { category ->
            CategoriesTable.insert {
                it[name] = category.name
                it[image] = category.image
                it[color] = category.color
                it[userId] = null
            }
        }


    }

    suspend fun getCategories(userId: Int): List<Category> = DatabaseFactory.dbQuery {
        CategoriesTable.selectAll()
            .where {
                (CategoriesTable.userId.isNull()) or (CategoriesTable.userId eq userId)
            }
            .map {
                // M9: userId incluído. Antes ficava de fora e o cliente recebia `userId: null` em
                // TODA categoria da lista — indistinguível de uma categoria global (que é null de
                // verdade). O mesmo recurso vinha com shape diferente conforme a rota
                // (getCategoryById populava, getCategories não). Argumentos nomeados de propósito:
                // posicional aqui é convite a trocar `image` com `color` sem o compilador reclamar.
                Category(
                    id = it[CategoriesTable.id].value,
                    name = it[CategoriesTable.name],
                    image = it[CategoriesTable.image],
                    color = it[CategoriesTable.color],
                    userId = it[CategoriesTable.userId]
                )
            }
    }

    // getCategoryById foi removido no P6 (2026-07-29): seu único chamador era a pré-checagem do
    // DELETE, e ela sumiu quando o row count do deleteWhere passou a ser a autorização. Não existe
    // rota GET /categories/{id}. Se um dia existir, o formato está no addCost (CostService.kt:106).
    suspend fun editCategory(id: Int, userId: Int, patch: CategoryPatch): Boolean = DatabaseFactory.dbQuery {
        CategoriesTable.update(where = {( CategoriesTable.id eq id) and (CategoriesTable.userId eq userId)}) {
            patch.name?.let { newName -> it[name] = newName }
            patch.color?.let { newColor -> it[color] = newColor }
            patch.image?.let { newImage -> it[image] = newImage }
        } > 0
    }

    suspend fun deleteCategoryById(id: Int, userId: Int): Boolean = DatabaseFactory.dbQuery {
        CategoriesTable.deleteWhere {
            (CategoriesTable.id eq id) and (CategoriesTable.userId eq userId)
        } > 0
    }
}
