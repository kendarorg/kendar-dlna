/* A two-state switch with a label on each side; `modelValue` true = the right-hand state. */
export const SwitchToggle = {
  name: 'SwitchToggle',
  props: { modelValue: { type: Boolean, required: true }, offLabel: { type: String, required: true },
    onLabel: { type: String, required: true } },
  emits: ['update:modelValue'],
  template: `
    <label class="switch2">
      <span class="sw-label" :class="{ on: !modelValue }">{{ offLabel }}</span>
      <input type="checkbox" role="switch" :checked="modelValue" @change="$emit('update:modelValue', $event.target.checked)">
      <span class="sw-track" aria-hidden="true"></span>
      <span class="sw-label" :class="{ on: modelValue }">{{ onLabel }}</span>
    </label>`
};
