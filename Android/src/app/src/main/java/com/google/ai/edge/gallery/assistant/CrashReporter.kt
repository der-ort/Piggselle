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

package com.google.ai.edge.gallery.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.ai.edge.gallery.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Offline crash reporting: an uncaught exception is written to a local file, and the next launch
 * shows it with a Copy button. Nothing is uploaded. The report contains the stack trace, app
 * version and device model only — no chat content.
 */
object CrashReporter {
  private const val FILE = "last_crash.txt"

  private fun file(context: Context) =
    File(context.applicationContext.noBackupFilesDir, FILE)

  fun install(context: Context) {
    val app = context.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      try {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        file(app)
          .writeText(
            "Piggselle ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
              "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
              "(SDK ${Build.VERSION.SDK_INT})\n" +
              "Thread: ${thread.name}\n\n" +
              trace.take(20_000)
          )
      } catch (e: Throwable) {
        Log.e("PGCrashReporter", "Could not write crash report", e)
      }
      previous?.uncaughtException(thread, error)
    }
  }

  /** Shows the last crash, if any, and deletes it. Call from the launcher activity. */
  fun showIfPresent(activity: Activity) {
    val f = file(activity)
    if (!f.exists()) return
    val report = runCatching { f.readText() }.getOrDefault("")
    f.delete()
    if (report.isBlank()) return

    val text =
      TextView(activity).apply {
        setText(report)
        setTextIsSelectable(true)
        textSize = 11f
        setPadding(48, 24, 48, 24)
      }
    AlertDialog.Builder(activity)
      .setTitle("Piggselle crashed last time")
      .setView(ScrollView(activity).apply { addView(text) })
      .setPositiveButton("Copy") { _, _ ->
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Piggselle crash report", report))
        Toast.makeText(activity, "Crash report copied", Toast.LENGTH_SHORT).show()
      }
      .setNegativeButton("Dismiss", null)
      .show()
  }
}
