import { createTheme, type MantineColorsTuple } from '@mantine/core';

/** Burnt copper — path / selection signal. Not indigo. */
const copper: MantineColorsTuple = [
  '#fff7ed',
  '#ffedd5',
  '#fed7aa',
  '#fdba74',
  '#fb923c',
  '#f97316',
  '#ea580c',
  '#c2410c',
  '#9a3412',
  '#7c2d12',
];

/** Moss — validated / next-hop markers. */
const moss: MantineColorsTuple = [
  '#f0f7f3',
  '#dceee4',
  '#b9dcc9',
  '#8fc4a8',
  '#5ea683',
  '#3d8b68',
  '#2f6f54',
  '#285946',
  '#22483a',
  '#1c3b30',
];

export const walkthroughTheme = createTheme({
  primaryColor: 'copper',
  primaryShade: { light: 7, dark: 5 },
  colors: {
    copper,
    moss,
  },
  fontFamily:
    '"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, sans-serif',
  fontFamilyMonospace:
    '"IBM Plex Mono", "SFMono-Regular", Consolas, "Liberation Mono", monospace',
  headings: {
    fontFamily:
      '"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, sans-serif',
    fontWeight: '600',
    sizes: {
      h1: { fontSize: '2rem', lineHeight: '1.15' },
      h2: { fontSize: '1.35rem', lineHeight: '1.2' },
      h3: { fontSize: '1.1rem', lineHeight: '1.25' },
      h4: { fontSize: '0.95rem', lineHeight: '1.3' },
    },
  },
  defaultRadius: 2,
  radius: {
    xs: '2px',
    sm: '3px',
    md: '4px',
    lg: '6px',
    xl: '8px',
  },
  spacing: {
    xs: '6px',
    sm: '10px',
    md: '14px',
    lg: '20px',
    xl: '28px',
  },
  fontSizes: {
    xs: '11px',
    sm: '13px',
    md: '14px',
    lg: '16px',
    xl: '18px',
  },
  components: {
    Button: {
      defaultProps: {
        radius: 'xs',
      },
      styles: {
        root: {
          fontWeight: 600,
          letterSpacing: '0.01em',
        },
      },
    },
    SegmentedControl: {
      defaultProps: {
        radius: 'xs',
      },
    },
    TextInput: {
      defaultProps: {
        radius: 'xs',
      },
    },
    Textarea: {
      defaultProps: {
        radius: 'xs',
      },
    },
    Select: {
      defaultProps: {
        radius: 'xs',
      },
    },
    Badge: {
      defaultProps: {
        radius: 'xs',
      },
    },
    Alert: {
      defaultProps: {
        radius: 'xs',
      },
    },
    Modal: {
      defaultProps: {
        radius: 'xs',
      },
    },
    Tabs: {
      styles: {
        tab: {
          fontWeight: 600,
        },
      },
    },
  },
});
