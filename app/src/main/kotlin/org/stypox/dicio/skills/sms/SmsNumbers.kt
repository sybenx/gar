package org.stypox.dicio.skills.sms

/**
 * Tells a contact's different numbers apart from one number stored in different ways
 * ("435-555-0167" and "+14355550167"), so that the user is only asked which number when there
 * really are several. The telephone skill lists a contact's numbers through it too.
 */
object SmsNumbers {
    /**
     * [numbers] with each number once, in the order the numbers first appear. Of the forms of one
     * number, the complete international one is kept (with a plus or 00, else the most digits), as
     * stored. A number joins the forms of another only if it is [same] as each of them, so a
     * national form doesn't chain two different international numbers ("435 555 0167", "+1 435
     * 555 0167" and "+44 435 555 0167" are two numbers).
     */
    fun distinct(numbers: List<String>): List<String> {
        val groups = mutableListOf<MutableList<String>>()
        for (number in numbers) {
            groups.firstOrNull { group -> group.all { same(it, number) } }?.add(number)
                ?: groups.add(mutableListOf(number))
        }
        return groups.map { group ->
            // maxWith keeps the first of equals, i.e. the form stored first
            group.maxWith(compareBy({ parse(it).international }, { parse(it).digits.length }))
        }
    }

    /**
     * Whether [a] and [b] are the same number: the same digits whatever the spaces, dashes and
     * brackets, or a national number and the same number with a country code in front ("435 555
     * 0167" and "+1 435 555 0167" or "1 435 555 0167", "07700 900123" and "+44 7700 900123").
     */
    fun same(a: String, b: String): Boolean {
        val first = parse(a)
        val second = parse(b)
        if (first.digits == second.digits) {
            return first.digits.isNotEmpty()
        }
        val (long, short) = if (first.digits.length > second.digits.length) {
            Pair(first, second)
        } else {
            Pair(second, first)
        }
        if (short.international) {
            return false // two complete numbers with different digits
        }

        val nationals = if (long.international) {
            // the national form may start with a trunk prefix the international form drops
            listOf(short.digits, short.digits.removePrefix("0"))
        } else {
            listOf(short.digits)
        }
        return nationals.any { national ->
            national.length >= MIN_NATIONAL_DIGITS && long.digits.endsWith(national) &&
                    countryCodeFits(long.digits.dropLast(national.length), long.international)
        }
    }

    /**
     * A country code in front of a national number: any of 1 to 3 digits in an international
     * number, but only the 1 of North America without a plus ("1 435 555 0167"), so that an area
     * code ("212 555 0100" and "555 0100") doesn't count.
     */
    private fun countryCodeFits(code: String, international: Boolean): Boolean =
        if (international) code.length in 1..3 else code == "1"

    private class Parsed(val international: Boolean, val digits: String)

    private fun parse(number: String): Parsed {
        val digits = number.filter { it in '0'..'9' }
        return when {
            number.trim().startsWith("+") -> Parsed(true, digits)
            digits.startsWith("00") -> Parsed(true, digits.removePrefix("00"))
            else -> Parsed(false, digits)
        }
    }

    /** Shorter national numbers are short codes, which a country code in front doesn't make. */
    private const val MIN_NATIONAL_DIGITS = 7
}
