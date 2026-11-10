package org.stypox.dicio.skills.navigation

import org.dicio.numbers.ParserFormatter // GAR
import org.dicio.numbers.unit.Number
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore // GAR
import org.dicio.skill.skill.Score // GAR
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Navigation

class NavigationSkill(correspondingSkillInfo: SkillInfo, data: StandardRecognizerData<Navigation>)
    : StandardRecognizerSkill<Navigation>(correspondingSkillInfo, data) {
    // GAR: "where's my battery" and "where is my wifi" ask about the phone, not a place
    // (NavigationWords); the battery skill takes the first. gar:places: "how do I get rid of
    // ants" and "get me a coffee" are no place either (PlaceWords.isNoPlace)
    override fun score(ctx: SkillContext, input: String): Pair<Score, Navigation> {
        val (score, query) = super.score(ctx, input)
        val noPlace = NavigationWords.asksAboutPhone(input) || org.stypox.dicio.skills.places
            .PlaceWords.isNoPlace(input, (query as? Navigation.Query)?.where ?: (query as? Navigation.WhereIs)?.where)
        return Pair(if (noPlace) AlwaysWorstScore else score, query)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Navigation): SkillOutput {
        val placeToNavigate: String = when (inputData) {
            is Navigation.Query -> inputData.where ?: return NavigationOutput(null)
            // GAR (gar:places)
            is Navigation.WhereIs -> inputData.where ?: return NavigationOutput(null)
        }
        // GAR (gar:places): home and work, "coffee near me" and web addresses (NavigationPlaces);
        // contacts' addresses only for trips, not "where is mom"
        org.stypox.dicio.skills.places.NavigationPlaces
            .output(ctx, correspondingSkillInfo, placeToNavigate, people = inputData !is Navigation.WhereIs)
            ?.let { return it }

        // GAR (gar:places): the number cleanup is cleanPlace, which saved addresses use too
        val cleanPlaceToNavigate = cleanPlace(ctx.parserFormatter, placeToNavigate)

        // GAR (gar:places): the search goes to the "Navigate with" app (GAR asks which the first
        // time, NavigationPlaces.search) instead of geo:0,0?q= to Android's chooser
        return org.stypox.dicio.skills.places.NavigationPlaces
            .search(ctx, correspondingSkillInfo, cleanPlaceToNavigate)
    }

    companion object {
        /**
         * GAR (gar:places): the spoken place with its numbers as digits ("nine five four six
         * nineteenth avenue" -> "9546 19 avenue"), moved here from generateOutput unchanged so
         * that home and work addresses saved by voice get it too.
         */
        fun cleanPlace(npf: ParserFormatter?, placeToNavigate: String): String {
            return if (npf == null) {
                // No number parser available, feed the spoken input directly to the map application.
                placeToNavigate.trim { it <= ' ' }
            } else {
                val strNums: List<Any> = npf
                    .extractNumber(placeToNavigate)
                    .preferOrdinal(true)
                    .parseMixedWithText()
                    .flatMap { item ->
                        if (item is String) {
                            // allows checking whether there are two single letters next to each other
                            splitWordsAndKeepInBetween.findAll(item).map { it.value }
                        } else {
                            sequenceOf(item)
                        }
                    }

                // Given an address of "9546 19 avenue", the building number is 9546 and the street
                // number is 19.
                //
                // Known issues:
                // - Saying the building number using its digits one by one results in undesired spaces
                //   in between each one
                // - Saying the building number using partial grouping of its digits (but not all of
                //   them) e.g. "ninety five forty six" also results in undesired spaces in between each
                //   partial grouping
                //
                // Based on these known issues, for the example address given above, the speech provided
                // by the user should be "nine thousand five hundred forty six nineteen(th) avenue".
                val placeToNavigateSB = StringBuilder()
                for (i in strNums.indices) {
                    val curr = strNums[i]
                    if (curr is String) {
                        if (i in 1..<(strNums.size - 1)
                            && isSingleDigit(strNums[i - 1])
                            && curr.isBlank()
                            && isSingleDigit(strNums[i + 1])
                        ) {
                            // two consecutive digits or single letters likely go together,
                            // so don't add the space
                            continue
                        }
                        placeToNavigateSB.append(curr)
                    } else if (curr is Number) {
                        if (curr.isInteger) {
                            placeToNavigateSB.append(curr.integerValue())
                        } else {
                            placeToNavigateSB.append(curr.decimalValue())
                        }
                    } else {
                        throw RuntimeException("Item $curr is neither String nor Number")
                    }
                }
                placeToNavigateSB.toString().trim { it <= ' ' }
            }
        }

        val splitWordsAndKeepInBetween = Regex("""\p{L}+|[^\p{L}]""")

        fun isSingleDigit(strOrNum: Any): Boolean {
            return when (strOrNum) {
                is String -> strOrNum.length <= 1
                is Number -> strOrNum.isInteger && strOrNum.integerValue() in 0..<10
                else -> throw RuntimeException("Item $strOrNum is neither String nor Number")
            }
        }
    }
}
