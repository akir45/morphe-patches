package app.morphe.patches.youtube.interaction.playlistautoplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.playerStatusMethodRef
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisablePlaylistAutoplayPatch;"

@Suppress("unused")
val disablePlaylistAutoplayPatch = bytecodePatch(
    name = "Disable playlist autoplay",
    description = "Adds an option to stop playback when a playlist video ends.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_playlist_autoplay", summary = true),
        )

        playerStatusMethodRef.get()!!.apply {
            val insertIndex = indexOfFirstInstructionOrThrow(Opcode.SGET_OBJECT)
            val freeRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

            addInstructionsWithLabels(
                insertIndex,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->shouldStopPlayback(Ljava/lang/Enum;)Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :continue_playback_status
                    return-void
                    :continue_playback_status
                    nop
                """,
            )
        }
    }
}
