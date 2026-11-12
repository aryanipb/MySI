package com.mysi.agent.web

/**
 * HTML character references: every HTML 4 named entity, the common HTML 5 additions, and
 * decimal/hex numeric references with the WHATWG replacements (C1 controls map through
 * windows-1252; zero, surrogates and out-of-range values become U+FFFD).
 */
object Entities {
    private val LATIN1 =
        ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 " +
            "acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde " +
            "Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc " +
            "Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring " +
            "aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml " +
            "divide oslash ugrave uacute ucirc uuml yacute thorn yuml").split(' ')

    private val GREEK_UPPER = "Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi Omicron Pi Rho".split(' ')
    private val GREEK_UPPER2 = "Sigma Tau Upsilon Phi Chi Psi Omega".split(' ')
    private val GREEK_LOWER =
        "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigmaf sigma tau upsilon phi chi psi omega".split(' ')

    private const val OTHERS =
        "quot:34 amp:38 apos:39 lt:60 gt:62 OElig:338 oelig:339 Scaron:352 scaron:353 Yuml:376 fnof:402 circ:710 " +
            "tilde:732 thetasym:977 upsih:978 piv:982 ensp:8194 emsp:8195 thinsp:8201 hairsp:8202 zwnj:8204 zwj:8205 " +
            "lrm:8206 rlm:8207 hyphen:8208 dash:8208 ndash:8211 mdash:8212 horbar:8213 lsquo:8216 rsquo:8217 rsquor:8217 " +
            "sbquo:8218 lsquor:8218 ldquo:8220 rdquo:8221 rdquor:8221 bdquo:8222 ldquor:8222 dagger:8224 Dagger:8225 " +
            "bull:8226 bullet:8226 hellip:8230 mldr:8230 permil:8240 prime:8242 Prime:8243 lsaquo:8249 rsaquo:8250 " +
            "oline:8254 frasl:8260 euro:8364 image:8465 weierp:8472 real:8476 trade:8482 TRADE:8482 alefsym:8501 " +
            "larr:8592 uarr:8593 rarr:8594 darr:8595 harr:8596 crarr:8629 lArr:8656 uArr:8657 rArr:8658 dArr:8659 " +
            "hArr:8660 forall:8704 part:8706 exist:8707 empty:8709 nabla:8711 isin:8712 notin:8713 ni:8715 prod:8719 " +
            "sum:8721 minus:8722 lowast:8727 radic:8730 prop:8733 infin:8734 ang:8736 and:8743 or:8744 cap:8745 " +
            "cup:8746 int:8747 there4:8756 sim:8764 cong:8773 asymp:8776 ne:8800 equiv:8801 le:8804 ge:8805 sub:8834 " +
            "sup:8835 nsub:8836 sube:8838 supe:8839 oplus:8853 otimes:8855 perp:8869 sdot:8901 lceil:8968 rceil:8969 " +
            "lfloor:8970 rfloor:8971 lang:10216 rang:10217 loz:9674 spades:9824 clubs:9827 hearts:9829 diams:9830 " +
            "starf:9733 star:9734 check:10003 cross:10007 Tab:9 NewLine:10 excl:33 num:35 dollar:36 percnt:37 lpar:40 " +
            "rpar:41 ast:42 plus:43 comma:44 period:46 sol:47 colon:58 semi:59 equals:61 quest:63 commat:64 lsqb:91 " +
            "lbrack:91 bsol:92 rsqb:93 rbrack:93 Hat:94 lowbar:95 grave:96 lcub:123 lbrace:123 verbar:124 vert:124 " +
            "rcub:125 rbrace:125 half:189 centerdot:183 ZeroWidthSpace:8203 NonBreakingSpace:160 QUOT:34 AMP:38 LT:60 " +
            "GT:62 COPY:169 REG:174"

    private val named: Map<String, Int> =
        HashMap<String, Int>(512).apply {
            LATIN1.forEachIndexed { i, n -> put(n, 160 + i) }
            GREEK_UPPER.forEachIndexed { i, n -> put(n, 913 + i) }
            GREEK_UPPER2.forEachIndexed { i, n -> put(n, 931 + i) }
            GREEK_LOWER.forEachIndexed { i, n -> put(n, 945 + i) }
            OTHERS.split(' ').forEach { pair ->
                val (n, cp) = pair.split(':')
                put(n, cp.toInt())
            }
        }

    /** Entities browsers still decode without the trailing semicolon (the common legacy ones). */
    private val legacy = setOf("amp", "lt", "gt", "quot", "nbsp", "copy", "reg", "AMP", "LT", "GT", "QUOT", "COPY", "REG")

    private val c1 =
        intArrayOf(
            0x20AC, 0x81, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x8D, 0x017D, 0x8F,
            0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x9D, 0x017E, 0x0178,
        )

    private fun numeric(cp: Long): Int =
        when {
            cp == 0L || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> 0xFFFD
            cp in 0x80..0x9F -> c1[(cp - 0x80).toInt()]
            else -> cp.toInt()
        }

    /**
     * Decodes character references in [s]. In attribute values ([inAttribute]) a legacy entity
     * without a semicolon followed by a letter, digit or '=' is left alone, as browsers do, so
     * links such as `?a=1&copy=2` survive.
     */
    fun decode(s: String, inAttribute: Boolean = false): String {
        var amp = s.indexOf('&')
        if (amp < 0) return s
        val out = StringBuilder(s.length)
        var last = 0
        while (amp >= 0) {
            out.append(s, last, amp)
            var i = amp + 1
            var consumed = 0
            var value = -1
            if (i < s.length && s[i] == '#') {
                i++
                val hex = i < s.length && (s[i] == 'x' || s[i] == 'X')
                if (hex) i++
                val start = i
                var cp = 0L
                while (i < s.length && i - start < 8) {
                    val d = Character.digit(s[i], if (hex) 16 else 10)
                    if (d < 0) break
                    cp = cp * (if (hex) 16 else 10) + d
                    i++
                }
                if (i > start) {
                    if (i < s.length && s[i] == ';') i++
                    value = numeric(cp)
                    consumed = i - amp
                }
            } else {
                val start = i
                while (i < s.length && i - start < 32 && s[i].isLetterOrDigit()) i++
                val name = s.substring(start, i)
                if (i < s.length && s[i] == ';' && named.containsKey(name)) {
                    value = named.getValue(name)
                    consumed = i + 1 - amp
                } else {
                    // Longest legacy prefix without a semicolon, e.g. "&ampx" or "&nbsp".
                    val prefix = legacy.filter { name.startsWith(it) }.maxByOrNull { it.length }
                    if (prefix != null) {
                        val next = s.getOrNull(start + prefix.length)
                        val blocked = inAttribute && next != null && (next.isLetterOrDigit() || next == '=')
                        if (!blocked) {
                            value = named.getValue(prefix)
                            consumed = 1 + prefix.length
                        }
                    }
                }
            }
            if (value >= 0) {
                out.appendCodePoint(value)
                last = amp + consumed
            } else {
                out.append('&')
                last = amp + 1
            }
            amp = s.indexOf('&', last)
        }
        out.append(s, last, s.length)
        return out.toString()
    }
}
