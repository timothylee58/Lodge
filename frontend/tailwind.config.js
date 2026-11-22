/** Tailwind = layout, spacing, colour utilities. Angular Material = components.
 *  preflight is OFF: Tailwind's global reset strips Material's button/form-field styles. */
module.exports = {
  content: ['./src/**/*.{html,ts}'],
  darkMode: 'media',
  corePlugins: { preflight: false },
  theme: { extend: {} },
  plugins: [],
};
