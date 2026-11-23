package com.nuvio.tv.ui.scout

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.core.catalog.BuiltInCatalogs
import com.nuvio.tv.core.scout.ScoutCatalogProvider
import com.nuvio.tv.core.scout.ScoutMixCatalogProvider
import com.nuvio.tv.core.scout.ask.AskCatalogProvider
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberRawSvgPainter

/** A catalog row Scout makes: its picks and saved Ask answers. */
fun isScoutCatalog(addonId: String?): Boolean =
    addonId == ScoutCatalogProvider.ADDON_ID || addonId == AskCatalogProvider.ADDON_ID

/** A collection row Scout makes: Mixes and its tiles. Takes the collection's id or a Home key ending with it. */
fun isScoutCollection(idOrKey: String?): Boolean =
    idOrKey != null && (idOrKey.endsWith(ScoutMixCatalogProvider.COLLECTION_ID) || idOrKey.endsWith(ScoutCatalogProvider.TILES_COLLECTION_ID))

/** The app's own rows mix films and shows, so their title carries no type. */
fun skipsCatalogTypeSuffix(addonId: String?): Boolean =
    addonId != null && (addonId.startsWith(BuiltInCatalogs.ADDON_ID_PREFIX) || addonId == AskCatalogProvider.ADDON_ID)

/** Scout's mark before the title of a row it makes, so it stands apart from add-on rows. */
@Composable
fun ScoutRowMark(size: Dp = 22.dp) {
    Icon(
        painter = rememberRawSvgPainter(R.raw.sidebar_scout, size),
        contentDescription = "Scout",
        tint = NuvioTheme.colors.Secondary,
        modifier = Modifier.size(size)
    )
    Spacer(Modifier.width(10.dp))
}
