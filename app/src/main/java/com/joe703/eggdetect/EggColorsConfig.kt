package com.joe703.eggdetect

import kotlinx.serialization.Serializable

@Serializable
data class EggColorsConfig(
    val colors: List<EggColor>
)

@Serializable
data class EggColor(
    val name: String,
    val rgb: RGB,
    val hsv: HSVConfig,
    val s2Threshold: Int,
    val roiExpansion: Double,
    val shapeMatching: ShapeMatching
)

@Serializable
data class RGB(
    val r: Int,
    val g: Int,
    val b: Int
)

@Serializable
data class HSVConfig(
    val s1: HSVRange,
    val s2: HSVRange
)

@Serializable
data class HSVRange(
    val hMin: Int,
    val hMax: Int,
    val sMin: Int,
    val sMax: Int,
    val vMin: Int,
    val vMax: Int
)

@Serializable
data class ShapeMatching(
    val aspectRatioMin: Double,
    val aspectRatioMax: Double,
    val circularityMin: Double,
    val minArea: Int
)
