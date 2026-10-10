/* Pure logic of the admin Library page: directory tree state, keyboard navigation, badges, breadcrumbs. */

/** Every ancestor path of "a/b/c": ["a", "a/b"] (the path itself is not included). */
export function ancestorPaths(path) {
  const parts = (path || '').split('/').filter(Boolean);
  const out = [];
  for (let i = 1; i < parts.length; i++) out.push(parts.slice(0, i).join('/'));
  return out;
}

/** Paths of the nodes to expand by default: the roots. */
export function defaultExpanded(roots) {
  return (roots || []).map(r => r.path);
}

/** Expanded set (array of paths) with the ancestors of `selected` added; never mutates its input. */
export function withAncestors(expanded, selected) {
  const set = new Set(expanded || []);
  for (const p of ancestorPaths(selected)) set.add(p);
  return Array.from(set);
}

/** Toggles a path in an expanded array; returns a new array. */
export function toggleExpanded(expanded, path) {
  const set = new Set(expanded || []);
  if (set.has(path)) set.delete(path); else set.add(path);
  return Array.from(set);
}

/** Drops paths that no longer exist in the tree (a folder was deleted or renamed). */
export function pruneExpanded(expanded, roots) {
  const known = new Set();
  const walk = n => { known.add(n.path); (n.children || []).forEach(walk); };
  (roots || []).forEach(walk);
  return (expanded || []).filter(p => known.has(p));
}

/**
 * The visible rows of the tree in display order: {node, path, level, hasChildren, open, parent}.
 * Roots are level 1 (the ARIA convention).
 */
export function visibleRows(roots, expanded) {
  const open = new Set(expanded || []);
  const rows = [];
  const walk = (node, level, parent) => {
    const kids = node.children || [];
    const isOpen = kids.length > 0 && open.has(node.path);
    rows.push({ node: node, path: node.path, level: level, hasChildren: kids.length > 0, open: isOpen, parent: parent });
    if (isOpen) kids.forEach(k => walk(k, level + 1, node.path));
  };
  (roots || []).forEach(r => walk(r, 1, null));
  return rows;
}

/**
 * Keyboard handling of a tree (WAI-ARIA tree pattern). Returns what to do:
 * {focus: path} | {toggle: path} | {select: path} | null.
 */
export function treeKey(key, rows, focusedPath) {
  const i = rows.findIndex(r => r.path === focusedPath);
  if (!rows.length) return null;
  const cur = i < 0 ? null : rows[i];
  switch (key) {
    case 'ArrowDown': return { focus: rows[Math.min(rows.length - 1, i + 1)].path };
    case 'ArrowUp': return { focus: rows[Math.max(0, i < 0 ? 0 : i - 1)].path };
    case 'Home': return { focus: rows[0].path };
    case 'End': return { focus: rows[rows.length - 1].path };
    case 'ArrowRight':
      if (!cur) return null;
      if (cur.hasChildren && !cur.open) return { toggle: cur.path };
      if (cur.open && rows[i + 1]) return { focus: rows[i + 1].path };
      return null;
    case 'ArrowLeft':
      if (!cur) return null;
      if (cur.open) return { toggle: cur.path };
      if (cur.parent) return { focus: cur.parent };
      return null;
    case 'Enter': case ' ': return cur ? { select: cur.path } : null;
    default: return null;
  }
}

/** Breadcrumb entries of a folder path; `names` maps a path to its display name. */
export function crumbsFor(path, names) {
  const parts = (path || '').split('/').filter(Boolean);
  return parts.map((name, i) => {
    const p = parts.slice(0, i + 1).join('/');
    return { name: (names && names[p]) || name, path: p };
  });
}

/** path -> displayName for a whole tree. */
export function nameIndex(roots) {
  const out = {};
  const walk = n => { out[n.path] = n.displayName; (n.children || []).forEach(walk); };
  (roots || []).forEach(walk);
  return out;
}

/** Badges of a track row: [{id, kind: ''|'warn'|'bad', labelKey, helpKey}] (labels are i18n keys). */
export function trackBadges(track) {
  const out = [];
  if (!track) return out;
  if (!track.editable) {
    out.push({ id: 'readonly', kind: '', labelKey: 'lib.badge.readonly', helpKey: 'lib.badge.readonly.help' });
  } else if ((track.editableFields || []).length < 10) {
    out.push({ id: 'limited', kind: '', labelKey: 'lib.badge.limited', helpKey: 'lib.badge.limited.help' });
  }
  if (track.segment) out.push({ id: 'cue', kind: '', labelKey: 'badge.virtual', helpKey: 'lib.badge.segment.help' });
  if (track.outOfSync) out.push({ id: 'sync', kind: 'warn', labelKey: 'lib.badge.sync', helpKey: 'lib.badge.sync.help' });
  return out;
}

/** Badges of a folder (header, tile or tree row). */
export function folderBadges(folder) {
  const out = [];
  if (!folder) return out;
  if (folder.virtual) out.push({ id: 'cue', kind: '', labelKey: 'badge.virtual', helpKey: 'lib.badge.virtual.help' });
  if (folder.hasList) out.push({ id: 'list', kind: '', labelKey: 'lib.badge.list', helpKey: 'lib.badge.list.help' });
  if (folder.outOfSync) out.push({ id: 'sync', kind: 'warn', labelKey: 'lib.badge.sync', helpKey: 'lib.badge.sync.help' });
  if (folder.settingsError) out.push({ id: 'settingsError', kind: 'bad', labelKey: 'lib.badge.settingsError', helpKey: 'lib.badge.settingsError.help' });
  if (folder.listError) out.push({ id: 'listError', kind: 'bad', labelKey: 'lib.badge.listError', helpKey: 'lib.badge.listError.help' });
  return out;
}

/** "2.5" style track label: disc and track number, empty when neither is known. */
export function trackNumber(track, index) {
  if (!track) return '';
  const n = track.trackNo || (index !== undefined ? index + 1 : '');
  if (!n) return '';
  return track.discNo ? track.discNo + '.' + n : String(n);
}

/** Total duration in ms of the tracks of a folder listing (children with a track). */
export function totalDuration(children) {
  let sum = 0;
  for (const c of children || []) if (c.type === 'track' && c.track && c.track.durationMillis) sum += c.track.durationMillis;
  return sum;
}
