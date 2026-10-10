/* Heart toggle for one track; reads the favorites cache, so it costs no request until clicked. */
import { Icon } from './icons.js';
import { favorites } from '../personal/favorites.js';
import { toast } from '../state.js';

export const FavButton = {
  name: 'FavButton',
  components: { Icon },
  props: { track: { type: Object, default: null } },
  setup() { return { favorites: favorites }; },
  computed: {
    on() { return !!this.track && this.favorites.state.paths[this.track.path] === true; }
  },
  methods: {
    async toggle() {
      if (!this.track) return;
      try {
        const now = await this.favorites.toggle(this.track.path);
        toast(this.$t(now ? 'toast.favAdded' : 'toast.favRemoved'));
      } catch (e) { toast(e.message); }
    }
  },
  template: `
    <button type="button" class="icon-btn fav" :class="{ on: on }" :disabled="!track" :aria-pressed="on"
            :aria-label="$t(on ? 'action.unfavorite' : 'action.favorite')" :title="$t(on ? 'action.unfavorite' : 'action.favorite')"
            @click.stop="toggle"><icon :name="on ? 'heart' : 'heartOutline'"></icon></button>`
};
