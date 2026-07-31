package models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class Cost(
    val id: Int,
    val userId: Int,
    val title: String,
    val description: String,
    val categoryId: Int,
    @Contextual
    val value: BigDecimal,
    val type: TransactionType
)

@Serializable
data class CostDTO(
    val title: String,
    val description: String = "",
    val categoryId: Int,
    @Contextual
    val value: BigDecimal,
    val type: TransactionType
)

@Serializable
data class CostPatch(
    val title: String? = null,
    val description: String? = null,
    val categoryId: Int? = null,
    @Contextual
    val value: BigDecimal? = null,
    val type: TransactionType? = null
)

@Serializable
data class CostDTOResponse(
    val id: Int,
    val title: String,
    val description: String,
    @Contextual
    val value: BigDecimal,
    val type: TransactionType,
    val category: Category // The nested category object
)

fun CostDTO.validate(): List<String> = buildList {
    if (title.isBlank()) add("O título do custo é obrigatório")
    if (value <= BigDecimal.ZERO) add("O valor do custo deve ser maior que zero")
    if (categoryId <= 0) add("A categoria do custo é obrigatória")
}

fun CostPatch.validate(): List<String> = buildList {
    title?.let { if (title.isBlank()) add("O título do custo é obrigatório") }
    value?.let { if (value <= BigDecimal.ZERO) add("O valor do custo deve ser maior que zero") }
    categoryId?.let { if (categoryId <= 0 ) add("A categoria do custo é obrigatória") }
}