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

package com.google.ai.edge.gallery.assistant.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
  private fun chunk(id: String, path: String, text: String) = KbChunk(id, id, path, text)

  private val corpus =
    listOf(
      chunk("1", "health/sleep.md", "Slept badly after coffee in the afternoon. Magnesium in the evening helps."),
      chunk("2", "work/smaug.md", "SMAUG roadmap: IFC viewer, point cloud streaming, project admin."),
      chunk("3", "trips/mallorca.md", "Wanderung zum Kloster Lluc, Etappe drei, GPX Track im Ordner."),
      chunk("4", "recipes/bread.md", "Sourdough bread needs a long cold proof overnight."),
    )

  @Test
  fun bm25FindsTheRightNote() {
    val idx = Bm25Index(corpus)
    assertEquals("1", idx.search("why do I sleep badly after coffee").first().chunk.id)
    assertEquals("2", idx.search("point cloud viewer").first().chunk.id)
    assertEquals("3", idx.search("Kloster Wanderungen").first().chunk.id)
  }

  @Test
  fun fileNameIsSearchable() {
    assertEquals("4", Bm25Index(corpus).search("bread").first().chunk.id)
  }

  @Test
  fun unrelatedQueryReturnsNothing() {
    assertTrue(Bm25Index(corpus).search("quantum chromodynamics").isEmpty())
  }

  @Test
  fun stopwordOnlyQueryReturnsNothing() {
    assertTrue(Bm25Index(corpus).search("was ist das und wie").isEmpty())
  }

  @Test
  fun chunkerRespectsSizeAndOverlaps() {
    val para = (1..40).joinToString(" ") { "word$it" } // ~280 chars
    val text = (1..10).joinToString("\n\n") { "$para section$it." }
    val chunks = TextChunker.chunk(text, targetChars = 700, overlapChars = 100)
    assertTrue(chunks.size >= 4)
    assertTrue(chunks.all { it.length <= 900 })
    // Overlap: the start of chunk 2 repeats text from the end of chunk 1.
    val firstWordOfSecond = chunks[1].split(' ').first()
    assertTrue(chunks[0].contains(firstWordOfSecond))
  }

  @Test
  fun chunkerSplitsOneHugeParagraph() {
    val huge = (1..600).joinToString(" ") { "token$it." }
    val chunks = TextChunker.chunk(huge, targetChars = 500, overlapChars = 50)
    assertTrue(chunks.size > 5)
    assertTrue(chunks.all { it.length <= 650 })
  }

  @Test
  fun cleanStripsFrontMatterAndHtml() {
    assertEquals("Body text", TextChunker.clean("---\ntags: [a]\n---\nBody text", "note.md"))
    assertEquals("Hello world", TextChunker.clean("<p>Hello</p> <b>world</b>", "page.html").replace(Regex("\\s+"), " ").trim())
  }

  @Test
  fun cosineAndRrf() {
    assertEquals(1.0, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(2f, 0f)), 1e-9)
    assertEquals(0.0, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 3f)), 1e-9)
    val fused = VectorMath.rrf(listOf(listOf("a", "b"), listOf("b", "c")))
    assertTrue(fused.getValue("b") > fused.getValue("a"))
  }
}
