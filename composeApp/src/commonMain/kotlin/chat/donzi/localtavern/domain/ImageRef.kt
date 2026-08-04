package chat.donzi.localtavern.domain

import kotlinx.serialization.Serializable

// A content-addressed reference to one stored image blob. The blob itself
// lives in the platform blob store (key = sha256), never in the database;
// only the reference travels with the message row, keeps the database small,
// and rides inside sync envelopes so peers can fetch the bytes out-of-band.
@Serializable
data class ImageRef(
    val sha256: String,
    val size: Long
)
