package com.payorch.ledger.journal;

import java.util.UUID;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface JournalRepository extends MongoRepository<JournalEntry, String> {

    boolean existsByEventId(UUID eventId);

    long countByPaymentId(UUID paymentId);
}
