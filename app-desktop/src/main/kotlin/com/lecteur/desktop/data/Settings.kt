package com.lecteur.desktop.data

import com.lecteur.core.common.cast.protocol.PairingCode
import com.lecteur.core.common.library.LibrarySignature
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibraryView
import com.lecteur.core.model.PosterSize
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.ThemeMode
import com.lecteur.core.player.tracks.SubtitleMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress

/**
 * Where the app keeps its files: %LOCALAPPDATA%\LecteurMedia-data (database, image cache, settings), next to the install
 * folder (%LOCALAPPDATA%\LecteurMedia for a per-user install), never inside it: an update uninstalls the previous
 * version, and the installer deletes its whole folder.
 */
object AppDirs {
    private val DATA_FILES = listOf("library.db", "library.db-wal", "library.db-shm", "settings.json", "images")

    val root: File = run {
        // LECTEUR_DATA_DIR: a separate library (tests, a second profile)
        System.getenv("LECTEUR_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { return@run File(it).apply { mkdirs() } }
        val base = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".lecteurmedia")
        val install = File(base, "LecteurMedia")
        val data = File(base, "LecteurMedia-data").apply { mkdirs() }
        // Earlier versions wrote inside the install folder (in data\, the very first ones next to the program):
        // the library moves out once
        if (!File(data, "library.db").exists()) {
            listOf(File(install, "data"), install).forEach { old ->
                DATA_FILES.map { File(old, it) }.filter { it.exists() && !File(data, it.name).exists() }
                    .forEach { runCatching { it.renameTo(File(data, it.name)) } }
            }
        }
        data
    }
    val database: File get() = File(root, "library.db")
    val images: File get() = File(root, "images").apply { mkdirs() }
    val settings: File get() = File(root, "settings.json")
}

enum class ResumeBehavior(val label: String) { ASK("Demander"), ALWAYS("Toujours reprendre"), NEVER("Toujours depuis le début") }

val SubtitleMode.label: String
    get() = when (this) {
        SubtitleMode.AUTO -> "Automatique (si l'audio n'est pas dans ma langue)"
        SubtitleMode.ALWAYS -> "Toujours"
        SubtitleMode.OFF -> "Seulement les sous-titres forcés"
    }

/** libVLC's relative font size: a smaller number is a bigger text. */
enum class SubtitleSize(val label: String, val vlcRelative: Int) {
    SMALL("Petite", 20), NORMAL("Normale", 16), LARGE("Grande", 12), HUGE("Très grande", 9)
}

enum class SubtitleColor(val label: String, val rgb: Int) {
    WHITE("Blanc", 0xFFFFFF), YELLOW("Jaune", 0xFFFF00), CYAN("Cyan", 0x00FFFF)
}

@Serializable
data class SectionSettings(
    val sortField: SortField = SortField.TITLE,
    val sortOrder: SortOrder = SortOrder.ASC,
    val view: LibraryView = LibraryView.GRID
)

/** A phone or TV paired with the shared library; [secret] checks its signatures (it never travels after pairing). */
@Serializable
data class SharedDevice(
    val id: String,
    val name: String,
    val secret: String,
    val platform: String = "android",
    val pairedAt: Long = 0,
    val lastSeenAt: Long = 0,
    val lastAddress: String? = null
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** TMDB tag of the metadata language. */
    val metadataLanguage: String = "fr-FR",
    /** Overrides the key compiled into the build; blank keeps the build's key. */
    val tmdbApiKey: String = "",
    val audioLanguages: List<String> = listOf("fr", "en"),
    val subtitleLanguages: List<String> = listOf("fr"),
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
    val resumeBehavior: ResumeBehavior = ResumeBehavior.ASK,
    val autoPlayNext: Boolean = true,
    val seekStepSeconds: Int = 10,
    val hardwareDecoding: Boolean = true,
    /** Sends Dolby/DTS untouched to an amplifier over S/PDIF or HDMI. */
    val passthrough: Boolean = false,
    val volume: Int = 100,
    /** Lets the volume go up to 200 % (quiet files). */
    val volumeBoost: Boolean = false,
    /** Dynamic range compression: dialogues and explosions closer in level. */
    val nightMode: Boolean = false,
    /** Seconds of the "next episode" countdown; 0 = no automatic switch before the end. */
    val upNextSeconds: Int = 10,
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val subtitleColor: SubtitleColor = SubtitleColor.WHITE,
    val subtitleBackground: Boolean = false,
    /** Distance from the bottom of the picture, in pixels. */
    val subtitleMargin: Int = 0,
    val posterSize: PosterSize = PosterSize.MEDIUM,
    val movies: SectionSettings = SectionSettings(),
    val series: SectionSettings = SectionSettings(),
    val homeLayout: String = "",
    val scanOnStart: Boolean = true,
    /** Rescan every N hours while the app is open; 0 = never. */
    val periodicScanHours: Int = 0,
    val receiverEnabled: Boolean = true,
    val receiverName: String = defaultDeviceName(),
    val pairingCode: String = PairingCode.generate(),
    /** Codes already accepted by the TVs this PC drives, by TV name. */
    val remotePairings: Map<String, String> = emptyMap(),
    /** Library sharing with the phones and TVs of the house (off until the user turns it on). */
    val shareEnabled: Boolean = false,
    /** Stable identity of this PC as a library server, so devices recognise it after an address change. */
    val serverId: String = LibrarySignature.newId(),
    /** Devices allowed to read the shared library. Removing one revokes it at once. */
    val sharedDevices: List<SharedDevice> = emptyList()
) {
    fun section(section: LibrarySection) = if (section == LibrarySection.MOVIES) movies else series

    fun withSection(section: LibrarySection, value: SectionSettings) =
        if (section == LibrarySection.MOVIES) copy(movies = value) else copy(series = value)
}

fun defaultDeviceName(): String =
    (System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull())
        ?.let { "PC $it" } ?: "PC Windows"

/** Settings as a JSON file, rewritten whole on every change. */
class SettingsStore(private val file: File = AppDirs.settings) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val value: AppSettings get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        _state.update(transform)
        save(_state.value)
    }

    private fun load(): AppSettings {
        val loaded = runCatching { json.decodeFromString(AppSettings.serializer(), file.readText()) }.getOrNull()
        // Saved even when read fine: defaults drawn at random (server id, pairing code) must not change at the next start
        return (loaded ?: AppSettings()).also { save(it) }
    }

    @Synchronized
    private fun save(settings: AppSettings) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(AppSettings.serializer(), settings))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
