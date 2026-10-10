/* The favorites singleton: paths of the signed-in user's favorites, loaded after sign-in. */
import { reactive } from '../vue.js';
import { Api } from '../api.js';
import { createFavorites } from '../core/favorites.js';

export const favorites = createFavorites({
  reactive: reactive,
  list: p => Api.favorites(p),
  add: paths => Api.addFavorites(paths),
  remove: paths => Api.removeFavorites(paths)
});
