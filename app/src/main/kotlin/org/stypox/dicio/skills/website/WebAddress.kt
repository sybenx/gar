package org.stypox.dicio.skills.website

import cc.aaro.gar.router.SpokenForm
import java.util.Locale

/**
 * GAR (gar:places). A web address as said or typed: "wikipedia.org", "reddit dot com", "w w w dot
 * example dot co dot uk", "news dot y combinator dot com slash item" (English). Opened with
 * ACTION_VIEW in the default browser ([WebsiteSkill]).
 *
 * @param host the host, lowercase, e.g. "news.ycombinator.com"
 * @param path the path after the host, starting with "/", or empty
 */
data class WebAddress(val host: String, val path: String = "") {
    /** What GAR shows and says: "reddit.com", "reddit.com/r/android". */
    val display: String get() = host + path

    /** The https URL the browser opens. */
    val url: String get() = "https://$host$path"

    companion object {
        /**
         * The address in [said], or null when it is no address: it needs a dot (said or typed)
         * before a top level domain GAR knows (`com`, `org`, ..., or any two letters, a country's),
         * so "open notes", "take me to the mall" and "7.30" are none.
         *
         * @param lenient also take a last word that is one of the common top level domains with
         * no dot before it ("wikipedia org"): the model's slots are word spans of the normalized
         * utterance, which has no dots left between words (`Utterances.normalize`)
         */
        fun parse(said: String, lenient: Boolean = false): WebAddress? {
            var text = said.lowercase(Locale.ROOT).trim()
                .trimEnd('.', '?', '!', ',')
                .replace(Regex("\\s+"), " ")
            text = LEADING.replace(text, "")
            text = text.replace(SCHEME, "")
            text = SPOKEN_WWW.replace(text, "www.")
            text = SPOKEN_DOT.replace(text, ".")
            text = SPOKEN_SLASH.replace(text, "/")
            text = SPOKEN_DASH.replace(text, "-")
            text = text.replace(Regex("\\s*([./])\\s*"), "$1")
            if (lenient && '.' !in text) {
                val words = text.split(' ')
                if (words.size >= 2 && words.last() in LENIENT_TLDS) {
                    text = words.dropLast(1).joinToString(" ") + "." + words.last()
                }
            }
            if ('.' !in text) {
                return null
            }
            // what's left between dots is one label said in several words: "y combinator"
            text = text.replace(" ", "")

            val slash = text.indexOf('/')
            val host = (if (slash < 0) text else text.substring(0, slash)).trimEnd('.')
            val path = if (slash < 0) "" else text.substring(slash).trimEnd('/')
            val labels = host.split('.')
            if (labels.size < 2 || labels.any { !LABEL.matches(it) }) {
                return null
            }
            val tld = labels.last()
            if (tld !in TLDS && !(tld.length == 2 && tld.all { it in 'a'..'z' })) {
                return null
            }
            // "www.com" and the like: a name is needed before the top level domain
            if (labels.dropLast(1).all { it == "www" }) {
                return null
            }
            if (path.isNotEmpty() && !PATH.matches(path)) {
                return null
            }
            return WebAddress(host, path)
        }

        /**
         * The display form of the address that an `open_app` slot names ("reddit dot com" ->
         * "reddit.com"), or null for an app's name. GAR's app resolver reads it before the
         * launcher's apps (`DefaultGarSlotResolver`), so the open skill gets the address.
         */
        fun displayFor(appSlot: String): String? = parse(appSlot, lenient = true)?.display

        private val LEADING = Regex(
            "^((the|a) )?(web ?site|site|web page|webpage|page|url|address|link)( (called|named))? "
        )
        private val SCHEME = Regex("^(https?|h t t p s?)( ?: ?//| colon slash slash) ?")
        private val SPOKEN_WWW = Regex("^(w w w|www|double u double u double u)( dot |\\.)")
        private val SPOKEN_DOT = Regex(" ?\\b(dot|period|point)\\b ?")
        private val SPOKEN_SLASH = Regex(" ?\\b(forward slash|slash)\\b ?")
        private val SPOKEN_DASH = Regex(" ?\\b(dash|hyphen)\\b ?")
        private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val PATH = Regex("/[a-z0-9._~/?=&%+-]*")

        /** Top level domains people say, besides the two-letter country ones (shared with SpokenForm). */
        private val TLDS = SpokenForm.TOP_LEVEL_DOMAINS

        /** The ones taken with no dot said in [parse]'s lenient mode. */
        private val LENIENT_TLDS = setOf("com", "org", "net", "edu", "gov")
    }
}
