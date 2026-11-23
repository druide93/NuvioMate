package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

// Ported from Nuvio Arc (ysosrs123/NuvioTV-Fork, GPL-3.0): rows the app makes itself (Scout).

private const val BUILT_IN_ROWS_SETTLE_MS = 500L

/** Reads the app's own rows again when their titles change, while at least one of them is switched on. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun HomeViewModel.observeBuiltInRowChangesPipeline() {
    viewModelScope.launch {
        combine(builtInCatalogs.addons, layoutPreferenceDataStore.disabledHomeCatalogKeys) { addons, _ -> addons }
            .debounce(BUILT_IN_ROWS_SETTLE_MS)
            .map { addons -> addons.filter { addon -> addon.enabledHomeCatalogs(this@observeBuiltInRowChangesPipeline).isNotEmpty() } }
            .distinctUntilChanged()
            .flatMapLatest { addons ->
                addons.map { addon -> builtInCatalogs.rowChanges(addon.id).map { addon } }.merge()
            }
            .collect { addon ->
                addon.enabledHomeCatalogs(this@observeBuiltInRowChangesPipeline).forEach { catalog ->
                    loadCatalogPipeline(addon, catalog, catalogLoadGeneration, isRefresh = true, forceReplace = true)
                }
            }
    }
}

private fun Addon.enabledHomeCatalogs(viewModel: HomeViewModel): List<CatalogDescriptor> = catalogs.filter { catalog ->
    catalog.shouldShowOnHome() && !viewModel.isCatalogDisabled(
        addonBaseUrl = baseUrl,
        addonId = id,
        type = catalog.apiType,
        catalogId = catalog.id,
        catalogName = catalog.name
    )
}
