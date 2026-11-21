package com.droidcraft.app.studio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Module C — Content Rewards campaign tracker. */
@Entity(tableName = "campaigns")
data class CampaignEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val ratePerThousand: Double,   // $ per 1,000 views
    val deadline: String,          // yyyy-MM-dd
    val clipsSubmitted: Int = 0,
    val status: String = "pending" // pending | approved | paid
)

/** Module D — Job applications tracker (sponsored jobs abroad). */
@Entity(tableName = "job_applications")
data class JobApplicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val company: String,
    val role: String,
    val appliedOn: String,   // yyyy-MM-dd
    val status: String = "applied" // applied | interview | offer | rejected
)

/** Module D — Hindi notes / reminders. */
@Entity(tableName = "hindi_notes")
data class HindiNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** Module C — his channels (static list + quick links). */
object CreatorChannels {
    data class Channel(val name: String, val handle: String, val studioPath: String)

    val ALL = listOf(
        Channel("GT Marwadi Vlogs", "@gtmarwadivlogs", "/channel/UC_gtmarwadi"),
        Channel("GT Tech Gyaan", "@gttechgyaan", "/channel/UC_gttechgyaan"),
        Channel("GT6 Ki Duniya Hindi", "@gt6ki Duniya", "/channel/UC_gt6duniya"),
        Channel("Mind Facts Hindi", "@mindfactshindi", "/channel/UC_mindfacts"),
        Channel("Kids Videos Hindi Story", "@kidsvideoshindi", "/channel/UC_kidsvideos"),
        Channel("Lumo Tales", "@lumotales", "/channel/UC_lumotales"),
        Channel("Khanak Drops", "@khanakdrops", "/channel/UC_khanakdrops"),
    )

    fun studioUrl(channel: Channel): String =
        "https://studio.youtube.com${channel.studioPath}"
}
