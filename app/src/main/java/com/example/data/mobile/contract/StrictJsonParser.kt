package com.example.data.mobile.contract

/**
 * Typed JSON element tree preserving exact types, string decimals, and distinguishing
 * objects from arrays and integers from floating-point numbers.
 */
sealed interface StrictJsonElement {
    fun asObject(): StrictJsonObject =
        this as? StrictJsonObject ?: throw CanonicalSerializationException("Expected JSON object, got ${this::class.simpleName}")

    fun asArray(): StrictJsonArray =
        this as? StrictJsonArray ?: throw CanonicalSerializationException("Expected JSON array, got ${this::class.simpleName}")

    fun asString(): String =
        (this as? StrictJsonString)?.value ?: throw CanonicalSerializationException("Expected JSON string, got ${this::class.simpleName}")

    fun asInt(): Int {
        val num = this as? StrictJsonNumber ?: throw CanonicalSerializationException("Expected JSON number, got ${this::class.simpleName}")
        if (!num.isInt) {
            throw CanonicalSerializationException("Expected integer, got floating-point number: ${num.rawString}")
        }
        return num.rawString.toInt()
    }

    fun asLong(): Long {
        val num = this as? StrictJsonNumber ?: throw CanonicalSerializationException("Expected JSON number, got ${this::class.simpleName}")
        if (!num.isInt) {
            throw CanonicalSerializationException("Expected integer, got floating-point number: ${num.rawString}")
        }
        return num.rawString.toLong()
    }
}

data class StrictJsonObject(val properties: Map<String, StrictJsonElement>) : StrictJsonElement {
    operator fun get(key: String): StrictJsonElement? = properties[key]
    fun require(key: String): StrictJsonElement =
        properties[key] ?: throw CanonicalSerializationException("Missing required property '$key'")
}

data class StrictJsonArray(val elements: List<StrictJsonElement>) : StrictJsonElement {
    operator fun get(index: Int): StrictJsonElement = elements[index]
    val size: Int get() = elements.size
}

data class StrictJsonString(val value: String) : StrictJsonElement

data class StrictJsonNumber(val rawString: String, val isInt: Boolean) : StrictJsonElement

data class StrictJsonBoolean(val value: Boolean) : StrictJsonElement

object StrictJsonNull : StrictJsonElement {
    override fun toString(): String = "null"
}

/**
 * Pure Kotlin parser that enforces Small Trader contract parsing rules:
 * 1. Strictly rejects duplicate object keys (Negative 10b parity).
 * 2. Detects and rejects lone / unpaired Unicode surrogates.
 * 3. Preserves exact string decimal numbers and flags floating-point representations.
 * 4. Strictly distinguishes objects from lists and null from missing.
 */
class StrictJsonParser private constructor(private val src: String) {
    private var pos: Int = 0
    private val len: Int = src.length

