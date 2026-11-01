package com.sboard.app.feature.drive

import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveApiErrorTest {
    @Test fun apiDisabledErrorNamesRequiredAction() {
        val message = GoogleDriveApiError.describe("list", 403, """{"error":{"code":403,"message":"Drive API has not been used","errors":[{"reason":"accessNotConfigured"}]}}""")
        assertTrue(message.contains("Enable Google Drive API"))
        assertTrue(message.contains("accessNotConfigured"))
    }

    @Test fun missingScopeIsActionable() {
        val message = GoogleDriveApiError.describe("upload", 403, """{"error":{"errors":[{"reason":"insufficientPermissions"}]}}""")
        assertTrue(message.contains("drive.appdata"))
    }

    @Test fun unauthorizedResponsePromptsReconnect() {
        assertTrue(GoogleDriveApiError.describe("download", 401, null).contains("reconnect Google Drive"))
    }
}
