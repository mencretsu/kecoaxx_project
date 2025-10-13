package com.example.ngontol.config

import com.example.ngontol.models.AppConfig

object AppConfigs {
    val SUGO = AppConfig(
        packageName = "com.voicemaker.android",
        listViewId = "com.voicemaker.android:id/ll_chat_item",
        unreadViewId = "com.voicemaker.android:id/id_unread_tcv",
        inputViewId = "com.voicemaker.android:id/id_input_edit_text",
        sendViewId = "com.voicemaker.android:id/id_chat_send_btn",
        diamondViewId = "com.voicemaker.android:id/contentView",
        conversationTabId = "com.voicemaker.android:id/id_main_bottomtab_conv",
        allTabId = "com.voicemaker.android:id/id_conv_tab_all",
        unreadTabId = "com.voicemaker.android:id/id_conv_tab_unread",
        cancelButtonIds = listOf(
            "com.voicemaker.android:id/tv_cancel",
            "com.voicemaker.android:id/id_close_dialog",
            "com.voicemaker.android:id/close"
        )
    )

    val MOMO = AppConfig(
        packageName = "com.hwsj.club",
        listViewId = "com.hwsj.club:id/bodyView",
        unreadViewId = "com.hwsj.club:id/tvBadge",
        inputViewId = "com.hwsj.club:id/editContent",
        sendViewId = "com.hwsj.club:id/ivSend",
        diamondViewId = "",
        conversationTabId = "",
        allTabId = "",
        unreadTabId = "",
        cancelButtonIds = listOf(
            "com.hwsj.club:id/negativeButton",
            "com.hwsj.club:id/ivClose",
            "com.hwsj.club:id/mIvBtnBg"
        )
    )

    val FIYA = AppConfig(
        packageName = "com.fiya.android",
        listViewId = "com.fiya.android:id/ll_chat_item",
        unreadViewId = "com.fiya.android:id/id_unread_tcv",
        inputViewId = "com.fiya.android:id/id_input_edit_text",
        sendViewId = "com.fiya.android:id/id_chat_send_btn",
        diamondViewId = "com.fiya.android:id/contentView",
        conversationTabId = "com.fiya.android:id/id_main_bottomtab_conv",
        allTabId = "com.fiya.android:id/id_conv_tab_all",
        unreadTabId = "com.fiya.android:id/id_conv_tab_unread",
        cancelButtonIds = listOf(
            "com.fiya.android:id/tv_cancel",
            "com.fiya.android:id/id_close_dialog",
            "com.fiya.android:id/close",
            "com.fiya.android:id/text_skip"
        )
    )
}