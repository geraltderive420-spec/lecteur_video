package com.lecteur.feature.scanner

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.data.scan.ScanPhase
import com.lecteur.core.data.scan.ScanProgress
import com.lecteur.feature.scanner.work.JobText
import org.junit.Test

class JobTextTest {

    private fun progress(phase: ScanPhase, processed: Int = 0, total: Int = 0, file: String? = null) =
        ScanProgress(1, "Films", phase, processed, total, file)

    @Test
    fun theDetailFollowsThePhaseOfTheScan() {
        assertThat(JobText.scanDetail(null)).isEqualTo("Préparation…")
        assertThat(JobText.scanDetail(progress(ScanPhase.LISTING))).contains("Films")
        assertThat(JobText.scanDetail(progress(ScanPhase.ANALYZING, 12, 340, "Dune.mkv"))).isEqualTo("12 / 340 fichiers · Dune.mkv")
        assertThat(JobText.scanDetail(progress(ScanPhase.ANALYZING, 0, 0))).isEqualTo("Aucun nouveau fichier")
        assertThat(JobText.scanDetail(progress(ScanPhase.FINISHING, 340, 340))).isEqualTo("Finalisation…")
    }

    @Test
    fun theTitleNamesTheFolderBeingAnalysed() {
        assertThat(JobText.scanTitle(null)).isEqualTo("Analyse de la bibliothèque")
        assertThat(JobText.scanTitle(progress(ScanPhase.ANALYZING, 1, 2))).isEqualTo("Analyse de « Films »")
    }

    @Test
    fun theProgressBarIsDeterminateOnlyWhileFilesAreBeingAnalysed() {
        assertThat(JobText.fraction(progress(ScanPhase.ANALYZING, 50, 200))).isEqualTo(0.25f)
        assertThat(JobText.fraction(progress(ScanPhase.LISTING))).isNull()
        assertThat(JobText.fraction(progress(ScanPhase.ANALYZING, 0, 0))).isNull()
        assertThat(JobText.fraction(null)).isNull()
    }

    @Test
    fun identificationProgressIsPluralised() {
        assertThat(JobText.identifyDetail(0)).isEqualTo("Recherche des métadonnées…")
        assertThat(JobText.identifyDetail(1)).isEqualTo("Métadonnées trouvées pour 1 titre")
        assertThat(JobText.identifyDetail(12)).isEqualTo("Métadonnées trouvées pour 12 titres")
    }

    @Test
    fun theSummaryListsOnlyWhatHappened() {
        assertThat(JobText.summaryLine(0, 0, 0, 0, 0)).isEqualTo("Bibliothèque à jour")
        assertThat(JobText.summaryLine(3, 0, 1, 2, 0)).isEqualTo("3 ajoutés · 1 déplacé · 2 indisponibles")
        assertThat(JobText.summaryLine(1, 2, 0, 0, 4)).isEqualTo("1 ajouté · 2 mis à jour · 4 en erreur")
    }
}
