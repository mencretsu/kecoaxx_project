package com.example.ngontol

data class Persona(
    val botName: String,
    val gender: String,
    val address: String,
    val hobby: String,
    val blacklist: List<String> = emptyList(),
    val appName: String? = null,
    val appPackage: String? = null,
    val apiKeyx: List<String> = emptyList(),
    val relogOption: Int? = 3,
    val hybridState: Int = 1,
    val autoHi: Int? = 1
)

enum class BotPersona(val label: String) {
    GENZ_CENTIL("> Karakter"),
    CENTIL("Manja"),
    TANTE_GENIT("Tante Genit"),
    SAD_GIRL("Cewe Misterius"),
    KAKAK_DINGIN("SEMI TANTE"),
    CEWE_PINTER("Pinter Kalem"),
    FLIRTY_TOXIC("Flirty Toxic"),
    SOPAN("CEWE Sopan"),
    CEWE_AMBIVERT("Ambivert"),
    CEWE_RANDOM("cw RANDOM"),
    CEWE_CHALLENGE("SPEK Preman"),
    CEWE_BUAYA("Buaya"),
    TANTE_MATURE("SPEK Mommy"),
    TANTE_BADASS("Tante GANAS"),
    MBAK_BERPENGALAMAN("Cewe BErPengalaman"),
    TANTE_PROVIDER("TAnte TAJIR"),
    CEWE_KARIR("CEWE KARIR"),
    CEWE_HOPELESS_ROMANTIC("sPEK RATU"),
    CEWE_JOMBLO_BAHAGIA("JOMBLO"),
    CEWE_PEJUANG_JODOH("PEJUANG JOOOH"),
    CEWE_HEALING("PENYEMBAH HEALING"),
    CEWE_PLIN_PLAN("CEWE TOLOL"),
    CEWE_FOMO("Cewe FOMO")




}
