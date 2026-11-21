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

## Phase 5 — evolution toward the validation reference

The validation mock (dark/light, 320–411dp) is the primary visual target. The old design is kept wherever it already matched; nothing is copied pixel by pixel.

| Element | Decision | What changed |
|---|---|---|
| QWERTY layout, bottom row `?123 , space . Enter`, no globe | **Retained** | Familiar, simple, no behavior change |
| Typing, suggestions, backspace, shift states, number row, long-press, key preview, all settings | **Retained** | Untouched logic |
| Dynamic color / accent presets / key radius, height, spacing sliders | **Retained** | Same prefs, same defaults |
| Shell | **Refined** | Added a soft upper light layer (one drawable, no extra view) under the existing outline + top edge highlight |
| Keycaps | **Refined** | Stronger top-to-bottom sheen (light: bright top, accent-tinted underside), specular top rim (cached Paint, hidden while pressed), accent-tinted soft shadow in light theme |
| Suggestion strip | **Refined** | Full capsule radius, hairline outline |
| Enter | **Refined** | Always the accent key (reference), not only when the editor exposes an action |
| Label size (`?123`, `space`) | **Refined** | 15sp → 14sp so labels stay quieter than letters |
| Settings cards, buttons, test field | **Refined** | Same hairline outline as the shell, pill buttons, radius 28dp; live preview picks up the new keys automatically |
| Flat keycap look, flat strip | **Replaced** | Superseded by the sheen/rim/shadow treatment above |

Not in this phase: AGSL `RuntimeShader` lighting and real backdrop refraction. The shell glow is plain drawables so it works on API 26+; a shader layer should be added as an optional API 33+ enhancement after on-device profiling.

## Phase 5b — closer to iOS 26

New reference: iOS 26 keyboard. This supersedes the keycap, strip and shadow items of Phase 5.

- **Keycaps:** flat solid surfaces, no outline, no rim, no gradient, no tinted shadow (elevation 1dp only). One corner radius for all keys (default 10dp; Enter / active Shift +2dp).
- **Color:** neutral gray. Letters one step lighter, special keys one step darker, pressed = lighter (dark) / darker (light). Accent color is used only for Enter and active/locked Shift, plus the Enter pressed state.
- **Shell:** darker glass in dark theme; soft upper light kept, but toned down.
- **Suggestion strip:** no capsule; words and hairline dividers directly on the shell.

## Stage 1 — implementation map

| Concern | Where | Notes |
|---|---|---|
| Architecture | Classic Android Views, no Compose | `PillboardService` builds `LinearLayout` rows of `KeyView` (a `TextView` subclass); no Canvas keyboard engine |
| Tokens | `KbTokens` | Single source for color, geometry, elevation, motion; resolved from the (dynamic) Material theme |
| Shell | `KbTokens.shell()` set as `root.background` | Gradient + hairline + soft upper light, one drawable |
| Blur | `PillboardService.applyBlur()` | `setBackgroundBlurRadius` (API 31+) with `isCrossWindowBlurEnabled` listener; 3 tiers (blur / tint fallback / solid) |
| Keycaps | `KeyView` | `GradientDrawable` background, solid flat fill; styles LETTER / SPECIAL / ACCENT / ACTIVE |
| Motion | `KeyView`, `Motion.kt` | `ViewPropertyAnimator` + analytic `SpringInterpolator`; no `dynamicanimation` dependency |
| Suggestions | `PillboardService.buildStrip()` / `updateStrip()` | 3 slots, fade/translate on change |
| Popups | `KeyPreview`, long-press alternates | One reusable `PopupWindow` |
| Settings | `SettingsActivity`, `SettingsUi` | Live preview reuses `KeyView` + `KbTokens` |
| Min API / target | 26 / 35 | Blur 31+, AGSL 33+ (not used yet) |

## Stage 2 — core material rendering

- **Tonal hierarchy:** shell (glass) > letter keys (lightest) > special keys incl. space (one step darker) > accent (Enter, active Shift).
- **Space bar** is now a special-tone key, as in iOS 26.
- **Geometry:** vertical key margin is `gap + 2dp` (was `gap + 1dp`) so rows breathe more than columns; the key-height clamp accounts for it. The settings preview uses the same spacing.

## Stage 3 — Liquid Glass

