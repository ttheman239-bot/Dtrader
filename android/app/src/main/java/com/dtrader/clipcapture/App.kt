package com.dtrader.clipcapture

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes any uncaught exception to crash.txt before the process dies, so the
 * next launch can show the user exactly what failed.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                crashFile(this).writeText(
                    "เวลา: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n" +
                        "เครื่อง: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n" +
                        "แอป: ${packageManager.getPackageInfo(packageName, 0).versionName}\n" +
                        "thread: ${thread.name}\n\n$sw",
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "crash.txt")
    }
}
