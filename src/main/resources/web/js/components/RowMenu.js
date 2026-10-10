/* "..." button with a small action menu (same look as the player's action menu); items: [{id, label, icon, run, disabled}]. */
import { Icon } from './icons.js';

export const RowMenu = {
  name: 'RowMenu',
  components: { Icon },
  props: {
    items: { type: Array, required: true },
    label: { type: String, default: '' },
    cls: { type: String, default: '' }
  },
  data() { return { open: false, left: 0, top: 0 }; },
  mounted() {
    this.onDoc = e => { if (this.open && !this.$el.contains(e.target)) this.close(); };
    this.onKey = e => { if (this.open && e.key === 'Escape') { this.close(true); e.stopPropagation(); } };
    document.addEventListener('mousedown', this.onDoc, true);
    document.addEventListener('keydown', this.onKey, true);
    window.addEventListener('scroll', this.onScroll, true);
    window.addEventListener('resize', this.close);
  },
  beforeUnmount() {
    document.removeEventListener('mousedown', this.onDoc, true);
    document.removeEventListener('keydown', this.onKey, true);
    window.removeEventListener('scroll', this.onScroll, true);
    window.removeEventListener('resize', this.close);
  },
  methods: {
    onScroll(e) { if (this.open && !(this.$refs.menu && this.$refs.menu.contains(e.target))) this.close(); },
    toggle() {
      if (this.open) { this.close(); return; }
      const r = this.$refs.btn.getBoundingClientRect();
      this.left = r.right; this.top = r.bottom;
      this.open = true;
      this.$nextTick(() => {
        const el = this.$refs.menu;
        if (!el) return;
        const m = el.getBoundingClientRect();
        this.left = Math.max(4, Math.min(r.right - m.width, window.innerWidth - m.width - 4));
        this.top = r.bottom + m.height > window.innerHeight - 4 ? Math.max(4, r.top - m.height) : r.bottom + 2;
        const first = el.querySelector('button:not(:disabled)');
        if (first) first.focus();
      });
    },
    close(refocus) {
      this.open = false;
      if (refocus === true && this.$refs.btn) this.$refs.btn.focus();
    },
    run(item) { this.close(); if (item.run) item.run(); },
    move(e, dir) {
      const buttons = Array.from(this.$refs.menu.querySelectorAll('button:not(:disabled)'));
      const i = buttons.indexOf(document.activeElement);
      const next = buttons[(i + dir + buttons.length) % buttons.length];
      if (next) { next.focus(); e.preventDefault(); }
    }
  },
  template: `
    <span class="rowmenu">
      <button ref="btn" type="button" class="icon-btn" :class="cls" :aria-label="label || $t('action.more')" aria-haspopup="menu"
              :aria-expanded="String(open)" @click.stop="toggle"><icon name="more"></icon></button>
      <div v-if="open" ref="menu" class="menu" role="menu" :aria-label="label" :style="{ left: left + 'px', top: top + 'px' }"
           @keydown.down="move($event, 1)" @keydown.up="move($event, -1)">
        <button v-for="i in items" :key="i.id" type="button" role="menuitem" :disabled="i.disabled" @click="run(i)">
          <icon v-if="i.icon" :name="i.icon"></icon><span v-else class="ic"></span>{{ i.label }}</button>
      </div>
    </span>`
};
