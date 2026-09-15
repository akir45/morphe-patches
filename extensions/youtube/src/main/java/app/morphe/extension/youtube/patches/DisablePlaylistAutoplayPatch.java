package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class DisablePlaylistAutoplayPatch {

    private DisablePlaylistAutoplayPatch() {
    }

    public static boolean shouldStopPlayback(Enum<?> status) {
        try {
            return Settings.DISABLE_PLAYLIST_AUTOPLAY.get()
                    && !Settings.LOOP_VIDEO.get()
                    && status != null
                    && "ENDED".equals(status.name())
                    && !VideoInformation.lastVideoIdIsShort()
                    && !VideoInformation.getPlaylistId().isEmpty();
        } catch (Exception ex) {
            Logger.printException(() -> "shouldStopPlayback failure", ex);
            return false;
        }
    }
}
