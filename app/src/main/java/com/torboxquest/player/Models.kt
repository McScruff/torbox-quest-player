package com.torboxquest.player

enum class SourceType(val segment: String, val idParam: String, val label: String) {
    TORRENT("torrents", "torrent_id", "Torrent"),
    USENET("usenet", "usenet_id", "Usenet"),
    WEB("webdl", "web_id", "Web")
}

data class TorBoxFile(
    val id: Long,
    val shortName: String,
    val size: Long
)

data class TorBoxItem(
    val id: Long,
    val type: SourceType,
    val name: String,
    val size: Long,
    val ready: Boolean,
    val statusLabel: String,
    val createdAt: Long,
    val files: List<TorBoxFile>
)
