/* Browser tab title: "artist - title" of the current track, the app name when nothing is loaded. */
export const APP_TITLE = 'Kendar Music';

export function pageTitle(track) {
  if (!track) return APP_TITLE;
  const title = (track.title || track.name || '').trim();
  const artist = (track.artist || '').trim();
  if (title && artist) return artist + ' - ' + title;
  return title || artist || APP_TITLE;
}
