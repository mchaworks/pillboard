# Pillboard — Liquid Expressive

Liquid Expressive is Pillboard's unified visual language: iOS 26-inspired Liquid Glass surfaces paired with Material 3 Expressive geometry, color roles, and interaction feedback. It is a single system, not two themes layered on top of each other.

## Design principles

1. **Glass belongs to the shell.** The keyboard container and suggestion surface may be translucent, softly outlined, and backdrop-blurred where Android supports cross-window blur.
2. **Keycaps stay readable.** Letter keys use solid Material tonal surfaces. They are not individually transparent glass tiles.
3. **Expressive geometry.** Rounded keycaps, a larger floating keyboard shell, and distinct special-key shapes create hierarchy without sacrificing familiar QWERTY ergonomics.
4. **Dynamic color remains semantic.** Material You supplies the accent and tonal roles. Special, active, and action keys use those roles rather than arbitrary decoration.
5. **Motion communicates input.** Presses respond immediately with a slight scale-down, tonal highlight, and corner morph; release uses a spring-like overshoot. Motion never delays text input.
6. **Graceful blur fallback.** If the ROM disables cross-window blur, the setting still applies a translucent glass tint and outline instead of disabling the control.

## Visual layers

- **Keyboard shell:** large rounded translucent surface, subtle outline, soft top highlight, and optional Android backdrop blur.
- **Suggestion strip:** inset capsule-like glass surface with restrained separators and clear text.
- **Letter keycaps:** solid tonal surfaces, subtle outline/elevation, readable high-contrast glyphs.
- **Special keys:** distinct tonal surface; Shift has off/active/locked states; Enter uses the Material accent when the editor exposes an action.
- **Long-press alternatives:** floating rounded glass popup with an accent selection state.
- **Bottom row:** `?123`, comma, space, period, and editor action. The in-keyboard globe is removed to avoid duplicating Android's system language-switch affordance.

## Motion

- Key down: immediate color response and approximately 0.94 scale.
- Key release: return to 1.0 scale with a restrained overshoot.
- Corner radius expands slightly while pressed, then returns to the configured key radius.
- Long-press alternatives remain responsive and use the same glass/accent roles.

## Core tokens

| Token | Role |
|---|---|
| `background` | Base keyboard surface |
| `glassFill` | Translucent shell/suggestion tint |
| `glassStroke` | Subtle glass edge |
| `glassHighlight` | Specular/highlight edge and pressed outline |
| `key` / `keyText` | Readable letter-key surface and foreground |
| `special` / `specialText` | Secondary control surface and foreground |
| `accent` / `onAccent` | Primary action and selected states |
| `pressed` / `pressedText` | Immediate key-press feedback |

## Balance

The target is a balanced fusion: Liquid Glass controls the outer surface, depth, and translucency; Material 3 Expressive controls geometry, semantic color, hierarchy, and interaction. Avoid applying blur or transparency to every key because it weakens legibility and makes the keyboard visually noisy.

## Implementation notes (Phase 1–2)

- **Tokens:** all geometry, elevation, motion and color live in `KbTokens` (container 30dp, key 12dp default, special keys +3dp, Enter/active Shift +8dp, popup 20dp, strip 18dp).
- **Shell:** `KbTokens.shell()` — tonal gradient + hairline outline + a specular top edge drawn inside the same drawable (no extra views).
- **Keycaps:** solid tonal surfaces with a very subtle top-to-bottom sheen; pressed state is a solid tonal fill with corner morph.
- **Icons:** Material-Symbols-style rounded vectors (`ic_backspace`, `ic_shift`, `ic_shift_on`, `ic_caps_lock`, `ic_enter`, `ic_search`, `ic_send`, `ic_check`, `ic_arrow_forward`), drawn centered by `KeyView`.
- **Suggestion bar:** faint tonal wash over the glass, no outline, hairline dividers, pressed state per slot, medium weight for the lead candidate.
- **Popups:** `KeyPreview` (one reusable window, shown after the key is committed) and the long-press alternates share the same near-opaque floating surface, padded so elevation shadows are not clipped.
- **Rows:** `isBaselineAligned = false` — mixed text sizes and icons must never shift keys vertically.
- No globe key: system navigation already provides language switching.

## Implementation notes (Phase 3 — motion & effects)

- **Press:** input is committed first, then feedback. Tonal change and corner morph (+3dp) are instant; scale-down to 0.94 in 65ms.
- **Release:** real damped-spring interpolator (`SpringInterpolator`, ζ = 0.62, ω = 22 rad/s, ~340ms) — no `androidx.dynamicanimation` dependency. Corner radius eases back in 140ms.
- **Organic morph:** Shift state changes (off → on → lock) cross-fade tone and corner radius with the same spring instead of snapping.
- **Page change (?123 ↔ ABC):** rows fade/rise in with an 18ms stagger instead of an abrupt rebuild.
- **Preview bubble:** scale/fade in from its bottom edge, 70ms fade-out; a new press cancels the fade, so rolling typing never flickers.
- **Glass:** shell is more translucent only when real backdrop blur is active; blur requested but unsupported falls back to a near-opaque tint (0xF2); blur off is a solid tonal shell. The window background is a rounded rect so the blur is masked to the shell corners. Blur state changes at runtime (battery saver) re-apply the shell.

## Validation (Phase 4)

Contrast, computed from the Material 3 baseline fallbacks (dynamic color will vary):

| Pair | Dark | Light |
|---|---|---|
| Text on keycap | 9.5:1 | 17.1:1 |
| Text on special key | 7.2:1 | ~10:1 |
| On-accent on accent | 7.7:1 | 6.4:1 |
| Keycap vs shell | 1.39:1 | 1.24:1 (was 1.10:1 before the shell/special-key tone fix) |

- Light theme: shell is 5% darker and special keys 12% darker than the raw M3 roles so keycaps and special keys no longer melt into the shell.
- Key width at 320/360/411dp with 3dp gap: ~25 / 29 / 34dp (height 44–58dp).
- Key height is clamped so rows + suggestions never exceed ~55% of the screen height (landscape, split-screen).
- Not yet verified on a device: backdrop-blur masking, popup positions on the top row, and real input latency. Check these on hardware.
