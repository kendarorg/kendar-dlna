/*
 * Vertical drag handle that resizes a pane through a CSS variable on <html> (desktop only, hidden by CSS under 860px).
 * Props: cssVar (e.g. '--side-w'), pane (selector of the element measured at drag start), prefKey, min, max, fallback (px).
 * Drag or arrow keys resize, double-click / Home resets; the width is kept in localStorage.
 */
import { prefs } from '../player/index.js';
import { clampWidth, dragWidth, keyWidth } from '../core/resize.js';

export const PaneResizer = {
  name: 'PaneResizer',
  props: {
    cssVar: { type: String, required: true },
    pane: { type: String, required: true },
    prefKey: { type: String, required: true },
    min: { type: Number, default: 160 },
    max: { type: Number, default: 560 },
    fallback: { type: Number, default: 240 },
    label: { type: String, default: '' }
  },
  data() { return { width: this.fallback, dragging: false }; },
  mounted() {
    const stored = prefs.get(this.prefKey, null);
    if (stored !== null) this.apply(clampWidth(stored, this.min, this.max), false);
    else this.width = this.measure() || this.fallback;
  },
  methods: {
    measure() {
      const el = document.querySelector(this.pane);
      return el ? Math.round(el.getBoundingClientRect().width) : 0;
    },
    apply(w, save) {
      this.width = w;
      document.documentElement.style.setProperty(this.cssVar, w + 'px');
      if (save) prefs.set(this.prefKey, w);
    },
    reset() {
      document.documentElement.style.removeProperty(this.cssVar);
      prefs.remove(this.prefKey);
      this.$nextTick(() => { this.width = this.measure() || this.fallback; });
    },
    down(e) {
      if (e.button !== undefined && e.button !== 0) return;
      const startX = e.clientX;
      const start = this.measure() || this.width;
      this.dragging = true;
      const move = ev => this.apply(dragWidth(start, startX, ev.clientX, this.min, this.max), false);
      const up = () => {
        this.dragging = false;
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', up);
        window.removeEventListener('pointercancel', up);
        prefs.set(this.prefKey, this.width);
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
      window.addEventListener('pointercancel', up);
      e.preventDefault();
    },
    key(e) {
      if (e.key === 'Home') { this.reset(); e.preventDefault(); return; }
      const w = keyWidth(this.measure() || this.width, e.key, this.min, this.max, this.fallback);
      if (w !== null) { this.apply(w, true); e.preventDefault(); }
    }
  },
  template: `
    <div class="pane-resizer" :class="{ dragging: dragging }" role="separator" aria-orientation="vertical" tabindex="0"
         :aria-label="label" :aria-valuenow="width" :aria-valuemin="min" :aria-valuemax="max"
         @pointerdown="down" @dblclick="reset" @keydown="key"></div>`
};
