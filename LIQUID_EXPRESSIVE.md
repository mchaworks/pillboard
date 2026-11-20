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
