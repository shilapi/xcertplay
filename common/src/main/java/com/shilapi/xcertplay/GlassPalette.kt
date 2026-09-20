package com.shilapi.xcertplay

import android.graphics.Color

/**
 * Shared light "liquid glass" palette for the xcertplay host UI.
 *
 * Surfaces are translucent white so the video (or the launch background) shows through, with a
 * bright hairline rim standing in for the specular edge light. Text and control colours are
 * picked for contrast on those translucent surfaces rather than on a solid background.
 *
 * `LandscapeLaunchActivity` mirrors these values as Compose `Color`s because Compose cannot
 * consume the Android `Color` ints directly; keep the two in sync when adjusting the palette.
 */
internal object GlassPalette {
    /** Glass surface sheen: slightly more opaque at the top edge, thinner towards the bottom. */
    val GLASS_TOP = Color.argb(0xF2, 0xFF, 0xFF, 0xFF)
    val GLASS_BOTTOM = Color.argb(0xD6, 0xFF, 0xFF, 0xFF)

    /** Hairline rim light drawn on the glass edge. */
    val GLASS_RIM = Color.argb(0xCC, 0xFF, 0xFF, 0xFF)

    /** Flat (non-gradient) glass, used for the compact chips and the log panel. */
    val GLASS_FILL = Color.argb(0xCC, 0xFF, 0xFF, 0xFF)

    /** Frosted backdrop behind the settings sheet and the safe-area editor. */
    val SCRIM = Color.argb(0xE8, 0xEC, 0xF0, 0xF5)

    val PRIMARY = Color.rgb(0x11, 0x18, 0x1C)
    val SECONDARY = Color.rgb(0x5A, 0x66, 0x72)
    val TERTIARY = Color.rgb(0x8A, 0x94, 0x9E)

    /** Deep mint: the original `#7FCD9A` brand accent darkened to stay legible on white. */
    val ACCENT = Color.rgb(0x0A, 0x8F, 0x5F)

    /**
     * Off track. Must stay dark enough to be seen against the near-white glass panel: the
     * previous `#C3CCD6` sat at roughly 1.5:1, so an off switch read as an empty hole. This value
     * clears 3:1 against the panel, which is the WCAG bar for non-text UI components.
     */
    val TRACK_OFF = Color.rgb(0x84, 0x8F, 0x9C)

    /**
     * Both switch knobs are white, as on iOS: the on/off state is carried by the track colour.
     * Tinting the checked knob with the accent made it blend into the accent track on light glass.
     */
    val THUMB_ON = Color.rgb(0xFF, 0xFF, 0xFF)
    val THUMB_OFF = Color.rgb(0xFF, 0xFF, 0xFF)

    val BUTTON_TEXT = Color.rgb(0xFF, 0xFF, 0xFF)

    /** Secondary (cancel-style) button fill: neutral glass instead of the accent. */
    val BUTTON_NEUTRAL = Color.argb(0xF2, 0xE4, 0xE9, 0xEF)
    val DANGER = Color.rgb(0xD9, 0x3A, 0x3A)

    /** Shown while no video is attached. */
    val BACKGROUND_TOP = Color.rgb(0xF2, 0xF5, 0xF9)
    val BACKGROUND_BOTTOM = Color.rgb(0xE2, 0xE8, 0xF1)

    /**
     * Safe-area editor: the frosted veil that covers everything outside the rectangle.
     *
     * This used to be a flat `argb(0x66, near-black)` laid over an almost opaque light sheet, so
     * the whole editor came out as one muddy grey slab -- no depth, and no way to see what was
     * being kept. A cool, denser frost keeps the "liquid glass" language while still dimming the
     * pixels that will be cropped away.
     */
    val SCRIM_FROST = Color.argb(0xD9, 0xAF, 0xBB, 0xCC)

    /** Feather shadow hugging the rectangle: darkest on the boundary, clearing outwards. */
    val SCRIM_SHADOW = Color.argb(0x45, 0x0B, 0x12, 0x1A)
    val SCRIM_SHADOW_CLEAR = Color.argb(0x00, 0x0B, 0x12, 0x1A)

    /** Halo under the accent boundary lines so they stay readable on bright video. */
    val ACCENT_HALO = Color.argb(0xA6, 0xFF, 0xFF, 0xFF)

    /** Whisper of white inside the rectangle: the pane that survives the crop. */
    val PANE_WASH = Color.argb(0x1A, 0xFF, 0xFF, 0xFF)

    /** Backing pill for the coordinate labels, which float over arbitrary video. */
    val LABEL_BG = Color.argb(0xF2, 0xFF, 0xFF, 0xFF)

    /**
     * Backing pill for text that floats over arbitrary user imagery (the crop hint). The hint
     * used to be near-black text drawn straight onto the photo, so it vanished over dark images.
     */
    val HINT_SCRIM = Color.argb(0xCC, 0x0B, 0x12, 0x1A)

    /** Frosted mask over everything outside the crop square in the icon picker. */
    val CROP_MASK = Color.argb(0xB4, 0xFB, 0xFC, 0xFE)

    /** Hairline outline drawn around the safe area. */
    val HAIRLINE = Color.argb(0x99, 0x11, 0x18, 0x1C)
}
