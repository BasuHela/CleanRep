package org.cleanrep.core;

/** A revision or uniqueness condition failed; no part of the write was applied. */
public final class StoreConflict extends RuntimeException {
  private static final long serialVersionUID = 1L;
}
