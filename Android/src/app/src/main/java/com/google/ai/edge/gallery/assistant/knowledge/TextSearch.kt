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

import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/** A passage of a document in the knowledge base. */
@Serializable
data class KbChunk(
  val id: String,
  /** Stable document key (SAF document id). */
  val docKey: String,
  /** Path relative to the knowledge folder, e.g. "health/sleep.md". */
  val docPath: String,
  val text: String,
)

data class KbHit(val chunk: KbChunk, val score: Double)

/** Splits documents into overlapping passages that fit comfortably into a small model's context. */
object TextChunker {
  private val HTML_TAG = Regex("<[^>]+>")
  private val FRONT_MATTER = Regex("\\A---\\n.*?\\n---\\n", RegexOption.DOT_MATCHES_ALL)
  private val BLANK_LINES = Regex("\\n{3,}")

  fun clean(raw: String, fileName: String): String {
    var s = raw.replace("\r\n", "\n").replace('\r', '\n')
    if (fileName.endsWith(".html", true) || fileName.endsWith(".htm", true)) {
      s = s.replace(HTML_TAG, " ")
    }
    s = s.replace(FRONT_MATTER, "")
    return s.replace(BLANK_LINES, "\n\n").trim()
  }

  /**
   * Paragraph-aware chunking: paragraphs are packed until [targetChars]; a chunk keeps the last
   * [overlapChars] of the previous one so facts on a boundary are not lost. Very long paragraphs
   * are split on sentence or word boundaries.
   */
  fun chunk(text: String, targetChars: Int = 900, overlapChars: Int = 150): List<String> {
    if (text.isBlank()) return emptyList()
    val pieces = mutableListOf<String>()
    for (para in text.split(Regex("\\n\\s*\\n"))) {
      val p = para.trim()
      if (p.isEmpty()) continue
      if (p.length <= targetChars) pieces.add(p) else pieces.addAll(splitLong(p, targetChars))
    }
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (piece in pieces) {
      if (current.isNotEmpty() && current.length + piece.length + 2 > targetChars) {
        chunks.add(current.toString().trim())
        val tail = current.takeLast(overlapChars).toString()
        current.clear()
        // Start the overlap at a word boundary.
        current.append(tail.substringAfter(' ', tail)).append("\n\n")
      }
      current.append(piece).append("\n\n")
    }
    if (current.isNotBlank()) chunks.add(current.toString().trim())
    return chunks
  }

  private fun splitLong(p: String, max: Int): List<String> {
    val out = mutableListOf<String>()
    var rest = p
    while (rest.length > max) {
      val window = rest.substring(0, max)
      val cut =
        maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "), window.lastIndexOf('\n'))
          .takeIf { it > max / 3 }
          ?.plus(1)
          ?: window.lastIndexOf(' ').takeIf { it > max / 3 }
          ?: max
      out.add(rest.substring(0, cut).trim())
      rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) out.add(rest)
    return out
  }
}

object Tokenizer {
  private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
  private val STOP =
    setOf(
      // English
      "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was",
      "one", "our", "out", "has", "have", "with", "this", "that", "from", "they", "will", "would",
      "there", "their", "what", "about", "which", "when", "make", "like", "into", "than", "then",
      "them", "these", "some", "could", "other", "how", "its", "also", "just", "your", "does",
      "did", "is", "it", "in", "on", "of", "to", "a", "an", "or", "as", "at", "be", "by", "my",
      "me", "do", "so", "if", "we", "he", "she", "i",
      // German
      "der", "die", "das", "und", "ist", "ich", "nicht", "ein", "eine", "einen", "einem", "einer",
      "zu", "den", "dem", "des", "mit", "sich", "auf", "für", "von", "im", "es", "an", "als",
      "auch", "wie", "aber", "oder", "wenn", "dass", "noch", "nach", "bei", "aus", "um", "am",
      "war", "hat", "haben", "wird", "werden", "sind", "mein", "meine", "mir", "mich", "du", "er",
      "sie", "wir", "ihr", "was", "wo", "wer", "nur", "so", "schon", "dann", "da", "zum", "zur",
    )

  fun tokens(text: String): List<String> =
    text.lowercase().split(SPLIT).filter { it.length >= 2 && it !in STOP }.map(::stem)

  /** Very light suffix stripping so "meetings"/"meeting" and "Termine"/"Termin" match. */
  private fun stem(t: String): String {
    if (t.length <= 4 || t.any { it.isDigit() }) return t
    for (suffix in listOf("ungen", "ings", "ing", "en", "es", "er", "ed", "s", "e", "n")) {
      if (t.endsWith(suffix) && t.length - suffix.length >= 4) return t.dropLast(suffix.length)
    }
    return t
  }
}

/** Okapi BM25 over chunks. Small and fast enough to rebuild in memory on every app start. */
class Bm25Index(private val chunks: List<KbChunk>, private val k1: Double = 1.4, private val b: Double = 0.75) {
  private val docTokens: List<Map<String, Int>>
  private val docLen: IntArray
  private val avgLen: Double
  private val df = HashMap<String, Int>()

  init {
    docTokens =
      chunks.map { c ->
        // Include the file path so searching for a note's name finds it.
        val counts = HashMap<String, Int>()
        for (t in Tokenizer.tokens(c.docPath + " " + c.text)) counts[t] = (counts[t] ?: 0) + 1
        counts
      }
    docLen = IntArray(chunks.size) { i -> docTokens[i].values.sum() }
    avgLen = if (chunks.isEmpty()) 1.0 else docLen.average().coerceAtLeast(1.0)
    for (m in docTokens) for (t in m.keys) df[t] = (df[t] ?: 0) + 1
  }

  val size: Int
    get() = chunks.size

  fun search(query: String, limit: Int = 8): List<KbHit> {
    val q = Tokenizer.tokens(query).distinct()
    if (q.isEmpty() || chunks.isEmpty()) return emptyList()
    val n = chunks.size.toDouble()
    val scores = DoubleArray(chunks.size)
    for (term in q) {
      val dfT = df[term] ?: continue
      val idf = ln(1 + (n - dfT + 0.5) / (dfT + 0.5))
      for (i in chunks.indices) {
        val tf = docTokens[i][term] ?: continue
        val norm = tf * (k1 + 1) / (tf + k1 * (1 - b + b * docLen[i] / avgLen))
        scores[i] += idf * norm
      }
    }
    return scores.indices
      .filter { scores[it] > 0 }
      .sortedByDescending { scores[it] }
      .take(limit)
      .map { KbHit(chunks[it], scores[it]) }
  }
}

object VectorMath {
  fun cosine(a: FloatArray, b: FloatArray): Double {
    if (a.size != b.size || a.isEmpty()) return 0.0
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
      dot += a[i] * b[i]
      na += a[i] * a[i]
      nb += b[i] * b[i]
    }
    val d = sqrt(na) * sqrt(nb)
    return if (d == 0.0) 0.0 else dot / d
  }

  /** Reciprocal rank fusion of several ranked lists of chunk ids. */
  fun rrf(rankings: List<List<String>>, k: Int = 60): Map<String, Double> {
    val out = HashMap<String, Double>()
    for (ranking in rankings) {
      ranking.forEachIndexed { rank, id -> out[id] = (out[id] ?: 0.0) + 1.0 / (k + rank + 1) }
    }
    return out
  }
}
