/* Typed-consent dialog for irreversible actions: the button stays disabled until "I CONSENT" is typed exactly. */
import { Icon } from './icons.js';
import { ui } from '../state.js';
import { CONSENT_PHRASE, consentMatches } from '../core/consent.js';

export const ConsentDialog = {
  name: 'ConsentDialog',
  components: { Icon },
  setup() { return { ui: ui, phrase: CONSENT_PHRASE }; },
  data() { return { text: '' }; },
  computed: { ok() { return consentMatches(this.text); } },
  watch: {
    'ui.consent'(c) { this.text = ''; if (c) this.$nextTick(() => this.$refs.box && this.$refs.box.focus()); }
  },
  mounted() {
    this.onKey = e => { if (this.ui.consent && e.key === 'Escape') { this.finish(false); e.stopPropagation(); } };
    document.addEventListener('keydown', this.onKey, true);
  },
  beforeUnmount() { document.removeEventListener('keydown', this.onKey, true); },
  methods: {
    finish(result) {
      const c = this.ui.consent;
      this.ui.consent = null;
      if (c) c.resolve(result);
    },
    submit() { if (this.ok) this.finish(true); }
  },
  template: `
    <div v-if="ui.consent" class="modal-backdrop" @click.self="finish(false)">
      <section class="modal consent" role="alertdialog" aria-modal="true" :aria-label="ui.consent.title">
        <header class="modal-head"><h2>{{ ui.consent.title }}</h2>
          <button type="button" class="icon-btn" :aria-label="$t('common.close')" @click="finish(false)"><icon name="close"></icon></button></header>
        <p v-for="(l, i) in ui.consent.lines" :key="i" class="consent-line">{{ l }}</p>
        <form @submit.prevent="submit">
          <label class="field">{{ $t('consent.prompt', { phrase: phrase }) }}
            <input ref="box" type="text" v-model="text" autocomplete="off" spellcheck="false" :placeholder="phrase"></label>
          <div class="row consent-actions">
            <button type="button" @click="finish(false)">{{ $t('common.cancel') }}</button>
            <button type="submit" class="danger" :disabled="!ok">{{ ui.consent.confirmLabel || $t('consent.confirm') }}</button>
          </div>
        </form>
      </section>
    </div>`
};
