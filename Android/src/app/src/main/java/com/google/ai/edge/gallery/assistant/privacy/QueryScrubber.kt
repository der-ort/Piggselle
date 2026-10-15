/*
 * Copyright 2026 Daniel (der-ort)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.assistant.privacy

/** What kind of personal data a redaction removed (or, for [HEALTH], flagged). */
enum class RedactionCategory(val label: String) {
  PRIVATE_TERM("Private term"),
  EMAIL("Email address"),
  URL("Link"),
  PHONE("Phone number"),
  IBAN("Bank account"),
  CARD("Card number"),
  IP("IP address"),
  COORDINATES("Coordinates"),
  DATE("Date"),
  ADDRESS("Street address"),
  POSTCODE("Postcode"),
  NUMBER("Long number"),
  FIRST_PERSON("Personal wording"),
  /** Health terms are not removed (they are often the point of the query), only flagged. */
  HEALTH("Health topic"),
  LENGTH("Shortened"),
}

data class Redaction(val category: RedactionCategory, val text: String)

data class ScrubResult(
  val original: String,
  val cleaned: String,
  val redactions: List<Redaction>,
) {
  val removed: List<Redaction>
    get() = redactions.filter { it.category != RedactionCategory.HEALTH }

  val healthFlags: List<Redaction>
    get() = redactions.filter { it.category == RedactionCategory.HEALTH }

  /** Health topics always need a human look, even in auto mode. */
  val requiresReview: Boolean
    get() = healthFlags.isNotEmpty()

  val isEmpty: Boolean
    get() = cleaned.isBlank()
}

/**
 * Removes personal data from a web search query before it can leave the phone.
 *
 * It is deliberately conservative and pattern-based (no model involved), so its behaviour is
 * predictable and testable. It is a safety net, not a guarantee: the user still sees the final
 * query unless they enabled auto mode, and auto mode still stops for health topics.
 *
 * Android and the JVM disagree on whether `\w` and `\b` match umlauts, so all word boundaries
 * here are spelled out with Unicode letter/number classes.
 */
class QueryScrubber(privateTerms: Collection<String> = emptyList(), private val maxLength: Int = 120) {

  private val terms: List<String> =
    privateTerms.map { it.trim() }.filter { it.length >= 2 }.distinctBy { it.lowercase() }
      .sortedByDescending { it.length }

  fun scrub(query: String): ScrubResult {
    val redactions = mutableListOf<Redaction>()
    var text = query.replace(Regex("[\\r\\n\\t]+"), " ")

    fun strip(category: RedactionCategory, regex: Regex) {
      text =
        regex.replace(text) { m ->
          redactions.add(Redaction(category, m.value.trim()))
          " "
        }
    }

    // Links and emails go first as whole units, so a private term inside them can't leave half an
    // address behind.
    strip(RedactionCategory.URL, URL)
    strip(RedactionCategory.EMAIL, EMAIL)
    for (term in terms) {
      strip(RedactionCategory.PRIVATE_TERM, wordRegex(Regex.escape(term)))
    }
    strip(RedactionCategory.IBAN, IBAN)
    strip(RedactionCategory.CARD, CARD)
    strip(RedactionCategory.IP, IPV4)
    strip(RedactionCategory.COORDINATES, COORDINATES)
    strip(RedactionCategory.PHONE, PHONE)
    strip(RedactionCategory.DATE, DATE)
    strip(RedactionCategory.ADDRESS, STREET_DE)
    strip(RedactionCategory.ADDRESS, STREET_EN)
    strip(RedactionCategory.POSTCODE, POSTCODE)
    strip(RedactionCategory.NUMBER, LONG_NUMBER)

    // First-person wording turns a generic question into a statement about the user
    // ("my insulin dose" vs "insulin dose"). Drop those words.
    val words = text.split(' ').filter { it.isNotBlank() }
    val kept = mutableListOf<String>()
    for (w in words) {
      val core = w.trim { !it.isLetterOrDigit() && it != '\'' }.lowercase()
      if (core in FIRST_PERSON) {
        redactions.add(Redaction(RedactionCategory.FIRST_PERSON, w))
      } else {
        kept.add(w)
      }
    }
    text = kept.joinToString(" ")

    // Flag (not remove) health topics.
    for (m in HEALTH.findAll(text)) {
      redactions.add(Redaction(RedactionCategory.HEALTH, m.value))
    }

    text = text.replace(Regex(" +"), " ").trim().trim(',', ';', ':', '-').trim()
    if (text.length > maxLength) {
      val cut = text.substring(0, maxLength).substringBeforeLast(' ', text.substring(0, maxLength))
      redactions.add(Redaction(RedactionCategory.LENGTH, text.substring(cut.length).trim()))
      text = cut.trim()
    }
    return ScrubResult(original = query, cleaned = text, redactions = redactions)
  }

