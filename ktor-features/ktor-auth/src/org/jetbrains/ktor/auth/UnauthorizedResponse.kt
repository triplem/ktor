package org.jetbrains.ktor.auth

import org.jetbrains.ktor.content.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.util.*

class UnauthorizedResponse(vararg val challenges: HttpAuthHeader) : FinalContent.NoContent() {
    override val status: HttpStatusCode?
        get() = HttpStatusCode.Unauthorized

    override val headers: Parameters
        get() = if (challenges.isNotEmpty())
            parametersOf(HttpHeaders.WWWAuthenticate, listOf(challenges.joinToString(", ") { it.render() }), caseInsensitiveKey = true)
        else
            parametersOf()
}