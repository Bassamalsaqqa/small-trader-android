package com.example.data.mobile.outbox

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class OutboxBackupPolicyTest {
    @Test fun excludesEveryStorageDomainForCloudAndDeviceTransfer() {
        val base = File("src/main")
        val domains = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        fun check(element: Element) {
            val nodes = element.getElementsByTagName("exclude")
            val excluded = (0 until nodes.length).map {
                val node = nodes.item(it) as Element
                assertEquals(".", node.getAttribute("path"))
                node.getAttribute("domain")
            }.toSet()
            assertEquals(domains, excluded)
        }
        val factory = DocumentBuilderFactory.newInstance()
        check(factory.newDocumentBuilder().parse(File(base, "res/xml/backup_rules.xml")).documentElement)
        val extraction = factory.newDocumentBuilder().parse(File(base, "res/xml/data_extraction_rules.xml"))
        for (name in listOf("cloud-backup", "device-transfer")) {
            assertEquals(1, extraction.getElementsByTagName(name).length)
            check(extraction.getElementsByTagName(name).item(0) as Element)
        }
        val manifest = File(base, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("@xml/backup_rules"))
        assertTrue(manifest.contains("@xml/data_extraction_rules"))
    }
}
