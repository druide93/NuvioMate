package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto

internal object ReleaseSelector {
    // NuvioMate: a beta is a GitHub release marked "pre-release", and nothing else. Every NuvioMate
    // version has a suffix ("1.2.0-beta.2.nuviomate.1.3": it builds on a Nuvio beta), so neither the version
    // nor the release name can tell a beta from a stable release.
    fun eligibleReleases(
        releases: List<GitHubReleaseDto>,
        channel: UpdateChannel
    ): List<GitHubReleaseDto> = releases
        .asSequence()
        .filterNot(GitHubReleaseDto::draft)
        .mapNotNull { release ->
            val version = releaseVersion(release) ?: return@mapNotNull null
            ReleaseCandidate(
                release = release,
                version = version,
                prerelease = release.prerelease
            )
        }
        .filter { candidate -> channel == UpdateChannel.BETA || !candidate.prerelease }
        .sortedByDescending(ReleaseCandidate::version)
        .map(ReleaseCandidate::release)
        .toList()

    private fun releaseVersion(release: GitHubReleaseDto): SemanticVersion? =
        VersionUtils.parse(release.tagName) ?: VersionUtils.parse(release.name)

    private data class ReleaseCandidate(
        val release: GitHubReleaseDto,
        val version: SemanticVersion,
        val prerelease: Boolean
    )
}
