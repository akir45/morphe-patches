package app.morphe.patches.youtube.interaction.livechatdanmaku

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.request.buildRequestPatch
import app.morphe.patches.youtube.misc.request.hookBuildRequest
import app.morphe.patches.youtube.misc.request.hookBuildRequestWithBody
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/LiveChatDanmakuPatch;"

@Suppress("unused")
val liveChatDanmakuPatch = bytecodePatch(
    name = "Live chat danmaku",
    description = "Displays YouTube live chat messages as fullscreen danmaku comments over the video player.",
) {
    dependsOn(
        settingsPatch,
        playerOverlayButtonsHookPatch,
        videoIdPatch,
        videoInformationPatch,
        buildRequestPatch,
        playerTypeHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_live_chat_danmaku", summary = true),
                TextPreference("morphe_live_chat_danmaku_color"),
            )
        )

        hookVideoId("$EXTENSION_CLASS->newVideoLoaded(Ljava/lang/String;)V")
        hookBuildRequestWithBody("$EXTENSION_CLASS->setRequestBody(Ljava/lang/String;[B)V")
        hookBuildRequest("$EXTENSION_CLASS->setRequestHeaders(Ljava/lang/String;Ljava/util/Map;)V")
        addPlayerBottomButton(EXTENSION_CLASS)
    }
}
