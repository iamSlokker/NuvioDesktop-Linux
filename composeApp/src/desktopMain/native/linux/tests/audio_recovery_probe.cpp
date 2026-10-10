#include "audio_passthrough_recovery.h"

// Test-only ctypes shim: same helper and libmpv calls as the production event
// pump, without loading player_bridge/JNI, initializing GTK or opening windows.
extern "C" {
void *audio_recovery_create() { return new LinuxAudioPassthroughRecovery; }
void audio_recovery_destroy(void *state) {
    delete static_cast<LinuxAudioPassthroughRecovery *>(state);
}
int audio_recovery_accept(void *state, mpv_handle *mpv, const mpv_event *event) {
    const auto result = static_cast<LinuxAudioPassthroughRecovery *>(state)->accept(mpv, *event);
    return result.error < 0 ? result.error : result.recovered ? 1 : 0;
}
}
