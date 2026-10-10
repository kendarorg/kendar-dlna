/*
 * Directory tree of the admin library: a flat WAI-ARIA tree (one list of visible rows with aria-level), roving tabindex,
 * arrow-key navigation, the selected row scrolled into view. The parent owns the expanded paths and the selection.
 */
import { Icon } from './icons.js';
import { visibleRows, treeKey, folderBadges } from '../core/library.js';

export const DirTree = {
  name: 'DirTree',
  components: { Icon },
  props: {
    roots: { type: Array, required: true },
    expanded: { type: Array, required: true },
    selected: { type: String, default: null }
  },
  emits: ['select', 'toggle'],
  data() { return { focused: null }; },
  computed: {
    rows() { return visibleRows(this.roots, this.expanded); },
    tabStop() {
      const has = p => this.rows.some(r => r.path === p);
      if (this.focused && has(this.focused)) return this.focused;
      if (this.selected && has(this.selected)) return this.selected;
      return this.rows.length ? this.rows[0].path : null;
    }
  },
  watch: {
    selected() { this.$nextTick(() => this.reveal()); },
    roots() { this.$nextTick(() => this.reveal()); }
  },
  mounted() { this.$nextTick(() => this.reveal()); },
  methods: {
    problems(node) { return folderBadges(node).filter(b => b.id !== 'cue' && b.id !== 'list'); },
    reveal() {
      const el = this.$el && this.$el.querySelector ? this.$el.querySelector('[aria-selected="true"]') : null;
      if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
    },
    focusRow(path) {
      this.focused = path;
      this.$nextTick(() => {
        const el = this.$el.querySelector('[data-path="' + (window.CSS && CSS.escape ? CSS.escape(path) : path) + '"]');
        if (el) { el.focus(); if (el.scrollIntoView) el.scrollIntoView({ block: 'nearest' }); }
      });
    },
    onKey(e, row) {
      if (e.altKey || e.ctrlKey || e.metaKey) return;
      const act = treeKey(e.key, this.rows, row.path);
      if (!act) return;
      e.preventDefault();
      if (act.focus) this.focusRow(act.focus);
      else if (act.toggle) this.$emit('toggle', act.toggle);
      else if (act.select) this.$emit('select', act.select);
    }
  },
  template: `
    <ul class="dirtree" role="tree" :aria-label="$t('lib.tree')">
      <li v-for="r in rows" :key="r.path" class="dt-item" role="treeitem" :data-path="r.path"
          :aria-level="r.level" :aria-expanded="r.hasChildren ? String(r.open) : undefined"
          :aria-selected="String(r.path === selected)" :tabindex="r.path === tabStop ? 0 : -1"
          :class="{ active: r.path === selected }" :style="{ '--lvl': r.level - 1 }"
          @keydown="onKey($event, r)" @focus="focused = r.path">
        <button v-if="r.hasChildren" type="button" class="dt-twisty" tabindex="-1" :class="{ open: r.open }"
                :aria-label="r.open ? $t('lib.collapse') : $t('lib.expand')" @click.stop="$emit('toggle', r.path)"><icon name="expand"></icon></button>
        <span v-else class="dt-twisty"></span>
        <a class="dt-name" href="#" tabindex="-1" @click.prevent="$emit('select', r.path)">
          <icon :name="r.node.virtual ? 'album' : 'folder'"></icon><span class="dt-text">{{ r.node.displayName }}</span></a>
        <span v-for="b in problems(r.node)" :key="b.id" class="dt-dot" :class="b.kind" :title="$t(b.helpKey)"></span>
      </li>
    </ul>`
};
