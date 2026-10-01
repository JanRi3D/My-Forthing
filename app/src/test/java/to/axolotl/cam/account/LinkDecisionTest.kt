package to.axolotl.cam.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import to.axolotl.cam.account.LinkDecision.APPLY_REMOTE
import to.axolotl.cam.account.LinkDecision.ASK
import to.axolotl.cam.account.LinkDecision.OTHER_ACCOUNT
import to.axolotl.cam.account.LinkDecision.UPLOAD_LOCAL
import to.axolotl.cam.account.MergeStrategy.KEEP_LOCAL
import to.axolotl.cam.account.MergeStrategy.KEEP_REMOTE
import to.axolotl.cam.core.model.LocalProfile

/** The guest → account migration matrix (CONTRACTS §9): nothing is overwritten without the user's choice. */
class LinkDecisionTest {
    private val guest = LocalProfile("p", "Mein Auto", null, createdAt = 1L, linkedUid = null)
    private val linkedToMe = guest.copy(linkedUid = "me")
    private val linkedToOther = guest.copy(linkedUid = "other")

    private fun decide(local: LocalProfile?, remote: Boolean, strategy: MergeStrategy = MergeStrategy.ASK) =
        decideLink(local, "me", remote, strategy)

    @Test
    fun `empty remote takes the guest profile without asking`() {
        assertThat(decide(guest, remote = false)).isEqualTo(UPLOAD_LOCAL)
    }

    @Test
    fun `guest and remote both with data ask`() {
        assertThat(decide(guest, remote = true)).isEqualTo(ASK)
    }

    @Test
    fun `the user's choice resolves a conflict`() {
        assertThat(decide(guest, remote = true, KEEP_LOCAL)).isEqualTo(UPLOAD_LOCAL)
        assertThat(decide(guest, remote = true, KEEP_REMOTE)).isEqualTo(APPLY_REMOTE)
    }

    @Test
    fun `same account again - its copy is newer than the phone's cache`() {
        assertThat(decide(linkedToMe, remote = true)).isEqualTo(APPLY_REMOTE)
        assertThat(decide(linkedToMe, remote = true, KEEP_LOCAL)).isEqualTo(APPLY_REMOTE)
    }

    @Test
    fun `same account whose document is gone gets the phone's profile back`() {
        assertThat(decide(linkedToMe, remote = false)).isEqualTo(UPLOAD_LOCAL)
    }

    @Test
    fun `fresh install signed in`() {
        assertThat(decide(null, remote = true)).isEqualTo(APPLY_REMOTE)
        assertThat(decide(null, remote = false)).isEqualTo(UPLOAD_LOCAL)
    }

    @Test
    fun `a profile linked to another account is never taken over unasked`() {
        assertThat(decide(linkedToOther, remote = true)).isEqualTo(OTHER_ACCOUNT)
        assertThat(decide(linkedToOther, remote = false)).isEqualTo(OTHER_ACCOUNT)
        assertThat(decide(linkedToOther, remote = true, KEEP_LOCAL)).isEqualTo(OTHER_ACCOUNT)
    }

    @Test
    fun `switching to the signed-in account`() {
        assertThat(decide(linkedToOther, remote = true, KEEP_REMOTE)).isEqualTo(APPLY_REMOTE)
        assertThat(decide(linkedToOther, remote = false, KEEP_REMOTE)).isEqualTo(UPLOAD_LOCAL)
    }
}
