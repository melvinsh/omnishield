# Interface

Five tabs, one file each in `ui/`: Shield, Log, Firewall, Web, Settings.

## Every screen states its own purpose

Each screen is wrapped in `ui/components/ScreenScaffold`, which supplies the title bar (a title
plus one line saying what the screen is for) and the `SnackbarHost` reachable through
`LocalSnackbar`. A screen that skips it renders with no title and throws on its first snackbar.
Both failures are deliberate: a blocker is full of controls whose effect is invisible, so every
screen has to say what it governs and every action has to be answered.

`MainActivity`'s `Scaffold` owns the navigation bar and zeroes its own `contentWindowInsets`,
because each `TopAppBar` applies the status-bar inset itself. Padding both double-pads.

## Three pieces of UI logic that carry weight

- **`ruleSummary` (firewall) and `overrideTarget` (log)**, both unit-tested. The firewall's
  switches *block*, which inverts the usual "on means enabled" reading, so each row states its rule
  in words and the switch is a second representation of it rather than the only one.
  `overrideTarget` decides whether a log row has a domain to override at all: a `tcp` row is
  labelled `address:port` and an `http` row with a full URL, and a user rule keyed on either
  literal string would be stored, listed in Settings as though in force, and match nothing.
- **The firewall list is alphabetical and never reorders itself.** A `LazyColumn` holds its scroll
  offset while items are inserted above it, so hoisting blocked apps to the top slides the row the
  user just touched out of the viewport. Finding blocked apps is a filter chip instead.
- **Filter lists are not pushed into a running core.** `FilterRefreshWorker` explains why, so
  "Refresh now" in Settings says the lists load on the next connect rather than implying an effect
  it does not have.

## Material 3 Expressive

The UI is Material 3 Expressive with Material You dynamic colour, on `material3 1.5.0-alpha26`.

Dynamic colour is the default on Android 12+. `dynamicLightColorScheme` and
`dynamicDarkColorScheme` pull the palette the platform derived from the user's wallpaper, so the
app inherits the system's tonal palettes rather than imposing a brand colour. To check it against
a seeded palette:

```bash
adb shell "settings put secure theme_customization_overlay_packages \
  '{\"android.theme.customization.system_palette\":\"B33B15\",\
    \"android.theme.customization.theme_style\":\"VIBRANT\"}'"
adb shell cmd uimode night yes    # dark variant
```

Below API 31 the fallbacks are Google-authored rather than hand-picked:
`expressiveLightColorScheme()` and the baseline `darkColorScheme()`. Hand-tuning 48 role colours
reliably produces contrast failures. Material ships no `expressiveDarkColorScheme()` yet, which is
where the asymmetry comes from; devices on API 31+ see neither.

Dynamic colour plus a few Expressive components still looks like Material 2 with rounded corners.
What carries the language:

| Element | Treatment |
|---|---|
| Hero badge | A `Morph` between `MaterialShapes.Cookie9Sided` and `MaterialShapes.Sunny`, driven by connection state on a low-stiffness spring so it overshoots and settles. State is carried by geometry, not a colour swap. |
| Connect control | `TonalToggleButton`, whose shape morphs on toggle. Tonal specifically, because the *unchecked* state is the primary call to action, and the plain variant renders unchecked on a bare surface and reads as disabled. |
| Stat tiles | Colour variety across `secondaryContainer`, `errorContainer`, `tertiaryContainer` and `surfaceContainerHighest`, at `shapes.extraLarge`. A grid of identical neutral cards is what Expressive moves away from. |
| Navigation | `ShortNavigationBar`, the Expressive bar, label beside the icon. |
| Loading | `ContainedLoadingIndicator`, a morphing shape sequence rather than a spinning arc. |
| Filter toggle | `ToggleButton` in place of `FilterChip`. |
| Motion | `MotionScheme.expressive()` across the whole theme. |

`MorphShape` in `DashboardScreen.kt` adapts a `Morph` to a Compose `Shape`. It measures the morph
path's real bounds and scales and centres from those rather than assuming a normalised 0..1 box,
which keeps every shape in the catalogue correct including the asymmetric ones. It has to stay a
`data class`; as a plain class, every recomposition defeats `Modifier.clip`'s outline cache.

### Why a pre-release material3

`MaterialExpressiveTheme` and `MotionScheme` exist in stable 1.4.0, but `MaterialShapes`,
`ButtonGroup`, `FloatingToolbar`, `LoadingIndicator`, `SplitButton` and `ToggleButton` only appear
from 1.5.0-alpha. The alpha declares compose-foundation/ui 1.12.0-beta01, which the BOM's stable
1.12.0 already satisfies, so material3 is the only pre-release piece and the rest of Compose stays
stable. Dropping to 1.4.0 leaves the theme working; the shape morph and the toggle buttons are
what would need replacing.

## Accessibility

Nothing conveys meaning by colour alone. The log's verdict is an icon, a colour and text in the
row's content description. The firewall states each rule in words. Every icon-only control has a
`contentDescription`, and the shield badge announces the blocked count with the tunnel's actual
state rather than reusing the connected string.

Layouts are checked in RTL with:

```bash
adb shell cmd locale set-app-locales io.omnishield --locales ar-EG
```

The emulator images ignore `debug.force_rtl`, so this is the route that works.
