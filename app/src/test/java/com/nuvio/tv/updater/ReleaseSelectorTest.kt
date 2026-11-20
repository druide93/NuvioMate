package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseSelectorTest {
    @Test
    fun `stable channel excludes only GitHub pre-releases`() {
        val releases = listOf(
            release("1.2.0-beta.2.mate.3", prerelease = true),
            release("1.2.0-beta.2.mate.2"),
            release("1.2.0-beta.2.mate.1")
        )

        val selected = ReleaseSelector.eligibleReleases(releases, UpdateChannel.STABLE)

        assertEquals(listOf("1.2.0-beta.2.mate.2", "1.2.0-beta.2.mate.1"), selected.map { it.tagName })
    }

    @Test
    fun `beta channel takes stable and beta releases, newest first`() {
        val releases = listOf(
            release("1.2.0-beta.2.mate.2"),
            release("1.2.0-beta.2.mate.3", prerelease = true),
            release("1.2.0-beta.2.mate.1")
        )

        val selected = ReleaseSelector.eligibleReleases(releases, UpdateChannel.BETA)

        assertEquals(
            listOf("1.2.0-beta.2.mate.3", "1.2.0-beta.2.mate.2", "1.2.0-beta.2.mate.1"),
            selected.map { it.tagName }
        )
    }

    @Test
    fun `beta in the version or title does not make a beta`() {
        val releases = listOf(
            release(tag = "1.2.0-beta.3.mate.1", name = "NuvioMate 1.2.0-beta.3.mate.1 (beta)"),
            release(tag = "1.2.0-beta.2.mate.4")
        )

        val selected = ReleaseSelector.eligibleReleases(releases, UpdateChannel.STABLE)

        assertEquals(listOf("1.2.0-beta.3.mate.1", "1.2.0-beta.2.mate.4"), selected.map { it.tagName })
    }

    @Test
    fun `new installs start on stable, whatever the version`() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultForVersion("1.2.0-beta.2.mate.1"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultForVersion("1.2.0-mate.1"))
    }

    @Test
    fun `drafts and invalid tags are excluded`() {
        val releases = listOf(
            release("1.2.0", draft = true),
            release("nightly"),
            release("1.1.0")
        )

        val selected = ReleaseSelector.eligibleReleases(releases, UpdateChannel.BETA)

        assertEquals(listOf("1.1.0"), selected.map { it.tagName })
    }

    private fun release(
        tag: String,
        name: String = tag,
        draft: Boolean = false,
        prerelease: Boolean = false
    ) = GitHubReleaseDto(
        tagName = tag,
        name = name,
        draft = draft,
        prerelease = prerelease
    )
}
