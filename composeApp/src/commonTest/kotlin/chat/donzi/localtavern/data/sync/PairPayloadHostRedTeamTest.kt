package chat.donzi.localtavern.data.sync

import kotlin.test.Test
import kotlin.test.assertNull

// Red-team: QR host must be a bare host:port like fetch addresses are.
class PairPayloadHostRedTeamTest {

    @Test
    fun pathTraversalHost_rejected() {
        assertNull(
            PairPayload.fromQrText("localtavern://pair?h=evil.com%2Fsteal%3Fx%3D&p=47324&d=abc&n=x"),
            "host with decoded '/' must be rejected"
        )
    }

    @Test
    fun credentialsHost_rejected() {
        assertNull(
            PairPayload.fromQrText("localtavern://pair?h=user%40evil.com&p=47324&d=abc&n=x"),
            "host with decoded '@' must be rejected"
        )
    }

    @Test
    fun queryFragmentHost_rejected() {
        assertNull(
            PairPayload.fromQrText("localtavern://pair?h=evil.com%3Fq%3D1&p=47324&d=abc&n=x")
        )
        assertNull(
            PairPayload.fromQrText("localtavern://pair?h=evil.com%23123&p=47324&d=abc&n=x")
        )
    }

    @Test
    fun plainLanHost_accepted() {
        val payload = PairPayload.fromQrText("localtavern://pair?h=192.168.1.5&p=47324&d=abc123&n=Phone")
        kotlin.test.assertNotNull(payload)
    }
}
