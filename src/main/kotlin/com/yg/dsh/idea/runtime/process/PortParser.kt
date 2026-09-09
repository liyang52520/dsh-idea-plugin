package com.yg.dsh.idea.runtime.process

import com.yg.dsh.idea.util.Constants

/**
 * A discovered DSH web endpoint: loopback port plus the per-process auth token.
 *
 * Newer DSH builds authenticate every HTTP request: the banner prints the
 * token as a query parameter (`dsh web: http://127.0.0.1:<port>/?token=<tok>`),
 * a first GET with that token plants an `HttpOnly` auth cookie, and all
 * subsequent `/api/...` calls must carry that cookie.
 */
data class WebEndpoint(val port: Int, val token: String) {
    /** Scheme + host + port, no trailing slash. */
    val origin: String get() = "http://${Constants.LOOPBACK_HOST}:$port"

    /** Full URL that performs the token → cookie exchange when opened. */
    val url: String get() = "$origin/?token=$token"
}

object PortParser {
    private val RE = Regex("""dsh web: http://127\.0\.0\.1:(\d+)/\?token=([A-Za-z0-9_-]+)""")

    /** Extracts the [WebEndpoint] from a DSH banner line, or null. */
    fun parse(line: String): WebEndpoint? {
        val m = RE.find(line) ?: return null
        val port = m.groupValues[1].toIntOrNull() ?: return null
        return WebEndpoint(port, m.groupValues[2])
    }
}
