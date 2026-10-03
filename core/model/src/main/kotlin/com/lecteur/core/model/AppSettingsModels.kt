package com.lecteur.core.model

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class AppearanceSettings(
    /** Dark by default, as the specification wants. */
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** Colours taken from the wallpaper on Android 12+. */
    val dynamicColor: Boolean = true
)

enum class LibraryView { GRID, LIST }

/** Width of a poster in the grid. */
enum class PosterSize(val dp: Int, val label: String) {
    SMALL(100, "Petites"),
    MEDIUM(130, "Moyennes"),
    LARGE(170, "Grandes")
}

/** What a library shelf remembers between launches: its own sort and view, the poster size being shared. */
data class SectionPrefs(
    val sort: LibrarySort = LibrarySort(),
    val view: LibraryView = LibraryView.GRID,
    val posterSize: PosterSize = PosterSize.MEDIUM
)

enum class HomeRow(val title: String) {
    CONTINUE("Reprendre la lecture"),
    NEXT_UP("Prochains épisodes"),
    RECENT("Récemment ajoutés"),
    MOVIES("Films"),
    SERIES("Séries"),
    UNWATCHED("Non vus"),
    FAVORITES("Favoris")
}

data class HomeRowConfig(val row: HomeRow, val visible: Boolean = true)

/** The rows of the home screen in the order and visibility the user chose. */
data class HomeLayout(val rows: List<HomeRowConfig>) {

    val visibleRows: List<HomeRow> get() = rows.filter { it.visible }.map { it.row }

    /** Moves a row up (-1) or down (+1); no-op at the ends. */
    fun move(row: HomeRow, delta: Int): HomeLayout {
        val index = rows.indexOfFirst { it.row == row }
        val target = index + delta
        if (index < 0 || target !in rows.indices) return this
        val moved = rows.toMutableList()
        moved.add(target, moved.removeAt(index))
        return HomeLayout(moved)
    }

    fun setVisible(row: HomeRow, visible: Boolean) =
        HomeLayout(rows.map { if (it.row == row) it.copy(visible = visible) else it })

    companion object {
        val DEFAULT = HomeLayout(HomeRow.entries.map { HomeRowConfig(it) })

        /** Parses "ROW:1,ROW:0": unknown rows are dropped, duplicates ignored, rows added by an update appended visible. */
        fun decode(stored: String?): HomeLayout {
            if (stored.isNullOrBlank()) return DEFAULT
            val seen = LinkedHashMap<HomeRow, Boolean>()
            stored.split(',').forEach { part ->
                val name = part.substringBefore(':').trim()
                val row = HomeRow.entries.firstOrNull { it.name == name } ?: return@forEach
                if (row !in seen) seen[row] = part.substringAfter(':', "1").trim() != "0"
            }
            HomeRow.entries.filter { it !in seen }.forEach { seen[it] = true }
            return HomeLayout(seen.map { HomeRowConfig(it.key, it.value) })
        }

        fun encode(layout: HomeLayout): String = layout.rows.joinToString(",") { "${it.row.name}:${if (it.visible) 1 else 0}" }
    }
}