- **Translucent container:** unchanged tiers (real blur / tint fallback / solid). Blur is requested on the IME window background only, never per key; keycaps stay opaque.
- **Renderer:** the shell is now `GlassSurfaceDrawable` (`GlassSurface.kt`), one Canvas-drawn drawable with cached Paints/shaders (no per-frame allocation), replacing the `LayerDrawable`. It also provides the rounded outline for `clipToOutline` and the window-blur mask. Popups reuse it (`Style.FLOATING`, near-opaque, no shader).
- **Edge lighting, API 33+ (AGSL, `GlassShader`):** rounded-rect SDF -> rim lit by two symmetric angular lobes (`pow(dot(n, L), 4.5)`, no direction-independent term, default axis 30 deg / 210 deg), a hairline just inside the edge, an inner glow on the lit side only, a faint per-channel offset of the hairline (simulated dispersion) and a very faint light pool near the top. Lighting model adapted from QWEA0/Liquid-Glass-Android (MIT) `GlassLensRenderer`, whose angles were measured from iOS 26 controls; its refraction/backdrop path is not used. The light leans toward the pressed key (200ms in, 520ms out); only the shell is re-recorded. Compiled once per drawable; a compile failure or a software canvas silently falls back.
- **Edge lighting, fallback (API 26-32, or "Liquid light" off):** the same layers as gradients: soft top light, a diagonal specular rim stroke (bright top-left -> clear -> faint bottom-right) and the outer hairline. No animation, no invalidation.
- **Honesty note:** this is simulated optics from the shell's own geometry. It does not sample or refract the app behind the keyboard; only the compositor's window blur sees that.
- **Blur radius:** `min(28dp, 110px)`, following the Android guidance (~80px reads well, >150px costs performance).
- **Setting:** Settings > Appearance > "Liquid light" (`glass_fx`, Android 13+).
- **Fixed:** a trailing comment in `PillboardService.key()` swallowed the closing brace of the margins lambda (build break).
- **Fallback:** if `isCrossWindowBlurEnabled` is false (battery saver, developer option, ROM policy) the shell becomes a near-opaque tint; the listener re-applies when it flips.
- **Verification aid:** Settings > Appearance > "Blur status" shows the real state: `Active`, `Available, off`, `Blocked by system` or `Needs Android 12+`.
- **Not verified here:** actual IME-window blur masking and the ROM's behavior must be checked on the device. Blur-behind (`FLAG_BLUR_BEHIND`) is intentionally not used: per the Android window-blur docs it blurs the whole screen behind the window, not just the window's own backdrop.

### Research notes (references actually read)

- **Android window blurs (AOSP / developer docs):** background blur is masked by the window background drawable's outline; the window must be translucent because the blur is drawn under the window surface (`PillboardService.applyBlur()` now also sets `PixelFormat.TRANSLUCENT` when blur is active); the system may disable blurs at runtime (battery saver, certain video, developer override) and some GPUs do not support them, hence the alpha tiers and the listener.
- **Liquid Glass libraries (QWEA0/Ibrohim-18, MIT; Abdullajon1881, Apache-2.0):** both refract content recorded from their *own view tree* (parent / provider layout). An IME window cannot record the app behind it, so true refraction of app content is not possible here; only the compositor's window blur sees it. Their SDF lens would only distort the keyboard's own keys. What transfers: the 3-tier model (AGSL API 33+, blur 31-32, scrim below) which Pillboard already follows, and rim light / specular shading, which needs no backdrop sampling. Neither is added as a dependency (NDK, extra weight).

### Research notes, Stage 3 re-read

Read this round: Android window-blur docs (background blur vs blur-behind, translucent window required, background drawable outline masks the blur, system can disable blur at runtime, more opaque fallback), AGSL `RuntimeShader` usage docs (Paint shader is the cheap path; `RenderEffect` shader is costlier and only for affecting child content), Prismal `TECHNICAL.md` (SDF mask, Schlick Fresnel, two Blinn-Phong lobes, per-channel radial offset gated to the edge), QmDeve AndroidLiquidGlassView (MIT, no effects below Android 13), Haze liquid-glass docs (refraction/dispersion/specular/ambient parameters, tinted-fill + radial-highlight fallback), Material 3 Expressive overview and Material Components 1.14 release notes. The `Abdullajon1881/LiquidGlass`, `Ibrohim-18/liquid-glass-android` and `PrismalAGSL` pages were not reachable from the build sandbox this round; conclusions about them come from the earlier read above.

What transferred to Pillboard: SDF rim, Fresnel-style two-lobe lighting, edge-gated dispersion, tinted-gradient fallback. What did not: texture sampling of the backdrop (needs a capturable background), GL contexts, extra dependencies. Material Components 1.14 adds Expressive themes but the Views library has no keyboard-relevant component, so the M3E principles (spring press, shape morph, tonal hierarchy) stay implemented in `KeyView`/`KbTokens` and the dependency is unchanged.

- **Accessibility:** Android's "High contrast text" is treated as Reduce Transparency: solid shell, no blur. System animation scale 0 disables the light motion.
- **Reference repo read (zip supplied):** QWEA0/Liquid-Glass-Android: lens pipeline (blur -> SDF refraction -> dispersion -> two-lobe rim), per-pixel adaptive tint, gravity-sensor light, accessibility fallbacks. Not adopted: backdrop capture/refraction (IME cannot record the app behind it), sensor light (extra battery for an IME), NDK blur.

## Stage 4 — refraction (Liquid lens)

