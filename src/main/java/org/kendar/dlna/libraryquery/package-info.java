/**
 * Library Query context (plan section 12): the read-only facade of the web player over the catalog, views, search,
 * shuffle and artwork. Every result is confined to the caller's allowed roots in one place
 * ({@link org.kendar.dlna.libraryquery.LibraryQuery}); free of any HTTP type.
 */
package org.kendar.dlna.libraryquery;
