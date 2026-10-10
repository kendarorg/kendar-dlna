/**
 * Identity bounded context (web player plan sections 3, 4, 12): users and roles, credentials, invites, login
 * sessions, remember-me tokens, login throttling, allowed roots and permissions. Depends on nothing but the JDK,
 * Jackson and {@code shared}; the REST adapter ({@code adminweb}) enforces the decisions taken here.
 */
package org.kendar.dlna.identity;
