package com.banknotify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BackupRulesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `device bound preferences and payment database are excluded from every backup mode`() {
        assertExclusions("backup_rules", "full-backup-content")
        assertExclusions("data_extraction_rules", "cloud-backup")
        assertExclusions("data_extraction_rules", "device-transfer")
    }

    private fun assertExclusions(resource: String, section: String) {
        val id = context.resources.getIdentifier(resource, "xml", context.packageName)
        assertTrue("Missing $resource", id != 0)
        val exclusions = mutableSetOf<Pair<String, String>>()
        context.resources.getXml(id).use { parser ->
            var inSection = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    if (parser.name == section) inSection = true
                    if (inSection && parser.name == "exclude") {
                        exclusions.add(parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path"))
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == section) {
                    inSection = false
                }
                parser.next()
            }
        }
        assertTrue("$resource/$section must exclude encrypted preferences", "sharedpref" to "bank_notify.xml" in exclusions)
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            assertTrue("$resource/$section must exclude payment DB$suffix", "database" to "bank_notifications.db$suffix" in exclusions)
        }
    }
}
