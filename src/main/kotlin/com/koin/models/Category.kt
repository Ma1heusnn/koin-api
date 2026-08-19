package com.koin.models

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: Int? = null,
    val name: String,
    val image: String = "R.drawable.sem_foto",
    val color: String = "#CCCCCC",
    val userId: Int? = null
)
@Serializable
data class CategoryDTO(
    val name: String,
    val image: String = "R.drawable.sem_foto",
    val color: String = "#CCCCCC"
)

@Serializable
data class CategoryPatch(
    val name: String? = null,
    val image: String? = null,
    val color: String? = null
)

private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")

fun CategoryDTO.validate(): List<String> = buildList {
    if (name.isBlank()) add("O nome da categoria é obrigatório")
    if (!color.matches(HEX_COLOR)) add("Formato de cor inválido (#RRGGBB esperado)")
}

fun CategoryPatch.validate(): List<String> = buildList {
    if (listOfNotNull(name, image, color).isEmpty()) add("Envie ao menos um campo para atualizar")
    name?.let { if (name.isBlank()) add("O nome da categoria é obrigatório")}
    color?.let { if (!color.matches(HEX_COLOR)) add("Formato de cor inválido (#RRGGBB esperado)")}
}