- **What is real:** the key-preview popup is a glass lens (`LensView`, `GlassLens.kt`, API 33+). The keyboard window is recorded into a `RenderNode` magnified 1.18x around the pressed key, then `RenderEffect.createRuntimeShaderEffect` runs an AGSL shader: rounded-rect SDF, inverse-power bevel profile, samples pulled inward along the edge normal (compressed mirror ring at the rim), per-channel refraction amount (dispersion fringe), translucent key-tone body, and the shell's two-lobe rim light.
- **What it refracts:** only Pillboard's own keys. The app behind the IME is not sampled and cannot be; the shell stays blur + simulated lighting.
- **Cost:** one recording of the (display-list) keyboard per lens frame while a preview is visible, one re-record 80ms after the press scale settles; the effect is rebuilt only when the lens size changes. No bitmaps, no blur per key.
- **Fallbacks:** API < 33, software canvas, shader compile failure, or Settings > Appearance > "Liquid lens" off -> the original bubble preview (or a plain tile if drawn without hardware).
- **Not verified on device:** recording `View.draw()` of the IME decor into a `RenderNode`, rim/refraction strength (`bevel` 12dp, `refractPx` 9dp, `dispersion` 0.12, `MAG` 1.18), and cost on low-end GPUs. Tune those constants first if it looks off.

## Stage 4 (refraction on the shell) + Stage 5 (shape morph) + iOS special keys

- **Refraction, shell (API 33+, setting "Liquid refraction", `refract`):** `GlassRefraction.kt`. A `RenderEffect` AGSL shader on the shell view re-samples the rendered shell layer (glass + keys) inside a 16dp bevel band, pulled inward along the SDF normal, with a per-channel fringe (dispersion 0.14). The outer ~3dp stay untouched so rim light/hairline remain. Limit: it refracts Pillboard's own layer only; the app behind is seen only through window blur.
- **Reflection on the shell:** AGSL diagonal gloss streak (+ faint twin) that slides with the light, and a glow under the pressed key that shows in the gaps between keys. API 26-32: gradient sheen. Rim intensity raised.
- **Shape morph (no new dependency):** each key has four independent corner radii. Press: corners near the finger swell, far ones tighten. Release/Shift change: all corners spring back with a small undershoot. Press shrink is a fixed ~4dp per axis. The preview bubble/lens springs up out of the pressed key (lens magnification eases 1.0 -> 1.18). Suggestion words: old word drifts up, new one rises on a spring.
- **Bug fixed:** Shift colour morph fed spring values >1 to `ArgbEvaluator` (channel overflow); now clamped. Pressed icon on the inverted Shift was invisible; fixed.
- **iOS special keys:** SF-style shift / shift.fill / caps-lock / delete icons; labels `123`, `ABC`, `return` (editor actions: Go/Search/Send/Done/Next as words); Shift ON and LOCK both inverted (LOCK = underlined arrow); special keys flip to the letter-key tone while pressed.
- **Unverified (never compiled/run):** check AGSL compile, refraction look at the shell edge, tunables in `GlassRefraction.kt` (`BEVEL_DP`, `REFRACT_DP`, `DISPERSION`) and `GlassSurface.kt` (`gloss`, `touchGlow`).

## Glass rim (zip 12)

`GlassShader` (API 33+) draws a 7dp perimeter band from the shell SDF: Fresnel falloff, cyan→blue→violet→magenta→warm dispersion ramp (hue follows bevel depth and edge direction), corner-weighted intensity, thin specular ridge (key light upper-left + weaker fill), faint inner reflection. Night = additive glow, light = normal blend + faint shade for thickness. Static, no animation. Pre-33: faint sweep-gradient ring (approximation). Setting: "Glass rim" (`glass_rim`, default on). Keycaps and prediction strip untouched. The QWEA0 module was not imported (it ships native code); only the lighting/dispersion ideas were adapted.

## Liquid morph 123 <-> ABC (zip 15)

One deforming key silhouette (`MorphSilhouette` in `MorphGeometry.kt`): half-width profile W(y) joined with midpoint quadratic Béziers; the top stretches up, pinches into a neck under a rounded head, the neck travels up while it retracts, then it settles into the key. `LiquidMorphRenderer` draws it as a ViewOverlay path (no bitmap, any API); label crossfades at max stretch; both directions. `MorphTransitionController` runs one morph at a time (cancel-safe). Tunables: `MorphTokens`. Animations off = no morph.

## Liquid Motion System (zip 16)

Reusable pieces (all in the main package): `MorphTokens` (every tunable), `MorphPresets` (duration + curve per morph kind: shape / container / release), `MorphState` (`RoundRectState`), `MorphGeometry` (`MorphSilhouette`: single-shape Bézier deformation), `LiquidMorphRenderer` + `MorphTransitionController` (overlay playback, one at a time, cancel-safe), `ContainerMorph` (`morphContainer`: outline/clip/shadow of a view interpolates rect + radius). Applied: 123 <-> ABC = shape morph; long-press popup = container morph (grows out of the key, content fades in late). Not built yet: metaball merge capability (the earlier SDF version was replaced by the single-shape one), interactive/drag deformation, optical glass reacting to the silhouette.
