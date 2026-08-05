import { createTheme, type MantineColorsTuple } from '@mantine/core';

const blue: MantineColorsTuple = [
  '#edf6ff', '#d7ebff', '#b5d8ff', '#8cc2ff', '#5aa7fa',
  '#2888e8', '#0071e3', '#005bb5', '#00478d', '#00386f',
];

const green: MantineColorsTuple = [
  '#effbf3', '#d9f5e2', '#b4e9c7', '#82d8a1', '#4dc279',
  '#26a85d', '#16803c', '#106831', '#0d5228', '#083f1f',
];

export const walkthroughTheme = createTheme({
  primaryColor: 'blue',
  primaryShade: { light: 6, dark: 5 },
  colors: { blue, green },
  fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
  fontFamilyMonospace: '"SFMono-Regular", Consolas, "Liberation Mono", monospace',
  headings: {
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    fontWeight: '650',
    sizes: {
      h1: { fontSize: '2rem', lineHeight: '1.15' },
      h2: { fontSize: '1.35rem', lineHeight: '1.2' },
      h3: { fontSize: '1.1rem', lineHeight: '1.25' },
      h4: { fontSize: '0.95rem', lineHeight: '1.3' },
    },
  },
  defaultRadius: 'md',
  radius: {
    xs: '4px',
    sm: '6px',
    md: '8px',
    lg: '8px',
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
      styles: { root: { fontWeight: 600, letterSpacing: '0' } },
    },
  },
});
