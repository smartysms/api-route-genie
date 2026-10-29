package com.routegenie.client.curl

import com.routegenie.client.RequestSpec

/** The three command-line dialects a cURL command can be written for or pasted from. */
enum class CurlShell { BASH, CMD, POWERSHELL }

/** Request behavior that is not part of [RequestSpec] but that a cURL command can carry (`--compressed`, `-L`, `-k`). */
data class CurlFlags(val compressed: Boolean = false, val followRedirects: Boolean = false, val insecure: Boolean = false)

/**
 * How a command is written. [maskSecrets] replaces the value of credential headers and secret-looking query values with
 * [SecretMask.PLACEHOLDER], for copies that are shown or stored (history). [powerShell7] selects the PowerShell 7.3+
 * profile that passes quotes to native programs correctly; the default profile works on Windows PowerShell 5.1 and 7.
 */
data class CurlOptions(
    val shell: CurlShell = CurlShell.BASH,
    val flags: CurlFlags = CurlFlags(),
    val maskSecrets: Boolean = false,
    val multiline: Boolean = true,
    val powerShell7: Boolean = false,
)

/** A file the command refers to and that the user has to save next to it (cmd fallback for bodies that cannot be quoted). */
data class SideFile(val name: String, val content: String)

enum class CurlWarning { CMD_LINE_TOO_LONG, BODY_IN_SIDE_FILE }

data class CurlCommand(val text: String, val sideFiles: List<SideFile> = emptyList(), val warnings: List<CurlWarning> = emptyList())

/** Why a value cannot be written safely for a dialect. The emitter refuses instead of guessing (research 03, 7.4). */
enum class CurlProblem { NUL_CHARACTER, LONE_SURROGATE, HEADER_LINE_BREAK, HEADER_NAME_INVALID, ARGUMENT_NOT_REPRESENTABLE }

class CurlUnsupportedException(val problem: CurlProblem, val shell: CurlShell) :
    IllegalArgumentException("$problem for $shell")

/** What a parse found that it did not turn into part of the request. Never guessed, always reported. */
enum class IgnoredReason {
    /** Not in the option table; the next word is not consumed because its arity is unknown. */
    UNKNOWN_OPTION,

    /** A known option that does not change the request the IDE sends (proxy, certificates, output file, retries). */
    NO_EFFECT_HERE,

    /** A known option with behavior the plugin does not implement (digest auth, stdin data, several URLs, `--next`). */
    UNSUPPORTED,

    /** `@file` data or headers whose content was not supplied to the parser. */
    FILE_NOT_READ,

    INVALID,
}

data class IgnoredFlag(val flag: String, val reason: IgnoredReason)

enum class NoteKind {
    UNRESOLVED_VARIABLE, COMMAND_SUBSTITUTION, TAIL_IGNORED, CMD_AMPERSAND_ENDED, SCHEME_ASSUMED, GLOB_URL, LOCATION_TRUSTED,
    INVALID_LONG_OPTION_SYNTAX, POWERSHELL_73_STYLE, BASIC_WITHOUT_PASSWORD, FILE_AS_PART_TEXT, UNKNOWN_METHOD,
}

data class ParseNote(val kind: NoteKind, val detail: String = "")

data class ParsedCurl(
    val spec: RequestSpec,
    val flags: CurlFlags,
    val shell: CurlShell,
    val ignored: List<IgnoredFlag>,
    val notes: List<ParseNote>,
)

enum class CurlRejection { EMPTY, NOT_CURL, POWERSHELL_WEB_CMDLET, NO_URL }

sealed interface CurlParseResult {
    data class Parsed(val request: ParsedCurl) : CurlParseResult
    data class Rejected(val reason: CurlRejection) : CurlParseResult
}

/** Header names and query keys whose values are credentials; one place for emitter, history and exports. */
object SecretMask {
    const val PLACEHOLDER = "<redacted>"

    private val HEADERS = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key", "x-auth-token",
        "x-csrf-token", "x-xsrf-token", "x-amz-security-token",
    )
    private val KEY = Regex("(?i)(token|secret|password|passwd|pwd|api[_-]?key|apikey|auth|session|jwt|bearer|credential|signature|sig|code)")

    fun isSecretHeader(name: String): Boolean = name.trim().lowercase() in HEADERS

    fun isSecretKey(name: String): Boolean = KEY.containsMatchIn(name)

    /** [url] with the value of every secret-looking query parameter replaced; the rest is kept byte for byte. */
    fun maskUrl(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val hash = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val query = url.substring(q + 1, hash).split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0 && isSecretKey(pair.substring(0, eq))) pair.substring(0, eq + 1) + PLACEHOLDER else pair
        }
        return url.substring(0, q + 1) + query + url.substring(hash)
    }
}
