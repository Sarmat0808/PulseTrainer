package fi.sarmat.pulsetrainer

import android.app.backup.BackupManager
import android.content.Context
import android.net.Uri
import fi.sarmat.pulsetrainer.core.Gpx
import fi.sarmat.pulsetrainer.core.WorkoutJson
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Your data, safe:
 * 1. Automatic: Android backs up the app's data to your Google account (Google Drive) about once a day
 *    while charging on Wi-Fi, and restores it when you reinstall or move to a new phone.
 * 2. Manual: a single .zip with everything (workouts with every heart-rate second and route,
 *    morning tests, stress, night pulse from the watch, food diary, weight, profile, settings) —
 *    save it anywhere (Google Drive, Files) and restore from it at any time.
 * The watch sends all its data to the phone, so the phone backup also holds the watch's history.
 */
object Backup {
    private const val PREFS = "pt"
    private val prefNames = listOf("pt", "food")

    /** Tell Android that there is something new to back up to Google. */
    fun changed(ctx: Context) {
        try { BackupManager(ctx).dataChanged() } catch (_: Exception) {}
    }

    var lastManual: Long = 0L
        private set

    fun loadInfo(ctx: Context) {
        lastManual = ctx.getSharedPreferences("backup", Context.MODE_PRIVATE).getLong("last", 0L)
    }

    private fun prefsDir(ctx: Context) = File(ctx.applicationInfo.dataDir, "shared_prefs")

    fun export(ctx: Context, to: Uri): Int {
        var n = 0
        ctx.contentResolver.openOutputStream(to)?.use { os ->
            ZipOutputStream(os).use { zip ->
                fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry(); n++ }
                prefNames.forEach { p -> File(prefsDir(ctx), "$p.xml").takeIf { it.exists() }?.let { put("prefs/$p.xml", it.readBytes()) } }
                File(ctx.filesDir, "workouts").listFiles()?.forEach { put("workouts/${it.name}", it.readBytes()) }
                File(ctx.filesDir, "ecg").listFiles()?.forEach { put("ecg/${it.name}", it.readBytes()) }
                put("README.txt", ("PulseTrainer — резервная копия.\nВосстановление: Настройки → Резервная копия → Восстановить.\n" +
                    "workouts/*.json — тренировки (пульс каждую секунду, подходы, маршрут); prefs — профиль, тесты, питание, вес.").toByteArray())
            }
        }
        val now = System.currentTimeMillis()
        ctx.getSharedPreferences("backup", Context.MODE_PRIVATE).edit().putLong("last", now).apply()
        lastManual = now
        return n
    }

    /**
     * Restore from a backup zip. Settings files are replaced as a whole, so the app restarts afterwards
     * (the caller kills the process; Android reopens it fresh). Workouts are merged: nothing you have is lost.
     */
    fun import(ctx: Context, from: Uri): Int {
        var n = 0
        val tmpPrefs = HashMap<String, ByteArray>()
        ctx.contentResolver.openInputStream(from)?.use { ins ->
            ZipInputStream(ins).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    val bytes = zip.readBytes()
                    when {
                        name.startsWith("workouts/") && name.endsWith(".json") && !name.contains("..") -> {
                            // Validate before writing.
                            if (runCatching { WorkoutJson.fromJson(String(bytes)) }.isSuccess) {
                                File(File(ctx.filesDir, "workouts").apply { mkdirs() }, name.removePrefix("workouts/")).writeBytes(bytes); n++
                            }
                        }
                        name.startsWith("ecg/") && name.endsWith(".json") && !name.contains("..") -> {
                            File(File(ctx.filesDir, "ecg").apply { mkdirs() }, name.removePrefix("ecg/")).writeBytes(bytes); n++
                        }
                        name.startsWith("prefs/") && name.removePrefix("prefs/").removeSuffix(".xml") in prefNames -> tmpPrefs[name.removePrefix("prefs/")] = bytes
                    }
                    e = zip.nextEntry
                }
            }
        }
        // Commit settings last, straight to disk.
        tmpPrefs.forEach { (f, b) -> File(prefsDir(ctx), f).writeBytes(b); n++ }
        return n
    }

    /** All workouts as TCX files in one zip (for bulk upload to Strava / Garmin Connect). */
    fun exportAllTcx(ctx: Context, to: Uri): Int {
        var n = 0
        ctx.contentResolver.openOutputStream(to)?.use { os ->
            ZipOutputStream(os).use { zip ->
                PhoneStore.workouts.value.forEach { w ->
                    zip.putNextEntry(ZipEntry("pulsetrainer_${w.start}.tcx")); zip.write(Gpx.tcx(w).toByteArray()); zip.closeEntry(); n++
                }
            }
        }
        return n
    }

    @Suppress("unused") private val keep = PREFS
}
