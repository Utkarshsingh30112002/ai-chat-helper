package com.replyassistant.app.settings

/** Must match backend `normalizeMode` values in `POST /v1/suggest`. */
object ReplyMode {
    const val STANDARD = "standard"
    const val BRIEF = "brief"
    const val PROFESSIONAL = "professional"

    private val ALL = setOf(STANDARD, BRIEF, PROFESSIONAL)

    fun coerce(raw: String?): String {
        val s = raw?.trim().orEmpty()
        return if (s in ALL) s else STANDARD
    }
}