  companion object {
    private const val L = "\\p{L}\\p{N}"

    /** Matches [inner] only as a whole word (no letters or digits directly around it). */
    fun wordRegex(inner: String): Regex =
      Regex("(?<![$L])(?:$inner)(?![$L])", RegexOption.IGNORE_CASE)

    private val URL = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val IBAN = Regex("(?i)(?<![$L])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?(?![$L])")
    private val CARD = Regex("(?<![$L])(?:\\d[ -]?){13,19}(?![$L])")
    private val IPV4 = Regex("(?<![$L.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![$L.])")
    private val COORDINATES = Regex("-?\\d{1,3}\\.\\d{3,},\\s*-?\\d{1,3}\\.\\d{3,}")
    private val PHONE =
      Regex("(?<![$L])(?:\\+|00)\\d[\\d /().-]{6,}\\d(?![$L])|(?<![$L])0\\d{2,5}[ /-]?\\d{4,9}(?![$L])")
    private val DATE =
      Regex(
        "(?<![$L])(?:\\d{1,2}[./]\\d{1,2}[./](?:\\d{4}|\\d{2})|\\d{4}-\\d{2}-\\d{2})(?![$L])"
      )
    private val STREET_DE =
      Regex(
        "(?i)(?<![$L])[\\p{L}-]*(?:straße|strasse|str\\.|weg|platz|allee|gasse|ring|damm|ufer|chaussee)\\s+\\d+[a-z]?(?![$L])"
      )
    private val STREET_EN =
      Regex(
        "(?i)(?<![$L])\\d+\\s+[\\p{L}]+(?:\\s+[\\p{L}]+)?\\s+(?:street|st\\.?|road|rd\\.?|avenue|ave\\.?|lane|ln\\.?|drive|dr\\.?|boulevard|blvd\\.?)(?![$L])"
      )
    private val POSTCODE = Regex("(?<![$L])\\d{5}(?![$L])")

    /** Numbers with 4+ digits, except plausible years (1900-2099), which are usually harmless. */
    private val LONG_NUMBER = Regex("(?<![$L])(?!(?:19|20)\\d{2}(?![$L]))\\d{4,}(?![$L])")

    private val FIRST_PERSON =
      setOf(
        // English
        "i", "i'm", "im", "i've", "i'd", "i'll", "me", "my", "mine", "myself",
        // German
        "ich", "mich", "mir", "mein", "meine", "meiner", "meines", "meinem", "meinen", "meins",
      )

    private val HEALTH_STEMS =
      listOf(
        // conditions and symptoms
        "diagnos", "symptom", "schmerz", "pain", "depress", "angststörung", "anxiety", "panik",
        "panic", "adhs", "adhd", "autis", "diabet", "krebs", "cancer", "tumor", "tumour", "hiv",
        "aids", "chlamyd", "syphil", "herpes", "schwanger", "pregnan", "abtreib", "abortion",
        "fehlgeburt", "miscarr", "migrän", "migraine", "epilep", "asthma", "allerg", "unverträg",
        "intoleran", "infekt", "infection", "entzünd", "inflammat", "insomni", "schlafstör",
        "essstör", "eating disorder", "bulimi", "anorex", "sucht", "addict", "suizid", "suicid",
        "trauma", "ptbs", "ptsd", "bipolar", "schizo", "psychos", "zwangsstör", "ocd",
        "blutdruck", "blood pressure", "blutzucker", "blood sugar", "cholester", "thyro",
        "schilddrüs", "herzinfarkt", "heart attack", "schlaganfall", "stroke", "demenz",
        "dementia", "alzheimer", "parkinson", "arthros", "arthrit", "rheuma", "krankheit",
        "disease", "illness", "erkrank", "befund", "laborwert", "lab result", "blood test",
        "blutbild",
        // care
        "therap", "psychiat", "psycholog", "psychother", "medikament", "medication", "medicine",
        "tablette", "pill", "dosis", "dosage", "dose", "nebenwirk", "side effect", "rezeptpflicht",
        "prescription",
        // common substances
        "ibuprofen", "paracetamol", "acetaminophen", "aspirin", "novalgin", "metamizol",
        "sertralin", "fluoxetin", "citalopram", "escitalopram", "venlafaxin", "bupropion",
        "methylphenidat", "ritalin", "elvanse", "lisdexamfetamin", "insulin", "metformin",
        "levothyrox", "l-thyrox", "antidepress", "benzodiazep", "lorazepam", "diazepam",
        "opioid", "tramadol", "cannabis", "thc", "cbd", "kokain", "cocaine", "mdma", "ketamin",
      )

    /** Whole-word start match on any health stem, e.g. "Migräne", "migraines", "Blutdruckwerte". */
    private val HEALTH: Regex =
      Regex(
        "(?<![$L])(?:" +
          HEALTH_STEMS.joinToString("|") { Regex.escape(it) } +
          ")[$L]*",
        RegexOption.IGNORE_CASE,
      )
  }
}
