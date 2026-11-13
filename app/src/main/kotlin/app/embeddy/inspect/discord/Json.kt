package app.embeddy.inspect.discord

/**
 * A small JSON reader for component-embed payloads, oEmbed documents and
 * similar page-supplied JSON.
 *
 * Android's org.json is only a stub in JVM unit tests and silently drops
 * duplicate keys and number formatting details, so the validator parses with
 * this instead: objects keep key order, numbers keep whether they were written
 * as integers, and errors carry the offset where parsing stopped.
 */
sealed interface Json {
    data class Obj(val fields: LinkedHashMap<String, Json>) : Json {
        operator fun get(key: String): Json? = fields[key]
        val keys: Set<String> get() = fields.keys
    }

    data class Arr(val items: List<Json>) : Json

    data class Str(val value: String) : Json

    /** [integral] is true when the literal had no fraction or exponent and fits a Long */
    data class Num(val value: Double, val integral: Boolean) : Json {
        /** The value as a Long when it is a whole number */
        val asLong: Long? get() = if (integral || (value % 1.0 == 0.0 && !value.isInfinite())) value.toLong() else null
    }

    data class Bool(val value: Boolean) : Json

    data object Null : Json
}

/** Thrown for malformed JSON; [offset] is the character index where parsing failed */
class JsonParseException(message: String, val offset: Int) : Exception("$message at position $offset")

/** Parse a complete JSON document (surrounding whitespace allowed). */
fun parseJson(text: String): Json = JsonReader(text).readDocument()

/** Convenience accessors used by the validator and unfurl analysis */
val Json?.stringOrNull: String? get() = (this as? Json.Str)?.value
val Json?.objOrNull: Json.Obj? get() = this as? Json.Obj
val Json?.arrOrNull: Json.Arr? get() = this as? Json.Arr

private class JsonReader(private val s: String) {
    private var i = 0

    fun readDocument(): Json {
        skipWs()
        val v = readValue(depth = 0)
        skipWs()
        if (i != s.length) fail("Unexpected trailing characters")
        return v
    }

    private fun fail(message: String): Nothing = throw JsonParseException(message, i)

    private fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun readValue(depth: Int): Json {
        // Payloads are at most a few KB; this only stops pathological nesting
        if (depth > MAX_DEPTH) fail("Nested too deeply")
        if (i >= s.length) fail("Unexpected end of input")
        return when (val c = s[i]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> Json.Str(readString())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c in '0'..'9') readNumber() else fail("Unexpected character '$c'")
        }
    }

    private fun literal(word: String, value: Json): Json {
        if (!s.startsWith(word, i)) fail("Unexpected token")
        i += word.length
        return value
    }

    private fun readObject(depth: Int): Json.Obj {
        i++ // {
        val fields = LinkedHashMap<String, Json>()
        skipWs()
        if (i < s.length && s[i] == '}') {
            i++
            return Json.Obj(fields)
        }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') fail("Expected a property name")
            val key = readString()
            skipWs()
            if (i >= s.length || s[i] != ':') fail("Expected ':'")
            i++
            skipWs()
            // Like JSON.parse, a repeated key keeps the last value
            fields[key] = readValue(depth + 1)
            skipWs()
            if (i >= s.length) fail("Unterminated object")
            when (s[i]) {
                ',' -> i++
                '}' -> {
                    i++
                    return Json.Obj(fields)
                }
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun readArray(depth: Int): Json.Arr {
        i++ // [
        val items = ArrayList<Json>()
        skipWs()
        if (i < s.length && s[i] == ']') {
            i++
            return Json.Arr(items)
        }
        while (true) {
            skipWs()
            items += readValue(depth + 1)
            skipWs()
            if (i >= s.length) fail("Unterminated array")
            when (s[i]) {
                ',' -> i++
                ']' -> {
                    i++
                    return Json.Arr(items)
                }
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        i++ // opening quote
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) fail("Unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    if (i >= s.length) fail("Unterminated escape")
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("Bad unicode escape")
                            val hex = s.substring(i, i + 4)
                            out.append(hex.toIntOrNull(16)?.toChar() ?: fail("Bad unicode escape"))
                            i += 4
                        }
                        else -> fail("Bad escape '\\$e'")
                    }
                }
                c < ' ' -> fail("Control character in string")
                else -> out.append(c)
            }
        }
    }

    private fun readNumber(): Json.Num {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length) fail("Bad number")
        if (s[i] == '0') {
            i++
        } else if (s[i] in '1'..'9') {
            while (i < s.length && s[i].isAsciiDigit()) i++
        } else {
            fail("Bad number")
        }
        var integral = true
        if (i < s.length && s[i] == '.') {
            integral = false
            i++
            if (i >= s.length || !s[i].isAsciiDigit()) fail("Bad number")
            while (i < s.length && s[i].isAsciiDigit()) i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            integral = false
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || !s[i].isAsciiDigit()) fail("Bad number")
            while (i < s.length && s[i].isAsciiDigit()) i++
        }
        val literal = s.substring(start, i)
        val value = literal.toDoubleOrNull() ?: fail("Bad number")
        return Json.Num(value, integral && literal.toLongOrNull() != null)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    companion object {
        private const val MAX_DEPTH = 64
    }
}
