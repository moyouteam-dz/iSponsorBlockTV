package com.moyou.isponsorblocktv.domain

data class MonitorConfig(
    val screenId: String = "",
    val screenName: String = "",
    val categories: Set<String> = setOf("sponsor"),
    val muteAds: Boolean = true,
    val skipAds: Boolean = true,
    val trackSkips: Boolean = true,
    val autoPlay: Boolean = true,
    val minSkipSeconds: Int = 1,
    val offsetMillis: Int = 0,
) {
    val isPaired: Boolean get() = screenId.isNotBlank()
}

enum class LinkStatus { STOPPED, CONNECTING, CONNECTED, RETRYING, ERROR }

data class MonitorStatus(
    val status: LinkStatus = LinkStatus.STOPPED,
    val detail: String = "الخدمة متوقفة",
    val currentVideoId: String = "",
    val skippedSegments: Int = 0,
    val mutedByApp: Boolean = false,
)
