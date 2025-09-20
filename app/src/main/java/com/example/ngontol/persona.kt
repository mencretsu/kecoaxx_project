package com.example.ngontol

data class Persona(
    val botName: String,
    val gender: String,
    val address: String,
    val hobby: String,
    val blacklist: List<String> = emptyList(),
    val appName: String? = null,
    val appPackage: String? = null
)

enum class BotPersona(val label: String) {
    GENZ_CENTIL("Manja"),
    TANTE_GENIT("Tante Genit"),
    SAD_GIRL("Misterius"),
    KAKAK_DINGIN("Dingin/Cool"),
    CEWE_PINTER("Pinter Kalem"),
    FLIRTY_TOXIC("Flirty Toxic"),
    SOPAN("Sopan")

}
