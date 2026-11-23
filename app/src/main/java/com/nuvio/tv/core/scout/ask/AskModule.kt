package com.nuvio.tv.core.scout.ask

import com.nuvio.tv.core.catalog.BuiltInCatalogProvider
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class AskModule {
    @Multibinds
    abstract fun tasteSources(): Set<AskTasteSource>

    // NuvioMate: saved Ask rows come with the Ask screen (not ported yet).
    // @Binds
    // @IntoSet
    // abstract fun bindCatalogs(provider: AskCatalogProvider): BuiltInCatalogProvider

    @Binds
    @IntoSet
    abstract fun bindKeys(vault: AskKeyVault): ProfileScopedCredentialStore
}
