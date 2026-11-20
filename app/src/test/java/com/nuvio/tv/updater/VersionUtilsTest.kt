package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {
    @Test
    fun `stable release is newer than its prerelease`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0", "1.1.0-rc.2"))
    }

    @Test
    fun `beta identifiers use numeric ordering`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.10", "1.1.0-beta.9"))
    }

    @Test
    fun `prerelease for a later minor is newer than current stable`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.1", "1.0.3"))
    }

    @Test
    fun `stable channel patch is not newer than later minor beta`() {
        assertFalse(VersionUtils.isRemoteNewer("1.0.4", "1.1.0-beta.2"))
    }

    @Test
    fun `version prefix and build metadata do not affect precedence`() {
        assertFalse(VersionUtils.isRemoteNewer("v1.0.0+18", "1.0.0+17"))
    }

    @Test
    fun `invalid remote version is not offered`() {
        assertFalse(VersionUtils.isRemoteNewer("latest", "1.0.0"))
    }

    @Test
    fun `current beta naming is recognized as prerelease`() {
        assertTrue(VersionUtils.isPrerelease("0.8.12-beta"))
    }

    @Test
    fun `test builds compare by build number, even when the Nuvio base changes`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-test.441001+beta.9", "1.1.0-test.441000+beta.9"))
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-test.441001+beta.10", "1.1.0-test.441000+beta.9"))
        assertFalse(VersionUtils.isRemoteNewer("1.1.0-test.441000+beta.10", "1.1.0-test.441001+beta.9"))
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-test.99999+beta.9", "1.1.0-test.9999+beta.9"))
    }

    @Test
    fun `NuvioMate releases are newer than the plain build and than earlier NuvioMate releases`() {
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-beta.2.mate.1", "1.2.0-beta.2"))
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-beta.2.mate.10", "1.2.0-beta.2.mate.9"))
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-beta.3.mate.1", "1.2.0-beta.2.mate.7"))
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-mate.1", "1.2.0-beta.3.mate.4"))
        assertFalse(VersionUtils.isRemoteNewer("1.2.0-beta.2.mate.3", "1.2.0-beta.2.mate.3"))
    }

    @Test
    fun `nuviomate X-Y versions are newer than the old mate builds and sort by X then Y`() {
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-beta.2.nuviomate.1.1", "1.2.0-beta.2.mate.1"))
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-beta.2.nuviomate.1.10", "1.2.0-beta.2.nuviomate.1.9"))
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-beta.2.nuviomate.2.0", "1.2.0-beta.2.nuviomate.1.12"))
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-beta.3.nuviomate.1.4", "1.2.0-beta.2.nuviomate.1.3"))
        assertTrue(VersionUtils.isRemoteNewer("1.2.0-nuviomate.1.5", "1.2.0-beta.3.nuviomate.1.4"))
        assertFalse(VersionUtils.isRemoteNewer("1.2.0-beta.2.nuviomate.1.3", "1.2.0-beta.2.nuviomate.1.3"))
    }
}