    private fun parseValue(): StrictJsonElement {
        skipWhitespace()
        if (pos >= len) {
            throw CanonicalSerializationException("Unexpected end of JSON input")
        }
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            '-', in '0'..'9' -> parseNumber()
            else -> throw CanonicalSerializationException("Unexpected character '$c' at position $pos")
        }
    }

    private fun parseObject(): StrictJsonObject {
        consume('{')
        skipWhitespace()
        val props = LinkedHashMap<String, StrictJsonElement>()
        if (peek() == '}') {
            consume('}')
            return StrictJsonObject(props)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') {
                throw CanonicalSerializationException("Expected string property key at position $pos, got '${peek()}'")
            }
            val key = (parseString() as StrictJsonString).value
            if (props.containsKey(key)) {
                throw CanonicalSerializationException("Duplicate JSON key detected: '$key'")
            }
            skipWhitespace()
            consume(':')
            skipWhitespace()
            val value = parseValue()
            props[key] = value
            skipWhitespace()
            val next = peek()
            if (next == ',') {
                consume(',')
            } else if (next == '}') {
                consume('}')
                break
            } else {
                throw CanonicalSerializationException("Expected ',' or '}' at position $pos, got '$next'")
            }
        }
        return StrictJsonObject(props)
    }

    private fun parseArray(): StrictJsonArray {
        consume('[')
        skipWhitespace()
        val items = mutableListOf<StrictJsonElement>()
        if (peek() == ']') {
            consume(']')
            return StrictJsonArray(items)
        }
        while (true) {
            skipWhitespace()
            val value = parseValue()
            items.add(value)
            skipWhitespace()
            val next = peek()
            if (next == ',') {
                consume(',')
            } else if (next == ']') {
                consume(']')
                break
            } else {
                throw CanonicalSerializationException("Expected ',' or ']' at position $pos, got '$next'")
            }
        }
        return StrictJsonArray(items)
    }

    private fun parseString(): StrictJsonString {
        consume('"')
        val sb = StringBuilder()
        while (pos < len) {
            val c = src[pos++]
            if (c == '"') {
                return StrictJsonString(sb.toString())
            }
            if (c == '\\') {
                if (pos >= len) throw CanonicalSerializationException("Unterminated escape sequence in string")
                when (val esc = src[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > len) throw CanonicalSerializationException("Incomplete \\u hex escape in string")
                        val hex = src.substring(pos, pos + 4)
                        pos += 4
                        val code = hex.toIntOrNull(16)
                            ?: throw CanonicalSerializationException("Invalid hex in \\u escape: $hex")
                        val char1 = code.toChar()
                        if (Character.isHighSurrogate(char1)) {
                            // Must be followed by \u low surrogate
                            if (pos + 6 <= len && src.substring(pos, pos + 2) == "\\u") {
                                val hex2 = src.substring(pos + 2, pos + 6)
                                val code2 = hex2.toIntOrNull(16)
                                if (code2 != null && Character.isLowSurrogate(code2.toChar())) {
                                    pos += 6
                                    sb.append(char1)
                                    sb.append(code2.toChar())
                                    continue
                                }
                            }
                            throw CanonicalSerializationException("Unpaired high Unicode surrogate detected: 0x${Integer.toHexString(code)}")
                        } else if (Character.isLowSurrogate(char1)) {
                            throw CanonicalSerializationException("Unpaired low Unicode surrogate detected: 0x${Integer.toHexString(code)}")
                        }
                        sb.append(char1)
                    }
                    else -> throw CanonicalSerializationException("Invalid escape sequence '\\$esc' in string")
                }
            } else {
                if (Character.isHighSurrogate(c)) {
                    if (pos < len && Character.isLowSurrogate(src[pos])) {
                        sb.append(c)
                        sb.append(src[pos++])
                    } else {
                        throw CanonicalSerializationException("Unpaired high Unicode surrogate detected: 0x${Integer.toHexString(c.code)}")
                    }
                } else if (Character.isLowSurrogate(c)) {
                    throw CanonicalSerializationException("Unpaired low Unicode surrogate detected: 0x${Integer.toHexString(c.code)}")
                } else {
                    sb.append(c)
                }
            }
        }
        throw CanonicalSerializationException("Unterminated string literal")
    }

    private fun parseNumber(): StrictJsonNumber {
        val start = pos
        if (peek() == '-') consume('-')
        while (pos < len && src[pos] in '0'..'9') {
            pos++
        }
        var isFloat = false
        if (pos < len && src[pos] == '.') {
            isFloat = true
            pos++
            while (pos < len && src[pos] in '0'..'9') {
                pos++
            }
        }
        if (pos < len && (src[pos] == 'e' || src[pos] == 'E')) {
            isFloat = true
            pos++
            if (pos < len && (src[pos] == '+' || src[pos] == '-')) {
                pos++
            }
            while (pos < len && src[pos] in '0'..'9') {
                pos++
            }
        }
        val raw = src.substring(start, pos)
        return StrictJsonNumber(raw, isInt = !isFloat)
    }

    private fun parseBoolean(): StrictJsonBoolean {
        if (src.startsWith("true", pos)) {
            pos += 4
            return StrictJsonBoolean(true)
        }
        if (src.startsWith("false", pos)) {
            pos += 5
            return StrictJsonBoolean(false)
        }
        throw CanonicalSerializationException("Invalid boolean token at position $pos")
    }

    private fun parseNull(): StrictJsonNull {
        if (src.startsWith("null", pos)) {
            pos += 4
            return StrictJsonNull
        }
        throw CanonicalSerializationException("Invalid null token at position $pos")
    }

    private fun skipWhitespace() {
        while (pos < len && (src[pos] == ' ' || src[pos] == '\t' || src[pos] == '\n' || src[pos] == '\r')) {
            pos++
        }
    }

    private fun peek(): Char = if (pos < len) src[pos] else throw CanonicalSerializationException("Unexpected end of JSON")

    private fun consume(expected: Char) {
        if (pos >= len || src[pos] != expected) {
            val actual = if (pos < len) "'${src[pos]}'" else "EOF"
            throw CanonicalSerializationException("Expected '$expected' at position $pos, got $actual")
        }
        pos++
    }

    companion object {
        fun parse(jsonStr: String): StrictJsonElement {
            val parser = StrictJsonParser(jsonStr)
            val result = parser.parseValue()
            parser.skipWhitespace()
            if (parser.pos < parser.len) {
                throw CanonicalSerializationException("Extra trailing characters after JSON value at position ${parser.pos}")
            }
            return result
        }
    }
}
