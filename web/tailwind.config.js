/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: {
          900: '#0b0f14',
          800: '#121821',
          700: '#1a222e',
          600: '#222c3a',
          500: '#384559',
          400: '#6d7a8c',
          300: '#a0abbd',
          200: '#d0d6e0',
          100: '#eef1f6'
        },
        accent: {
          400: '#f5b06b',
          500: '#e88f33',
          600: '#c5701a'
        }
      },
      fontFamily: {
        sans: ['InterVariable', 'Inter', 'system-ui', 'sans-serif']
      }
    }
  },
  plugins: []
};
