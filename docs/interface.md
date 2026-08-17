# Interface

## Material 3 Expressive

The UI is Material 3 Expressive with Material You dynamic colour, on `material3 1.5.0-alpha26`.

Dynamic colour is the default on Android 12+. `dynamicLightColorScheme` and
`dynamicDarkColorScheme` pull the palette the platform derived from the user's wallpaper, so the
app inherits the system's tonal palettes rather than imposing a brand colour. Verified by seeding
the system palette directly and watching every surface, container, switch and navigation
indicator re-tint with contrast intact:

```bash
adb shell "settings put secure theme_customization_overlay_packages \
  '{\"android.theme.customization.system_palette\":\"B33B15\",\
    \"android.theme.customization.theme_style\":\"VIBRANT\"}'"
adb shell cmd uimode night yes    # dark variant
```

Below API 31 the fallbacks are Google-authored rather than hand-picked:
`expressiveLightColorScheme()` and the baseline `darkColorScheme()`. Eyeballing 48 role colours
reliably produces contrast failures. Material ships no `expressiveDarkColorScheme()` yet, which is
where the asymmetry comes from; devices on API 31+ see neither.

The first pass at this was dynamic colour plus a few Expressive components, and it still looked
like Material 2 with rounded corners. What carries the language:

| Element | Treatment |
|---|---|
| Hero badge | A `Morph` between `MaterialShapes.Cookie9Sided` and `MaterialShapes.Sunny`, driven by connection state on a low-stiffness spring so it overshoots and settles. State is carried by geometry, not a colour swap. |
| Connect control | `TonalToggleButton`, whose shape morphs on toggle. Tonal specifically, because the *unchecked* state is the primary call to action, and the plain variant renders unchecked on a bare surface and reads as disabled. |
| Stat tiles | Deliberate colour variety across `secondaryContainer`, `errorContainer`, `tertiaryContainer` and `surfaceContainerHighest`, at `shapes.extraLarge`. A grid of identical neutral cards is what Expressive moves away from. |
| Navigation | `ShortNavigationBar`, the Expressive bar, label beside the icon. |
| Loading | `ContainedLoadingIndicator`, a morphing shape sequence rather than a spinning arc. |
| Filter toggle | `ToggleButton` in place of `FilterChip`. |
| Motion | `MotionScheme.expressive()` across the whole theme. |

`MorphShape` in `DashboardScreen.kt` adapts a `Morph` to a Compose `Shape`. It measures the morph
path's real bounds and scales and centres from those, rather than assuming a normalised 0..1 box,
which keeps every shape in the catalogue correct including the asymmetric ones.

### Why an alpha

`MaterialExpressiveTheme` and `MotionScheme` exist in stable 1.4.0, but `MaterialShapes`,
`ButtonGroup`, `FloatingToolbar`, `LoadingIndicator`, `SplitButton` and `ToggleButton` only appear
from 1.5.0-alpha. The alpha declares compose-foundation/ui 1.12.0-beta01, which the BOM's stable
1.12.0 already satisfies, so material3 is the only pre-release piece and the rest of Compose stays
stable. Drop to 1.4.0 and the theme still works; the shape morph and the toggle buttons are what
would need replacing.

## Making the screens explain themselves

The app was built outward from the packet path, and it showed. Every screen was a thin rendering
of whatever state the core exposed, and none of them explained themselves. The firewall was the
clearest case: an `App | Wi-Fi | Data` header over a list of bare switches, with nothing saying
what the screen did or which way the switches ran. They *block*, which is the opposite of the
usual "on means enabled" reading, and there was no safe way to guess.

| | Before | After |
|---|---|---|
| Screens with a title | 0 of 5 | 5 of 5, each with a line saying what it is for |
| Feedback on an action | none, no `Snackbar` anywhere | every mutation acknowledged; firewall toggles undoable |
| Destructive actions | fired on one tap, silently | confirmation naming the row count and what survives |
| Firewall rule state | switch position only | stated in words per row, in the row's own colour |
| Log verdict | a 10 dp coloured dot | icon plus colour plus text in the row description |
| Hardcoded UI strings | 15 | 0 |

Three features existed in the repositories with no way in at all. They have one now: filter lists
with sizes, last-updated and a manual refresh (`settings_lists` had been defined in `strings.xml`
and referenced nowhere); an editable resolver (`setDohUrl` and `setUpstreamUdp` were wired but the
screen rendered static text); and un-pinning an app that once rejected our certificate
(`clearPinnedUids` was never called from anywhere, so the bypass was permanent). The log's kind
filter was the fourth: `setKind` had no caller.

### Two bugs this surfaced

Overrides on rows that have no domain. The sheet offered "Always allow" on every log row and
stored the row's label. A `tcp` row is labelled `address:port` and a content-filter row with a
full URL, and user rules match domains, so allowing a TCP row wrote a rule that could never match,
listed it in Settings as though it were in force, and left the user believing they had unblocked
something. `overrideTarget` now extracts a host or declines.

A list that reordered under the finger. Grouping blocked apps at the top of the firewall
looked right in a screenshot and was wrong in the hand: a `LazyColumn` holds its scroll offset
while items are inserted above it, so toggling a switch slid the row just touched out of the
viewport. Caught on the emulator, not in review. The order is now always alphabetical and the
grouping is a filter chip.

A third bug came out of the lint gate rather than the UI work: the Quick Settings tile called
`startActivityAndCollapse(Intent)`, which throws `UnsupportedOperationException` on Android 14
against a `targetSdk` of 34. It takes the `PendingIntent` overload now, behind a version guard.

### Verified

- **101 Rust, 58 Kotlin unit and 36 instrumented tests pass**, the latter two up from 24 and 16.
  The new ones cover the firewall wording and undo, both confirmation dialogs, resolver
  validation, day bucketing across local midnight, override targeting, and the per-app
  interception switches being inert while the master switch is off.
- Every screen was captured on the emulator in light and dark, plus the paused dashboard, the
  override sheet, both dialogs, search, and an empty log. The reordering bug above was found that
  way and nowhere else.
- RTL was checked with `adb shell cmd locale set-app-locales io.omnishield --locales ar-EG`, since
  the emulator image ignores `debug.force_rtl`.
- Idle CPU with the screen off is unchanged from the [efficiency pass](efficiency.md), which is
  the check that matters here. A UI pass is exactly where a stray eager collector or a per-frame
  binder call gets reintroduced.

The structural rules that came out of this work, including which pieces of UI logic carry real
weight and why the firewall list must never reorder, are in
[Architecture](architecture.md#screens).
