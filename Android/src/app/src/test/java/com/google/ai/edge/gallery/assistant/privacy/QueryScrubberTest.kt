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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryScrubberTest {
  private fun categories(r: ScrubResult) = r.redactions.map { it.category }.toSet()

  @Test
  fun genericQueryIsUntouched() {
    val r = QueryScrubber().scrub("best hiking trails Mallorca October")
    assertEquals("best hiking trails Mallorca October", r.cleaned)
    assertTrue(r.redactions.isEmpty())
    assertFalse(r.requiresReview)
  }

  @Test
  fun privateTermsAreRemovedCaseInsensitively() {
    val r = QueryScrubber(listOf("Daniel", "Kreuzberg")).scrub("daniel cafes in KREUZBERG open late")
    assertEquals("cafes in open late", r.cleaned)
    assertEquals(2, r.redactions.count { it.category == RedactionCategory.PRIVATE_TERM })
  }

  @Test
  fun privateTermDoesNotMatchInsideOtherWords() {
    val r = QueryScrubber(listOf("Ann")).scrub("annual report template")
    assertEquals("annual report template", r.cleaned)
  }

  @Test
  fun contactDataIsRemoved() {
    val r =
      QueryScrubber()
        .scrub("contact max.mustermann@example.org or +49 170 1234567 about https://x.io/a?b=c")
    assertTrue(categories(r).containsAll(setOf(RedactionCategory.EMAIL, RedactionCategory.PHONE, RedactionCategory.URL)))
    assertFalse(r.cleaned.contains("@"))
    assertFalse(r.cleaned.contains("1234567"))
    assertFalse(r.cleaned.contains("x.io"))
  }

  @Test
  fun germanPhoneNumberIsRemoved() {
    val r = QueryScrubber().scrub("Arzt 030 12345678 Termin")
    assertFalse(r.cleaned.contains("12345678"))
    assertTrue(RedactionCategory.PHONE in categories(r))
  }

  @Test
  fun ibanAndCardAreRemoved() {
    val r = QueryScrubber().scrub("DE89 3704 0044 0532 0130 00 and 4111 1111 1111 1111")
    assertFalse(r.cleaned.any { it.isDigit() })
  }

  @Test
  fun addressesAndPostcodesAreRemoved() {
    val r = QueryScrubber().scrub("bakery near Oranienstraße 25 10999 Berlin")
    assertEquals("bakery near Berlin", r.cleaned)
    assertTrue(RedactionCategory.ADDRESS in categories(r))
    assertTrue(RedactionCategory.POSTCODE in categories(r))
  }

  @Test
  fun datesAreRemovedButYearsStay() {
    val r = QueryScrubber().scrub("events on 15.10.2026 and festivals 2026")
    assertEquals("events on and festivals 2026", r.cleaned)
  }

  @Test
  fun firstPersonWordingIsDropped() {
    val r = QueryScrubber().scrub("what should I eat with my diabetes")
    assertEquals("what should eat with diabetes", r.cleaned)
    assertTrue(RedactionCategory.FIRST_PERSON in categories(r))
  }

  @Test
  fun germanFirstPersonIsDropped() {
    val r = QueryScrubber().scrub("Ich habe Migräne was hilft mir")
    assertEquals("habe Migräne was hilft", r.cleaned)
  }

  @Test
  fun healthTermsAreFlaggedNotRemoved() {
    val r = QueryScrubber().scrub("Ibuprofen Wechselwirkung Alkohol")
    assertEquals("Ibuprofen Wechselwirkung Alkohol", r.cleaned)
    assertTrue(r.requiresReview)
    assertEquals("Ibuprofen", r.healthFlags.first().text)
  }

  @Test
  fun healthStemsMatchWithUmlautsAndCompounds() {
    assertTrue(QueryScrubber().scrub("Blutdruckwerte normal").requiresReview)
    assertTrue(QueryScrubber().scrub("Schlafstörungen Tipps").requiresReview)
    assertTrue(QueryScrubber().scrub("blood pressure normal range").requiresReview)
    assertFalse(QueryScrubber().scrub("Kaffee Röstung Tipps").requiresReview)
  }

  @Test
  fun longQueriesAreCutAtAWordBoundary() {
    val long = (1..60).joinToString(" ") { "word" }
    val r = QueryScrubber(maxLength = 40).scrub(long)
    assertTrue(r.cleaned.length <= 40)
    assertFalse(r.cleaned.endsWith(" "))
    assertTrue(RedactionCategory.LENGTH in categories(r))
  }

  @Test
  fun queryMadeOnlyOfPersonalDataBecomesEmpty() {
    val r = QueryScrubber(listOf("Daniel")).scrub("Daniel daniel@example.com")
    assertTrue(r.isEmpty)
  }

  @Test
  fun newlinesCannotSmuggleExtraText() {
    val r = QueryScrubber().scrub("weather berlin\n\nmy name is Daniel")
    assertFalse(r.cleaned.contains("\n"))
  }

  @Test
  fun memoryOverlapIsDetected() {
    val memories = listOf("Sister Johanna lives in Leipzig and studies medicine.")
    assertTrue(MemoryOverlap.overlaps("Johanna Leipzig medicine exams", memories))
    assertFalse(MemoryOverlap.overlaps("Leipzig weather tomorrow", memories))
    assertFalse(MemoryOverlap.overlaps("best hiking trails", memories))
  }
}
