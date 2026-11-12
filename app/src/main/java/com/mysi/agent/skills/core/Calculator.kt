package com.mysi.agent.skills.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

class CalcException(message: String) : Exception(message)

/**
 * Exact decimal arithmetic for the model, so it never does sums in its head. A hand-written
 * parser builds the result directly; nothing is ever evaluated as code.
 *
 * Grammar: + - * / ^ (right-associative, "**" also works), parentheses, unary minus, postfix %
 * ("15%" is 0.15; "200 + 15%" adds 15% of 200 = 230, "200 - 15%" = 170; "15% of 200" = 30),
 * constants pi and e, and functions sqrt, abs, round(x[, places]), floor, ceil, min, max, sum,
 * avg, mod, pow, ln, log (base 10), log2, exp, percent(part, whole) and
 * percent_change(old, new). Thousands separators like 1,250,000 or 12,50,000 are accepted
 * outside function calls.
 */
object Calculator {
    /**
     * [value] carries 34 significant digits wherever a division or root does not end; the text
     * shows 20 and says so when that cut changes the number.
     */
    data class Result(val value: BigDecimal, val approximate: Boolean) {
        fun text(): String {
            val (shown, cut) = display(value)
            val note = when {
                approximate -> " (approximate: uses a 15-digit function)"
                cut -> " (rounded to $SHOWN_DIGITS significant digits)"
                else -> ""
            }
            return shown + note
        }
    }

    const val MAX_LENGTH = 500
    private const val MAX_DEPTH = 64
    private const val MAX_DIGITS = 1_000
    private const val SHOWN_DIGITS = 20
    private val MC = MathContext(34, RoundingMode.HALF_EVEN)
    private val HUNDRED = BigDecimal(100)
    private val PI = BigDecimal("3.141592653589793238462643383279503")
    private val E = BigDecimal("2.718281828459045235360287471352662")

    fun evaluate(expression: String): Result {
        val src = expression.trim()
        if (src.isEmpty()) throw CalcException("The expression is empty.")
        if (src.length > MAX_LENGTH) throw CalcException("The expression is longer than $MAX_LENGTH characters; split it into steps.")
        val p = Parser(lex(src))
        val v = p.expression()
        if (!p.atEnd()) throw CalcException("Unexpected '${p.peekText()}'. Check the operators and brackets.")
        return Result(v.value.stripTrailingZeros(), p.approximate)
    }

