package com.nuvio.tv.ui.screens.scout

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.player.ScoutMixReturn
import com.nuvio.tv.core.player.ScoutMixSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Lets the navigation graph ask whether the player is playing for a mix. */
@HiltViewModel
class ScoutMixNavViewModel @Inject constructor(private val session: ScoutMixSession) : ViewModel() {
    fun playbackEnded(contentId: String?): Boolean = session.returnFromPlayer(contentId, ScoutMixReturn.ENDED)

    fun nextChosen(contentId: String?): Boolean = session.returnFromPlayer(contentId, ScoutMixReturn.NEXT)

    fun backPressed(contentId: String?, watched: Boolean): Boolean =
        session.returnFromPlayer(contentId, if (watched) ScoutMixReturn.WATCHED else ScoutMixReturn.LEFT)
}

/** The same answers for the navigation graph, which sits outside any Hilt view-model owner in NuvioMate. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ScoutNavigationEntryPoint {
    fun mixSession(): ScoutMixSession
    fun scoutEngine(): com.nuvio.tv.core.scout.ScoutEngine
}

class ScoutNavigation(private val session: ScoutMixSession, private val engine: com.nuvio.tv.core.scout.ScoutEngine) {
    fun playbackEnded(contentId: String?): Boolean = session.returnFromPlayer(contentId, ScoutMixReturn.ENDED)

    fun backPressed(contentId: String?, watched: Boolean): Boolean =
        session.returnFromPlayer(contentId, if (watched) ScoutMixReturn.WATCHED else ScoutMixReturn.LEFT)

    fun streamBack(contentId: String?): Boolean = session.leaveSources(contentId)

    fun surprisePick(): com.nuvio.tv.domain.model.ScoutPick? = engine.pool.value?.picked?.take(60)?.randomOrNull()

    companion object {
        fun from(context: android.content.Context): ScoutNavigation {
            val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, ScoutNavigationEntryPoint::class.java)
            return ScoutNavigation(entry.mixSession(), entry.scoutEngine())
        }
    }
}
