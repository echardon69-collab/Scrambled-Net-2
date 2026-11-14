package net.chardons.netscramble2

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour depuis les publications GitHub Releases du dépôt
 * (echardon69-collab/Scrambled-Net-2), décisions d'eric du 12 nov. 2026 :
 *
 * - vérification à CHAQUE lancement, sur la page titre ;
 * - la vérification ne retient jamais le jeu plus de 5 secondes : une
 *   réponse plus tardive n'est pas exploitée (le prochain lancement
 *   revérifiera) ;
 * - quand une version plus récente existe, le joueur choisit sur la
 *   page titre : « Mettre à jour » ou « Jouer » ;
 * - « Mettre à jour » télécharge l'APK de la publication puis lance
 *   l'installation SANS confirmation quand Android l'accepte (mise à
 *   jour de soi-même, même clé de signature) ; sinon Android montre
 *   son propre écran de confirmation — et la première fois, le
 *   joueur doit autoriser une seule fois cette source à installer
 *   des applications (écran système pointé par l'app).
 *
 * Le Play Store est traité par les saveurs de distribution du
 * 14 nov. 2026 : rien ici ne lui est dédié. L'APK « play » retire
 * REQUEST_INSTALL_PACKAGES du manifeste fusionné et coupe cette
 * vérification à la construction (Distribution.UPDATER_ENABLED
 * faux, TitleActivity) : Play interdit l'auto-mise à jour. L'APK
 * « github » garde tout ce fichier tel quel.
 */
object Updater {

    /** Dernière publication du dépôt (API publique GitHub). */
    private const val LATEST_URL =
        "https://api.github.com/repos/echardon69-collab/Scrambled-Net-2/releases/latest"

    /** GitHub exige un User-Agent identifiable sur son API publique. */
    private const val USER_AGENT = "ScrambledNet2-updater"

    /** Nom de l'APK téléchargé, dans le cache privé de l'app. */
    const val UPDATE_APK_NAME = "update.apk"

    /** Nom de l'écriture d'installation dans la session PackageInstaller. */
    private const val INSTALL_NAME = "update.apk"

    /** Action du résultat d'installation (diffusion interne à l'app). */
    const val ACTION_INSTALL_RESULT =
        "net.chardons.netscramble2.INSTALL_RESULT"

    /** Une publication : étiquette de version et APK à télécharger. */
    data class Release(val version: String, val apkUrl: String)

    /**
     * true si [remote] est strictement plus récente que [current].
     * Les versions sont des nombres séparés par des points (le
     * préfixe « v » des étiquettes git est toléré), comparés
     * numériquement champ par champ, les champs manquants valant 0 :
     * 0.10.0 est bien plus récente que 0.9.9. Toute version
     * mal formée (ex. « 0.1.0-beta ») est ignorée : false.
     */
    fun isNewer(remote: String, current: String): Boolean {
        val r = versionParts(remote) ?: return false
        val c = versionParts(current) ?: return false
        val size = maxOf(r.size, c.size)
        for (i in 0 until size) {
            val rv = r.getOrNull(i) ?: 0
            val cv = c.getOrNull(i) ?: 0
            if (rv != cv) return rv > cv
        }
        return false
    }

    /** Champs numériques de la version, ou null si mal formée. */
    private fun versionParts(version: String): List<Int>? {
        val clean = version.trim()
            .removePrefix("v")
            .removePrefix("V")
        if (clean.isEmpty()) return null
        val parts = clean.split(".")
        if (parts.any { part -> part.isEmpty() || part.any { !it.isDigit() } }) {
            return null
        }
        return parts.map { it.toInt() }
    }

    /**
     * Dernière publication GitHub, ou null si indisponible (pas de
     * publication, pas de réseau, réponse inattendue...). Ne lève
     * jamais : l'absence de mise à jour est une issue normale.
     */
    suspend fun checkLatest(): Release? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection =
                (URL(LATEST_URL).openConnection() as HttpURLConnection)
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }
            val body = connection.inputStream.bufferedReader().use {
                it.readText()
            }
            val release = org.json.JSONObject(body)
            val version = release.optString("tag_name", "")
            val assets = release.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name", "").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (version.isEmpty() || apkUrl.isNullOrEmpty()) {
                return@withContext null
            }
            Release(version, apkUrl)
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Télécharge [urlString] dans [dest] (cache privé de l'app : aucune
     * permission de stockage). [onProgress] reçoit le pourcentage
     * (0 à 100), ou -1 si la taille totale est inconnue (barre
     * indéterminée). Appelé depuis le thread réseau.
     */
    suspend fun download(
        urlString: String,
        dest: File,
        onProgress: (Int) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            if (dest.exists()) dest.delete()
            dest.parentFile?.mkdirs()
            connection =
                (URL(urlString).openConnection() as HttpURLConnection)
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            onProgress(((read * 100) / total).toInt())
                        } else {
                            onProgress(-1)
                        }
                    }
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * L'app a-t-elle le droit d'installer des paquets ? Sous Android
     * 8.0+ c'est un droit par application (« installer des
     * applications inconnues ») ; avant, c'est un réglage global que
     * l'app ne peut pas consulter : on tente l'installation et le
     * système dit non le cas échéant.
     */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /**
     * Ouvre l'écran système où le joueur autorise, UNE fois, cette
     * source à installer des applications. Retour de l'écran : le
     * joueur appuie à nouveau sur « Mettre à jour ».
     */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            )
        )
    }

    /**
     * Lance l'installation de [apk] (PackageInstaller). Le résultat
     * arrive à la diffusion [ACTION_INSTALL_RESULT] : succès (l'app
     * est remplacée puis redémarrée par le système), confirmation
     * exigée par le système (dialog à montrer via l'intent fourni),
     * ou échec (message). Signature requise : la mise à jour doit
     * être signée avec la MÊME clé que la version installée.
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        // Android 12+ : demander l'installation sans confirmation
        // quand le système l'accepte (mise à jour de soi-même). Sinon,
        // le système montre son écran de confirmation — et cette
        // constante n'existe qu'à partir d'Android 12.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(
                PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
            )
        }
        val session = installer.openSession(installer.createSession(params))
        session.use { session ->
            session.openWrite(INSTALL_NAME, 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val broadcast = Intent(ACTION_INSTALL_RESULT)
                .setPackage(context.packageName)
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            val pending = PendingIntent.getBroadcast(context, 0, broadcast, flags)
            session.commit(pending.intentSender)
        }
    }

    /**
     * Version installée, lue dans le PackageManager (source de vérité
     * unique : versionName de app/build.gradle.kts).
     */
    fun currentVersion(context: Context): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.versionName ?: "?"
    }

    private const val TIMEOUT_MS = 8000
}
