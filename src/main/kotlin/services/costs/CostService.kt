package services.costs

import factory.DatabaseFactory
import models.Category
import models.Cost
import models.CostDTO
import models.CostDTOResponse
import models.CostPatch
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import db.tables.categories.CategoriesTable
import db.tables.costs.CostsTable
import kotlin.let

class CostService {

    suspend fun getCostsByUser(userId: Int): List<CostDTOResponse> = DatabaseFactory.dbQuery {
        CostsTable.join(
            otherTable = CategoriesTable,
            joinType = JoinType.INNER,
            onColumn = CostsTable.categoryId,
            otherColumn = CategoriesTable.id
        ).selectAll().where {
            CostsTable.userId eq userId
        }.map {
            val category = Category(
                it[CategoriesTable.id].value,
                it[CategoriesTable.name],
                it[CategoriesTable.image],
                it[CategoriesTable.color]
            )

            CostDTOResponse(
                id = it[CostsTable.id].value,
                it[CostsTable.title],
                it[CostsTable.description],
                category = category,
                value = it[CostsTable.value],
                type = it[CostsTable.type]
            )
        }
    }
    suspend fun getCostById(id: Int, userId: Int): CostDTOResponse? = DatabaseFactory.dbQuery {
        CostsTable.join(
            otherTable = CategoriesTable,
            joinType = JoinType.INNER,
            onColumn = CostsTable.categoryId,
            otherColumn = CategoriesTable.id
        ).selectAll().where {
            CostsTable.userId eq userId and (CostsTable.id eq id)
        }.singleOrNull()
            ?.let {
                val category = Category(
                    it[CategoriesTable.id].value,
                    it[CategoriesTable.name],
                    it[CategoriesTable.image],
                    it[CategoriesTable.color]
                )

                CostDTOResponse(
                    id = it[CostsTable.id].value,
                    it[CostsTable.title],
                    it[CostsTable.description],
                    category = category,
                    value = it[CostsTable.value],
                    type = it[CostsTable.type]
                )
            }
    }

    suspend fun costsByCategory(categoryId: Int, userId: Int): List<Cost> = DatabaseFactory.dbQuery {
        CostsTable.selectAll()
            .where { (CostsTable.categoryId eq categoryId) and (CostsTable.userId eq userId) }
            .map {
                Cost(
                    id = it[CostsTable.id].value,
                    title = it[CostsTable.title],
                    description = it[CostsTable.description],
                    categoryId = it[CostsTable.categoryId],
                    value = it[CostsTable.value],
                    type = it[CostsTable.type],
                    userId = it[CostsTable.userId]
                )
            }
    }

    // H4: valida a categoria ANTES de inserir e TUDO no MESMO dbQuery (uma transação só).
    // Retorno CostDTOResponse? -> null significa "categoria inválida" e NADA foi gravado.
    // Por que nullable e não um sealed result: aqui só existe UM motivo de falha (categoria
    // inexistente ou de outro dono -> 404), e nullable casa com o estilo do resto do código
    // (getCategoryById: Category?, getCostById: CostDTOResponse?). Se um dia precisarmos
    // distinguir "não existe" de "não é sua" com status/mensagens diferentes, aí sim um
    // `sealed interface AddCostResult { Success / CategoryNotFound / ... }` paga o custo extra.

    suspend fun addCost(cost: CostDTO, user: Int): CostDTOResponse? = DatabaseFactory.dbQuery {
        // 1) Checa existência E dono na MESMA transação. Regra idêntica ao getCategoryById:
        //    vale se a categoria for do usuário OU global (userId null). Sem esse `or isNull`,
        //    custos em categorias globais quebrariam.
        val categoryRow = CategoriesTable.selectAll()
            .where {
                (CategoriesTable.id eq cost.categoryId) and
                    ((CategoriesTable.userId eq user) or (CategoriesTable.userId.isNull()))
            }
            .singleOrNull()
            ?: return@dbQuery null // categoria inválida -> sai ANTES do insert. Nada é gravado.

        // Reaproveitamos a MESMA linha lida na validação para montar a resposta -> sem 2ª query (N+1).
        val category = Category(
            id = categoryRow[CategoriesTable.id].value,
            name = categoryRow[CategoriesTable.name],
            image = categoryRow[CategoriesTable.image],
            color = categoryRow[CategoriesTable.color],
            userId = categoryRow[CategoriesTable.userId]
        )

        // 2) Só agora insere. Se o return@dbQuery acima disparou, este bloco nem roda.
        val generatedId = CostsTable.insertAndGetId {
            it[title] = cost.title
            it[description] = cost.description
            it[categoryId] = cost.categoryId
            it[value] = cost.value
            it[type] = cost.type
            it[CostsTable.userId] = user
        }

        CostDTOResponse(
            id = generatedId.value,
            title = cost.title,
            description = cost.description,
            value = cost.value,
            type = cost.type,
            category = category
        )
    }

    // P2: o `where` do update autoriza a LINHA tocada. O categoryId gravado é VALOR de entrada —
    // segunda decisão de autorização, sobre uma segunda tabela, e precisa da própria checagem.
    // Só consulta quando o patch de fato move de categoria: sem categoryId no payload não há FK
    // sendo escrita, logo não há nada a autorizar (e não se paga a query).
    suspend fun patchCost(id: Int, userId: Int, patch: CostPatch): Boolean = DatabaseFactory.dbQuery {
        patch.categoryId?.let { novaCategoria ->
            CategoriesTable.selectAll()
                .where {
                    (CategoriesTable.id eq novaCategoria) and
                            ((CategoriesTable.userId eq userId) or (CategoriesTable.userId.isNull()))
                }
                .singleOrNull()
                ?: return@dbQuery false // categoria de destino não é sua -> sai ANTES do update. Nada muda.
        }

        CostsTable.update(where = { (CostsTable.id eq id) and (CostsTable.userId eq userId) }) {
            patch.title?.let { newTitle -> it[title] = newTitle }
            patch.description?.let { newDescription -> it[description] = newDescription }
            patch.categoryId?.let { newCategory -> it[categoryId] = newCategory }
            patch.value?.let { newValue -> it[value] = newValue }
            patch.type?.let { newType -> it[type] = newType }
        } > 0
    }

    suspend fun deleteCostById(id: Int, userId: Int): Boolean = DatabaseFactory.dbQuery {
        CostsTable.deleteWhere { (CostsTable.id eq id) and (CostsTable.userId eq userId) }
    } > 0
}
