# Design system

The window has one look, defined in `com.routegenie.ui.design`. It follows the IDE theme (IntelliJ Light, New UI and Classic UI
dark, and third-party themes such as Dracula, One Dark or Material), uses the editor color scheme for code, and stays sharp on
HiDPI screens. This page is for contributors: the rules, the pieces and how they are checked.

## Rules

1. **No color outside `ui/design`.** Every color comes from `Palette`; the numbers live in `PaletteSpec`. `DesignCentralizationTest`
   fails on `Color(...)`, `JBColor(...)`, `Color.WHITE`, `Gray._80` and 6-digit hex literals in any other UI source. Nothing under `ui` is exempt.
2. **No raw size.** Spacing, radius, stroke, icon and badge sizes are `DesignTokens` constants in unscaled pixels; scale with
   `JBUI`/`Space.px`. Spacing is a 4 px grid (`Space.XS=4, S=8, M=12, L=16, XL=24`); `Space.HALF=2` is the one exception.
3. **No UI string in design code.** Components take text from the caller, who reads it from the bundle.
4. **Neutrals are inherited, never typed.** Surfaces, borders, text, links, hover and selection are read from the running theme
   every time they are used (`JBColor.lazy`), so a theme switch or a third-party theme applies without a restart.
5. **Own colors come as light/dark pairs with a guaranteed contrast** and may be overridden by a theme (see below).
6. **Code areas use the editor color scheme** (`EditorColorsManager`), never colors of ours. `Type.mono()` is the editor font at
   the UI size for values the user copies.
7. **Paint code allocates nothing per row.** Fonts, strokes, composites and badge icons are cached; `BadgePainter` restores every
   Graphics setting it changes. `DesignLeakAndSpeedTest` measures bytes per badge paint and the 16 ms render budget on 50,000 rows.
8. **Every Disposable has a parent.** `LoadingRow`/`LoadingState` take the parent that owns their spinner.

## The pieces

| Piece | What it is |
| --- | --- |
| `DesignTokens` | `Space`, `Radius`, `Stroke`, `IconSize`, `Badge`, `State` (tint alphas, focus ring), `Rows`, `Type` (type scale from the IDE label font) |
| `Palette` | `surface`, `raised`, `hover`, `selection`, `selectionText`, `border`, `textPrimary`, `textSecondary`, `textDisabled`, `accent`, `focus`; tones `success`, `warning`, `danger`, `info`, `neutral`; `method(HttpMethod)`, `status(code)` / `status(StatusClass)` |
| `color.ColorMath`, `color.PaletteSpec` | pure Kotlin (no Swing): WCAG contrast, blending, the palette numbers and the reference surfaces |
| `MethodBadge.of(method, state)` | cached `Icon`, fixed width so paths line up; ANY is an outline; `BadgeState` recolors it on selected rows |
| `StatusPill(code, label)`, `Chip(text, tone)`, `ChipPainter` | pills tinted by status class; tags as outlined chips (a hue-less tint would not pass the contrast check), tone chips tinted; `ChipPainter` draws chips inside renderers |
| `SectionHeader`, `Divider`, `KeyValueGrid`/`KeyValueRow` | structure of a page: title with optional trailing component, hairlines, aligned label/value rows |
| `EmptyState`, `LoadingRow`, `SkeletonRows`, `LoadingState` | full-page states and the loading placeholders (static, no timers) |
| `NoticeStrip(tone, content)` | a slim notice above content: raised background, a tone stripe on the left, a hairline below; used by the pins strip (warning) and the hidden-routes line (info) |
| `RowPainter`, `RowState` | background of a row in a custom list: hover, selection, inactive selection |
| `ToolbarStyle` | one way to create toolbars and a raised bar with a filter field |
| `RouteGenieIcons` | all icons; `/icons/design/<name>.svg` with `<name>_dark.svg` beside it |

## Color model

A **tone** is one hue with two roles: `fill` is the vivid color for dots, outlines and the tint, `text` is the variant that stays
readable on the surface and on the tinted background. Badges and pills draw `fill` at 16 percent alpha over whatever is behind
them (so they work on any surface, hover or selection) and the lettering in `text`. On a selected row the lettering takes the
theme's own selection foreground (readable whatever blue, grey or green the theme chose for selection) and the verb keeps its hue as a
stronger tint (`PaletteSpec.SELECTED_TINT_ALPHA`) behind it; `PaletteContrastTest` checks the lettering against that tint on the sample selections.
A component that paints a badge or chip uses the width its layout gave it instead of measuring again with the (possibly scaled)
Graphics, otherwise the outline overruns the component and loses its right and bottom edge.

Hues: green, blue, orange, purple, red, slate, teal, indigo, cyan. Verbs: GET green, POST blue, PUT orange, PATCH purple, DELETE
red, HEAD teal, OPTIONS indigo, TRACE slate, ANY cyan as an outline. Status: 1xx slate, 2xx green, 3xx cyan, 4xx orange, 5xx red.
Color is never the only carrier of meaning: the verb and the code are always written.

