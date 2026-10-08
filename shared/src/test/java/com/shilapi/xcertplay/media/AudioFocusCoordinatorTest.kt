package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusCoordinatorTest {
    @Test
    fun focusPrioritiesAreOrderedCorrectly() {
        val media = AudioFocusCoordinator.focusPriority(AudioChannel.MEDIA)
        val phone = AudioFocusCoordinator.focusPriority(AudioChannel.PHONE)
        val assistant = AudioFocusCoordinator.focusPriority(AudioChannel.ASSISTANT)
        val navigation = AudioFocusCoordinator.focusPriority(AudioChannel.NAVIGATION)

        assertTrue("Media must have higher focus priority than telephony", media > phone)
        assertTrue("Telephony must have higher focus priority than assistant", phone > assistant)
        assertTrue("Assistant must have higher focus priority than navigation", assistant > navigation)
        assertEquals("Navigation guidance does not claim exclusive focus", 0, navigation)
    }

    @Test
    fun volumeConstantsMatchCarPlayStandards() {
        assertEquals(1.0f, AudioFocusCoordinator.FULL_VOLUME, 0.001f)
        assertEquals(0.2f, AudioFocusCoordinator.DUCKED_VOLUME, 0.001f)
    }

    @Test
    fun nullContextCoordinatorHandlesLifecycleSafely() {
        val coordinator = AudioFocusCoordinator(
            context = null,
            enabled = true,
            muteMediaOnTransientLoss = true,
        )
        // Ensure no NullPointerException or crash occurs with null system services
        coordinator.close()
    }
}
