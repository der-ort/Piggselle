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

/**
 * Makes markdown safe to render offline: images become plain links (so the renderer never fetches
 * them) and raw HTML image/media tags are removed.
 */
object MarkdownSanitizer {
  private val htmlMedia =
    Regex("<\\s*(img|image|picture|source|video|audio|iframe|object|embed|link)\\b[^>]*>", RegexOption.IGNORE_CASE)

  fun sanitize(markdown: String): String {
    if (!markdown.contains("![") && !markdown.contains('<')) return markdown
    // "![alt](url)" and "![alt][ref]" both start with "![": dropping the "!" turns them into links.
    return markdown.replace("![", "[").replace(htmlMedia, "")
  }
}