### Contract (tested without an IDE)

`PaletteContrastTest` checks, for every reference surface (light white, New UI panel, classic panel, New UI dark tool window and
editor, Darcula editor and panel, Dracula `#282A36`, One Dark `#282C34`, Material Oceanic `#263238`):

- text colors (primary, secondary, accent, every tone's text) at least 4.5:1;
- fills and the focus color at least 3:1;
- every tone's text on its own tint at least 4.5:1, on the surface, on `raised` and on `hover`;
- every swatch has a light and a different dark value.

Disabled text and hairline borders are the only exemptions (WCAG exempts disabled text; borders are decorative). If a pair fails,
change `PaletteSpec`, not the test. The measured worst ratios are written to `build/reports/routegenie/design/contrast.txt`.

### Third-party themes

Neutrals follow the theme through the platform's own keys (`Tree.background`, `Panel.background`, `Label.foreground`,
`Component.infoForeground`, `Tree.selectionBackground`, `List.hoverBackground`, `Borders.color`, link and focus colors), which is
what Dracula, Material Theme UI and similar themes change. Tones are ours because the platform has no color with a guaranteed
contrast on a tinted fill; a theme that wants other hues sets `RouteGenie.<tone>.fill` / `RouteGenie.<tone>.text`
(for example `RouteGenie.green.fill`) in its theme JSON, which `JBColor.namedColor` picks up.

## Icons

16 px action icons and 32 px empty-state illustrations, original geometry, stroke 1.5, rounded caps, grey for actions (`#6C707E`
light, `#CED0D6` dark), blue or red only where color carries meaning. A light file and its `_dark` file have identical geometry
and differ only in color (`DesignComponentsTest` compares them). The plugin mark has `routeGenie_dark.svg` next to it. Gutter
icons are 12 px and keep their own files.

## How a window is checked

- `DesignGalleryTest` paints the window, tree rows in every state, all components and the empty/loading pages to
  `build/reports/routegenie/design/*.png` for light, dark and Dracula-, One Dark- and Material-style samples, each at 1x and 2x.
  The dark runs switch the real look and feel when the test IDE allows it (`notes-*.txt` says what was used) and write the sample
  theme's color keys over it, which is what a third-party theme is.
- `DesignLeakAndSpeedTest` proves disposal (weak references, `LeakHunter`, heap growth), the render budget over 50,000 rows and the
  allocation per badge paint.
- Real focus, hover, high-DPI screens and the New UI selection shape can only be judged in a running IDE (see
  `docs/MANUAL-CHECKLIST.md`).

## Research behind the choices

Principles only; nothing is copied from any product.

- **IntelliJ Platform UI:** use `JBColor`, never `java.awt.Color`, so colors know about dark themes; read theme colors by key
  (`JBColor.namedColor`) and reuse a platform key only when its meaning fits, otherwise define a dedicated one, because a theme
  author who changes `Focus.borderColor` must not break an unrelated control. Icons are 16x16 with a 1 px transparent border
  (tool window 13x13 classic or 20x20 New UI, gutter 12x12), the visible artwork 14x14, strokes aligned to whole pixels, grey for
  actions and color only for meaning, dark variants as `<name>_dark.svg`.
  ([colors](https://plugins.jetbrains.com/docs/intellij/platform-theme-colors.html),
  [icons](https://plugins.jetbrains.com/docs/intellij/icons-style.html),
  [UI FAQ](https://plugins.jetbrains.com/docs/intellij/ui-faq.html),
  [theme metadata](https://plugins.jetbrains.com/docs/intellij/themes-metadata.html),
  [layout](https://plugins.jetbrains.com/docs/intellij/layout.html): horizontal insets smaller than vertical group gaps, no
  insets that suggest false grouping.)
- **API clients (Postman, Insomnia, Bruno, Hoppscotch, the IDE's HTTP client):** the shared language is a short colored verb next
  to the path, a status chip colored by response class (green success, amber or orange client error, red server error, grey
  informational), single-line list rows, and empty states with one sentence of help. The general web search for these products
  returned design write-ups rather than their documentation, so only the common conventions are taken, not any product's exact
  styling. One Yaak feedback thread names the trade-off we adopted: method colors help catch a wrong verb, but they must not
  clash with status colors. Both use tinted shapes with text in the hue, but the verb is a fixed-width tag and the status is a
  rounded pill that carries the code, so the two are never confused.
  ([status color conventions](https://supabase.com/design-system/docs/fragments/status-codes),
  [method colors discussion](https://yaak.app/feedback/posts/support-colors-for-http-method-in-sidebar))
- **Contrast:** WCAG 2.x relative luminance and contrast ratio, 4.5:1 for normal text and 3:1 for graphics.
