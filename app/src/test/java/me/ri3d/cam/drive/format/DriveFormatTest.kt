package me.ri3d.cam.drive.format

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import me.ri3d.cam.drive.DriveFile
import java.time.ZoneId
import java.time.ZoneOffset

class DriveFormatTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val full = DriveSidecar(
        id = "7f9c1d2e-0000-4000-8000-000000000001",
        kind = "ENHANCED_FRAME",
        category = "EVENT",
        recorderType = 1,
        originalFileName = "20261001120000_0001.MP4",
        recorderPath = "/mnt/sd/EVENT/20261001120000_0001.MP4",
        recorderTime = "2026-10-01 12:00:00",
        downloadedAt = "2026-10-01T12:03:00+02:00",
        sizeBytes = 123,
        md5 = "900150983cd24fb0d6963f7d28e17f72",
        mime = "image/jpeg",
        durationMs = null,
        parent = DriveSidecar.Parent("7f9c1d2e-0000-4000-8000-000000000000", 37_000),
        plates = listOf(DriveSidecar.Plate("B-MK 4821", "BMK4821", 37_000, 0.93f, listOf(10, 20, 110, 50))),
        backup = DriveSidecar.Backup(complete = true, completedAt = "2026-10-01T12:05:00+02:00"),
    )

    @Test
    fun `sidecar round-trips`() {
        assertThat(DriveSidecar.fromJson(full.toJson())).isEqualTo(full)
        val minimal = full.copy(parent = null, plates = null, recorderType = null, recorderPath = null)
        assertThat(DriveSidecar.fromJson(minimal.toJson())).isEqualTo(minimal)
    }

    @Test
    fun `every field is written, nulls explicitly`() {
        val json = full.copy(parent = null, plates = null).toJson()
        assertThat(json).startsWith("{\"format\":1,\"id\":")
        listOf("\"recorderTimeZone\":null", "\"durationMs\":null", "\"parent\":null", "\"plates\":null").forEach {
            assertThat(json).contains(it)
        }
        assertThat(full.toJson()).contains("\"confidence\":0.93")
    }

    @Test
    fun `the CONTRACTS example parses`() {
        val example = """
            { "format": 1, "id": "u", "kind": "ORIGINAL_VIDEO", "category": "NORMAL", "recorderType": 1,
              "originalFileName": "a.MP4", "recorderPath": "/x", "recorderTime": "2026-10-01 12:00:00",
              "recorderTimeZone": null, "downloadedAt": "2026-10-01T12:03:00+02:00", "sizeBytes": 123, "md5": "m",
              "mime": "video/mp4", "durationMs": 60000, "parent": { "id": "p", "positionMs": 37000 },
              "plates": [ { "text": "B-MK 4821", "normalized": "BMK4821", "positionMs": 37000, "confidence": null, "box": [0, 0, 0, 0] } ],
              "backup": { "complete": true, "completedAt": "2026-10-01T12:05:00+02:00" }, "addedInV1_1": "ignored" }
        """
        val sidecar = DriveSidecar.fromJson(example)
        assertThat(sidecar.durationMs).isEqualTo(60_000)
        assertThat(sidecar.plates!!.single().confidence).isNull()
        assertThat(sidecar.parent).isEqualTo(DriveSidecar.Parent("p", 37_000))
    }

    @Test
    fun `manifest matches the format`() {
        assertThat(DriveFormat.manifestJson(0, ZoneOffset.UTC))
            .isEqualTo("""{"format":1,"app":"me.ri3d.cam","createdAt":"1970-01-01T00:00:00Z"}""")
    }

    @Test
    fun `names, appProperties and timestamps`() {
        assertThat(DriveFormat.mediaFileName("id", "20261001120000_0001.MP4", "video/mp4")).isEqualTo("id.mp4")
        assertThat(DriveFormat.mediaFileName("id", "screenshot", "image/jpeg")).isEqualTo("id.jpg")
        assertThat(DriveFormat.sidecarFileName("id")).isEqualTo("id.json")
        assertThat(DriveFormat.mediaAppProperties("id", "SCREENSHOT", "USER", parentId = null)).containsExactly(
            "mf.format", "1", "mf.role", "media", "mf.id", "id", "mf.kind", "SCREENSHOT", "mf.category", "USER",
        )
        assertThat(DriveFormat.mediaAppProperties("id", "UPSCALED_CLIP", "NORMAL", "p")).containsEntry("mf.parent", "p")
        assertThat(DriveFormat.isoTimestamp(1_790_848_980_000, ZoneId.of("Europe/Berlin"))).isEqualTo("2026-10-01T12:03:00+02:00")
        assertThat(DriveFormat.isoTimestamp(1_790_848_980_123, ZoneId.of("Europe/Berlin"))).isEqualTo("2026-10-01T12:03:00.123+02:00")
        assertThat(DriveFormat.isoTimestamp(1_790_848_980_900, ZoneOffset.UTC)).isEqualTo("2026-10-01T10:03:00.9Z")
    }

    @Test
    fun `month folder prefers the recorder time guess`() {
        val oct = 1_790_848_980_000L // 2026-10-01 10:03 UTC
        val nov = oct + 31L * 24 * 3600 * 1000
        assertThat(DriveFormat.monthFolderName(oct, nov, ZoneOffset.UTC)).isEqualTo("2026-10")
        assertThat(DriveFormat.monthFolderName(null, nov, ZoneOffset.UTC)).isEqualTo("2026-11")
    }

    @Test
    fun `query literals are escaped`() {
        assertThat(DriveFormat.escape("""O'Brien\x""")).isEqualTo("""O\'Brien\\x""")
        assertThat(DriveFormat.childQuery("a'b", "p", folder = true))
            .isEqualTo("name = 'a\\'b' and 'p' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
    }

    @Test
    fun `md5 is lower-case hex`() {
        val file = tmp.newFile().apply { writeText("abc") }
        assertThat(DriveFormat.md5Hex(file)).isEqualTo("900150983cd24fb0d6963f7d28e17f72")
    }

    @Test
    fun `reader pairs media with sidecars`() {
        fun file(id: String, props: Map<String, String>, created: String = "2026-10-01T00:00:00Z") =
            DriveFile(id = id, appProperties = props, createdTime = created)
        val files = listOf(
            file("m1", DriveFormat.mediaAppProperties("a", "ORIGINAL_VIDEO", "NORMAL", null)),
            file("s1", DriveFormat.sidecarAppProperties("a")),
            file("m2", DriveFormat.mediaAppProperties("b", "ORIGINAL_PHOTO", "USER", null)), // upload not verified yet
            file("s3", DriveFormat.sidecarAppProperties("c")), // media deleted by the user in Drive
            file("m4new", DriveFormat.mediaAppProperties("d", "ORIGINAL_VIDEO", "EVENT", null), "2026-10-02T00:00:00Z"),
            file("m4old", DriveFormat.mediaAppProperties("d", "ORIGINAL_VIDEO", "EVENT", null), "2026-10-01T00:00:00Z"),
            file("root", DriveFormat.roleAppProperties(DriveFormat.ROLE_ROOT)),
            file("manifest", DriveFormat.roleAppProperties(DriveFormat.ROLE_MANIFEST)),
            file("v2", mapOf("mf.format" to "2", "mf.role" to "media", "mf.id" to "f")),
        )

        val entries = DriveFormatReader.pair(files).associateBy { it.mediaId }

        assertThat(entries.keys).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(entries.getValue("a").complete).isTrue()
        assertThat(entries.getValue("b").complete).isFalse()
        assertThat(entries.getValue("c").media).isNull()
        assertThat(entries.getValue("c").complete).isFalse()
        assertThat(entries.getValue("d").media!!.id).isEqualTo("m4old")
    }
}
