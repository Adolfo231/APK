package com.fastdtv.app

import android.net.Uri

data class ChannelItem(
    val id: Long,
    val inputId: String,
    val displayNumber: String,
    val displayName: String,
    val uri: Uri
)
