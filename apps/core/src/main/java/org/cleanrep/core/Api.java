package org.cleanrep.core;

/**
 * The CleanRep session API for home mode: a port of the fixture-mode Python server (`services/api`).
 * The HTTP layer (`org.cleanrep.core.http`) calls it for every `/v1/*` request, after the loopback and size
 * checks that belong to the listener; everything else, policy included, happens here.
 */
public interface Api {
  ApiResponse handle(ApiRequest request);
}
