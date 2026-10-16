/* The typed confirmation for destructive actions; the server checks the same exact phrase. */
export const CONSENT_PHRASE = 'I CONSENT';

export function consentMatches(text) { return text === CONSENT_PHRASE; }
