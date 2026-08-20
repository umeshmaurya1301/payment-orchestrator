package com.payorch.ledger.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The tombstones. Phase 6k.
 *
 * <p>{@code JpaRepository} rather than a hand-written query because there are
 * exactly two operations - insert one, ask whether one exists - and both are
 * derived. The interesting logic is entirely in WHEN they are called; see
 * {@link LedgerPosting#legsFor}.
 */
public interface ReversedCaptureRepository extends JpaRepository<ReversedCapture, UUID> {
}
