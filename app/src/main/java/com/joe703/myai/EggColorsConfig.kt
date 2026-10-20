package com.joe703.myai

import kotlinx.serialization.Serializable

@Serializable
data class EggColorsConfig(
    val colors: List<EggColor>
)

@Serializable
data class EggColor(
    val name: String,
    val rgb: RGB
)

@Serializable
data class RGB(
    val r: Int,
    val g: Int,
    val b: Int
)