    /**
     * Whole numbers up to 34 digits are shown exactly; other values with 20 significant digits,
     * in plain notation for ordinary sizes and scientific for very large or very small ones.
     */
    fun display(v: BigDecimal): Pair<String, Boolean> {
        if (v.signum() == 0) return "0" to false
        val exact = v.stripTrailingZeros()
        if (exact.scale() <= 0 && exact.precision() - exact.scale() <= MC.precision) return exact.toPlainString() to false
        val r = v.round(MathContext(SHOWN_DIGITS, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val cut = r.compareTo(v) != 0
        val exp = r.precision() - r.scale() - 1
        val s = if (exp >= 21 || exp < -9) r.toString().replace("E+", "e").replace("E", "e") else r.toPlainString()
        return s to cut
    }

    private enum class T { NUM, ID, OP, LP, RP, COMMA, END }

    private data class Tok(val t: T, val text: String, val num: BigDecimal? = null)

    private val unicodeOps = mapOf(Char(0x00D7) to "*", Char(0x00F7) to "/", Char(0x2212) to "-", Char(0x2013) to "-")

    private fun lex(s: String): List<Tok> {
        val out = mutableListOf<Tok>()
        val parens = ArrayDeque<Boolean>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val inCall = parens.lastOrNull() == true
                    val grouped = if (!inCall) Regex("^(\\d{1,3}(,\\d{3})+|\\d{1,2}(,\\d{2})+,\\d{3})(?![\\d,])").find(s.substring(i)) else null
                    val m = Regex("^(\\d*\\.?\\d+|\\d+\\.)([eE][+-]?\\d{1,4})?").find(s.substring(i))!!
                    val (text, len) = when {
                        grouped != null && grouped.value.length >= m.value.length -> {
                            val rest = Regex("^(\\.\\d+)?([eE][+-]?\\d{1,4})?").find(s.substring(i + grouped.value.length))!!.value
                            (grouped.value.replace(",", "") + rest) to (grouped.value.length + rest.length)
                        }
                        else -> m.value to m.value.length
                    }
                    val num = runCatching { BigDecimal(text) }.getOrNull() ?: throw CalcException("'$text' is not a number.")
                    out += Tok(T.NUM, text, num)
                    i += len
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    val id = s.substring(i, j).lowercase()
                    out += Tok(T.ID, id)
                    i = j
                    var k = j
                    while (k < s.length && s[k].isWhitespace()) k++
                    if (k < s.length && s[k] == '(') {
                        parens.addLast(true)
                        out += Tok(T.LP, "(")
                        i = k + 1
                    }
                }
                c == '(' -> { parens.addLast(false); out += Tok(T.LP, "("); i++ }
                c == ')' -> { parens.removeLastOrNull(); out += Tok(T.RP, ")"); i++ }
                c == ',' || c == ';' -> { out += Tok(T.COMMA, ","); i++ }
                c == '*' && i + 1 < s.length && s[i + 1] == '*' -> { out += Tok(T.OP, "^"); i += 2 }
                c in "+-*/^%" -> { out += Tok(T.OP, c.toString()); i++ }
                c in unicodeOps -> { out += Tok(T.OP, unicodeOps.getValue(c)); i++ }
                c == '=' -> i++
                else -> throw CalcException("'$c' is not allowed. Use numbers, + - * / ^ %, brackets and the listed functions.")
            }
            if (out.size > 400) throw CalcException("The expression is too long; split it into steps.")
        }
        out += Tok(T.END, "")
        return out
    }

    /** A value and whether it was written as a bare percentage (for "200 + 15%"). */
    private class V(val value: BigDecimal, val percent: Boolean = false)

    private class Parser(private val toks: List<Tok>) {
        private var pos = 0
        private var depth = 0
        var approximate = false

        fun atEnd() = toks[pos].t == T.END

        fun peekText() = toks[pos].text.ifEmpty { "end" }

        private fun peek() = toks[pos]

        private fun isOp(op: String) = peek().t == T.OP && peek().text == op

        private fun enter() {
            if (++depth > MAX_DEPTH) throw CalcException("The expression is nested too deeply.")
        }

        fun expression(): V {
            enter()
            var left = term()
            while (isOp("+") || isOp("-")) {
                val op = toks[pos++].text
                val right = term()
                val r = if (right.percent) left.value.multiply(right.value).let(::check) else right.value
                left = V(check(if (op == "+") left.value.add(r) else left.value.subtract(r)))
            }
            depth--
            return left
        }

        private fun term(): V {
            var left = unary()
            while (true) {
                val op = when {
                    isOp("*") -> "*"
                    isOp("/") -> "/"
                    peek().t == T.ID && peek().text == "of" -> "*"
                    else -> break
                }
                pos++
                val right = unary()
                left = V(
                    if (op == "*") check(left.value.multiply(right.value))
                    else divide(left.value, right.value),
                )
            }
            return left
        }

        private fun unary(): V {
            if (isOp("-")) {
                pos++
                val v = unary()
                return V(v.value.negate(), v.percent)
            }
            if (isOp("+")) {
                pos++
                return unary()
            }
            return power()
        }

        private fun power(): V {
            val base = postfix()
            if (isOp("^")) {
                pos++
                enter()
                val exp = unary()
                depth--
                return V(pow(base.value, exp.value))
            }
            return base
        }

        private fun postfix(): V {
            var v = primary()
            while (isOp("%")) {
                pos++
                v = V(v.value.divide(HUNDRED), percent = true)
            }
            return v
        }

        private fun primary(): V {
            val t = toks[pos]
            return when (t.t) {
                T.NUM -> { pos++; V(check(t.num!!)) }
                T.LP -> {
                    pos++
                    val v = expression()
                    expect(T.RP, "a closing bracket")
                    V(v.value)
                }
                T.ID -> {
                    pos++
                    if (toks[pos - 1].text in constants && !(pos < toks.size && toks[pos].t == T.LP)) return V(constants.getValue(t.text))
                    if (pos >= toks.size || toks[pos].t != T.LP) throw CalcException("Unknown name '${t.text}'. Known: pi, e and the functions ${functions.joinToString(", ")}.")
                    pos++
                    val args = mutableListOf<BigDecimal>()
                    if (peek().t != T.RP) {
                        args += expression().value
                        while (peek().t == T.COMMA) {
                            pos++
                            args += expression().value
                        }
                    }
                    expect(T.RP, "a closing bracket after the arguments of ${t.text}")
                    V(call(t.text, args))
                }
                T.END -> throw CalcException("The expression ends too early.")
                else -> throw CalcException("Unexpected '${t.text}'.")
            }
        }

        private fun expect(t: T, what: String) {
            if (peek().t != t) throw CalcException("Expected $what, found '${peekText()}'.")
            pos++
        }

        private fun check(v: BigDecimal): BigDecimal {
            if (v.signum() != 0 && (v.precision() > MAX_DIGITS || v.precision() - v.scale() > MAX_DIGITS || v.scale() > MAX_DIGITS))
                throw CalcException("The result is too large or too small to handle exactly.")
            return v
        }

        private fun divide(a: BigDecimal, b: BigDecimal): BigDecimal {
            if (b.signum() == 0) throw CalcException("Division by zero.")
            return try {
                check(a.divide(b))
            } catch (e: ArithmeticException) {
                // The quotient does not end, e.g. 1/3: keep 34 significant digits.
                check(a.divide(b, MC))
            }
        }

        private fun pow(base: BigDecimal, exp: BigDecimal): BigDecimal {
            val e = exp.stripTrailingZeros()
            if (e.scale() <= 0) {
                val n = try { e.intValueExact() } catch (x: ArithmeticException) { throw CalcException("The exponent is too large.") }
                if (kotlin.math.abs(n) > 10_000) throw CalcException("The exponent is too large.")
                if (base.signum() == 0 && n < 0) throw CalcException("Division by zero.")
                val digits = (base.precision().toLong()) * kotlin.math.abs(n)
                val whole = if (digits <= MAX_DIGITS) base.pow(kotlin.math.abs(n)) else base.pow(kotlin.math.abs(n), MC)
                return if (n >= 0) check(whole) else divide(BigDecimal.ONE, whole)
            }
            if (base.signum() < 0) throw CalcException("A negative number cannot be raised to a fractional power.")
            return approx(Math.pow(base.toDouble(), e.toDouble()))
        }

        private fun approx(d: Double): BigDecimal {
            if (d.isNaN() || d.isInfinite()) throw CalcException("The result is not a finite number.")
            approximate = true
            return BigDecimal(d).round(MathContext(15, RoundingMode.HALF_EVEN))
        }

        private fun one(name: String, args: List<BigDecimal>): BigDecimal {
            if (args.size != 1) throw CalcException("$name takes one number.")
            return args[0]
        }

        private fun call(name: String, args: List<BigDecimal>): BigDecimal =
            when (name) {
                "sqrt" -> one(name, args).let { x ->
                    if (x.signum() < 0) throw CalcException("sqrt of a negative number is not a real number.")
                    sqrt(x)
                }
                "abs" -> one(name, args).abs()
                "floor" -> one(name, args).setScale(0, RoundingMode.FLOOR)
                "ceil" -> one(name, args).setScale(0, RoundingMode.CEILING)
                "round" -> {
                    if (args.size !in 1..2) throw CalcException("round takes a number and optionally the decimal places.")
                    val places = args.getOrNull(1)?.toInt()?.coerceIn(-20, 20) ?: 0
                    args[0].setScale(places, RoundingMode.HALF_UP)
                }
                "min", "max", "sum", "avg", "mean", "average" -> {
                    if (args.isEmpty()) throw CalcException("$name needs at least one number.")
                    when (name) {
                        "min" -> args.minOrNull()!!
                        "max" -> args.maxOrNull()!!
                        "sum" -> check(args.fold(BigDecimal.ZERO, BigDecimal::add))
                        else -> divide(args.fold(BigDecimal.ZERO, BigDecimal::add), BigDecimal(args.size))
                    }
                }
                "mod" -> {
                    if (args.size != 2) throw CalcException("mod takes two numbers: mod(a, b).")
                    if (args[1].signum() == 0) throw CalcException("Division by zero.")
                    args[0].remainder(args[1])
                }
                "pow" -> {
                    if (args.size != 2) throw CalcException("pow takes two numbers: pow(base, exponent).")
                    pow(args[0], args[1])
                }
                "percent" -> {
                    if (args.size != 2) throw CalcException("percent takes two numbers: percent(part, whole).")
                    divide(args[0].multiply(HUNDRED), args[1])
                }
                "percent_change" -> {
                    if (args.size != 2) throw CalcException("percent_change takes two numbers: percent_change(old, new).")
                    divide(args[1].subtract(args[0]).multiply(HUNDRED), args[0])
                }
                "ln", "log", "log10", "log2" -> one(name, args).let { x ->
                    if (x.signum() <= 0) throw CalcException("$name needs a positive number.")
                    val d = x.toDouble()
                    approx(
                        when (name) {
                            "ln" -> Math.log(d)
                            "log2" -> Math.log(d) / Math.log(2.0)
                            else -> Math.log10(d)
                        },
                    )
                }
                "exp" -> approx(Math.exp(one(name, args).toDouble()))
                else -> throw CalcException("Unknown function '$name'. Known: ${functions.joinToString(", ")}.")
            }
    }

    private val constants = mapOf("pi" to PI, "e" to E)
    private val functions = listOf("sqrt", "abs", "round", "floor", "ceil", "min", "max", "sum", "avg", "mod", "pow", "ln", "log", "log2", "exp", "percent", "percent_change")

    /** Square root to 34 significant digits by Newton's method; exact for perfect squares. */
    fun sqrt(x: BigDecimal): BigDecimal {
        if (x.signum() == 0) return BigDecimal.ZERO
        val mc = MathContext(MC.precision + 4, RoundingMode.HALF_EVEN)
        var g = BigDecimal(Math.sqrt(x.toDouble()).takeIf { it.isFinite() && it > 0 } ?: 1.0)
        val two = BigDecimal(2)
        for (i in 0 until 200) {
            val next = g.add(x.divide(g, mc)).divide(two, mc)
            if (next.compareTo(g) == 0) break
            g = next
        }
        return g.round(MC).stripTrailingZeros()
    }
}
