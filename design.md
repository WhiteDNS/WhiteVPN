# Design — WhiteDNS VPN

A locked visual system for the Android app. The Kotlin palette in
`DashboardViews.kt` and the day/night Android colour resources are the runtime
sources; `tokens.css` is the portable export.

## Genre

Modern-minimal, technical, and tactile. Written for everyday users, with enough
instrument detail for the roughly half of users who rely on advanced controls.

## Macrostructure family

- App pages: **Workbench** — one dominant task, live state directly underneath,
  then ruled controls. Cards are reserved for containment, not every row.
- VPN surface: asymmetrical connection field · live metric strip · settings list
  · first-class Advanced disclosure.
- Subscriptions surface: edge-aligned heading · ruled source list · inline actions.
- Content/marketing pages: not in this redesign.

## Navigation and close

- Navigation: **N9 edge discipline**, adapted to a native, edge-to-edge bottom
  rail. No floating pill or detached glass dock.
- Legal close: **Ft2 single line** — one quiet copyright/link row, no footer card.

## Theme

Custom vibe: **technical, fluid, fresh, restrained**.

### Light

- `--color-paper` `oklch(97.5% 0.010 165)` / Android `#F1F9F5`
- `--color-paper-2` `oklch(96.5% 0.012 165)` / Android `#ECF6F1`
- `--color-paper-3` `oklch(93.5% 0.016 165)` / Android `#E0EDE7`
- `--color-ink` `oklch(18% 0.018 165)` / Android `#0A1410`
- `--color-ink-2` `oklch(44% 0.020 165)` / Android `#495650`
- `--color-rule` `oklch(82% 0.020 165)` / Android `#B9C8C1`
- `--color-accent` `oklch(51% 0.145 165)` / Android `#007E50`
- `--color-focus` `oklch(58% 0.180 165)`

### Dark

- `--color-paper` `oklch(15% 0.018 165)` / Android `#050E09`
- `--color-paper-2` `oklch(21% 0.020 165)` / Android `#0F1B16`
- `--color-paper-3` `oklch(24% 0.022 165)` / Android `#15231D`
- `--color-ink` `oklch(94% 0.012 165)` / Android `#E4EEE9`
- `--color-ink-2` `oklch(72% 0.018 165)` / Android `#9BA8A2`
- `--color-rule` `oklch(32% 0.025 165)` / Android `#273730`
- `--color-accent` `oklch(72% 0.130 165)` / Android `#3FBE90`
- `--color-focus` `oklch(78% 0.160 165)`

Semantic amber and red remain available for progress and failure, always paired
with text or a mark so colour is never the only signal.

## Typography

- Display: Android `sans-serif-condensed`, bold, roman.
- Body: Android `sans-serif`, regular; bold only for hierarchy.
- Data: Android `monospace`, bold, limited to the route instrument and metric
  strip so it stays an outlier rather than becoming a third body face.
- Portable mapping: IBM Plex Sans Condensed · IBM Plex Sans · IBM Plex Mono.
- Scale: 12 · 14 · 16 · 20 · 28 · 36 sp. No italic headings.

## Spacing

4 dp base: 4 · 8 · 12 · 16 · 24 · 32 · 40 · 64. Screen gutters are
20–24 dp; touch targets never fall below 44 dp.

## Motion

- State colour/geometry transition: 420 ms maximum, decelerating.
- Functional current only while connecting or disconnecting; connected is still.
- Press feedback: immediate inset/scale treatment.
- Respect Android's system animator setting; disabled animation renders final
  state immediately.

## Microinteractions stance

- Silent success when the resulting state is already visible.
- Focus is immediate and visibly outlined.
- Errors name the failure and retain the existing recovery action.
- No decorative infinite loops, ambient blobs, bounce, or glow.

## CTA voice

- Primary: the connection field itself; a single explicit verb, `Connect`,
  `Disconnect`, or `Try again`.
- Secondary: text or compact outlined actions. Avoid filled full-width bars unless
  the action is the screen's one primary job.

## What every surface shares

- Green signal accent used sparingly.
- Condensed display, sans body, mono data.
- Small radii, visible rules, asymmetrical composition.
- Edge-to-edge bottom rail and the same focus/pressed/disabled language.

## What surfaces may vary

- VPN may use one state-responsive current line.
- Subscriptions uses a quiet ruled list and no ambient motion.
- Advanced controls may use native Material fields and switches, but inherit the
  tighter radius and ruled grouping.

## Exports

### tokens.css

The canonical portable file is [`tokens.css`](tokens.css).

### Tailwind v4 `@theme`

```css
@theme {
  --color-paper: oklch(97.5% 0.010 165);
  --color-paper-2: oklch(96.5% 0.012 165);
  --color-paper-3: oklch(93.5% 0.016 165);
  --color-ink: oklch(18% 0.018 165);
  --color-ink-2: oklch(44% 0.020 165);
  --color-rule: oklch(82% 0.020 165);
  --color-accent: oklch(51% 0.145 165);
  --color-focus: oklch(58% 0.180 165);
  --font-display: "IBM Plex Sans Condensed", sans-serif;
  --font-body: "IBM Plex Sans", sans-serif;
  --font-outlier: "IBM Plex Mono", monospace;
  --spacing-xs: 0.5rem;
  --spacing-sm: 0.75rem;
  --spacing-md: 1rem;
  --spacing-lg: 1.5rem;
  --spacing-xl: 2rem;
  --ease-out: cubic-bezier(0.16, 1, 0.3, 1);
}
```

### DTCG `tokens.json`

```json
{
  "$schema": "https://design-tokens.github.io/community-group/format/",
  "color": {
    "paper": { "$value": "oklch(97.5% 0.010 165)", "$type": "color" },
    "paper-2": { "$value": "oklch(96.5% 0.012 165)", "$type": "color" },
    "ink": { "$value": "oklch(18% 0.018 165)", "$type": "color" },
    "ink-2": { "$value": "oklch(44% 0.020 165)", "$type": "color" },
    "rule": { "$value": "oklch(82% 0.020 165)", "$type": "color" },
    "accent": { "$value": "oklch(51% 0.145 165)", "$type": "color" },
    "focus": { "$value": "oklch(58% 0.180 165)", "$type": "color" }
  },
  "font": {
    "display": { "$value": "IBM Plex Sans Condensed", "$type": "fontFamily" },
    "body": { "$value": "IBM Plex Sans", "$type": "fontFamily" },
    "outlier": { "$value": "IBM Plex Mono", "$type": "fontFamily" }
  },
  "space": {
    "xs": { "$value": "0.5rem", "$type": "dimension" },
    "sm": { "$value": "0.75rem", "$type": "dimension" },
    "md": { "$value": "1rem", "$type": "dimension" },
    "lg": { "$value": "1.5rem", "$type": "dimension" },
    "xl": { "$value": "2rem", "$type": "dimension" }
  }
}
```

### shadcn/ui CSS variables

```css
:root {
  --background: 97.5% 0.010 165;
  --foreground: 18% 0.018 165;
  --card: 96.5% 0.012 165;
  --card-foreground: 18% 0.018 165;
  --primary: 51% 0.145 165;
  --primary-foreground: 98% 0.008 165;
  --muted: 93.5% 0.016 165;
  --muted-foreground: 44% 0.020 165;
  --border: 82% 0.020 165;
  --input: 82% 0.020 165;
  --ring: 58% 0.180 165;
  --radius: 0.75rem;
}
```
