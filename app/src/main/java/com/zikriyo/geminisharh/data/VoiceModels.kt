package com.zikriyo.geminisharh.data

/**
 * Original NVDA addon dagi ovozlar va modellar ro'yxati.
 * Azure Neural (Sardor/Madina) + Gemini ovozlari.
 */
object VoiceModels {

    data class VoiceOption(val id: String, val name: String, val gender: String)

    data class ModelOption(val id: String, val name: String)

    val MALE_VOICES = listOf(
        VoiceOption("uz-UZ-SardorNeural", "Sardor (O'zbekcha - Ravon va tabiiy)", "male"),
        VoiceOption("ru-RU-DmitryNeural", "Dmitriy (Ruscha)", "male"),
        VoiceOption("en-US-GuyNeural", "Guy (Inglizcha)", "male"),
        // Gemini native voices (agar Gemini TTS ishlatilsa)
        VoiceOption("Charon", "Charon (Gemini Erkak - Yoqimli / Vazmin)", "male"),
        VoiceOption("Fenrir", "Fenrir (Gemini Erkak - Aniq / Qat'iy)", "male"),
        VoiceOption("Puck", "Puck (Gemini Erkak - Jonli)", "male")
    )

    val FEMALE_VOICES = listOf(
        VoiceOption("uz-UZ-MadinaNeural", "Madina (O'zbekcha - Mayin va aniq)", "female"),
        VoiceOption("ru-RU-SvetlanaNeural", "Svetlana (Ruscha)", "female"),
        VoiceOption("en-US-JennyNeural", "Jenny (Inglizcha)", "female"),
        VoiceOption("Aoede", "Aoede (Gemini Ayol - Mayin / Musiqiy)", "female"),
        VoiceOption("Kore", "Kore (Gemini Ayol - Tiniq / Xotirjam)", "female")
    )

    val ALL_VOICES = MALE_VOICES + FEMALE_VOICES

    val VISION_MODELS = listOf(
        ModelOption("models/gemini-3.6-flash", "Gemini 3.6 Flash (Tavsiya etiladi)"),
        ModelOption("models/gemini-flash-latest", "Gemini Flash Latest"),
        ModelOption("models/gemini-2.5-flash", "Gemini 2.5 Flash"),
        ModelOption("models/gemini-2.5-pro", "Gemini 2.5 Pro (Yuqori aniqlik)"),
        ModelOption("models/gemini-3.7-flash", "Gemini 3.7 Flash"),
        ModelOption("models/gemini-3.0-flash", "Gemini 3.0 Flash")
    )

    fun voicesForGender(gender: String): List<VoiceOption> =
        if (gender == "female") FEMALE_VOICES else MALE_VOICES

    fun defaultVoice(gender: String): String =
        if (gender == "female") "uz-UZ-MadinaNeural" else "uz-UZ-SardorNeural"
}
