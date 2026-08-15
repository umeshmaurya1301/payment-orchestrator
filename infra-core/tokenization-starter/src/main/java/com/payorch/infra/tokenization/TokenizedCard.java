package com.payorch.infra.tokenization;

/**
 * What is allowed to leave {@code payments-edge} in place of a card number.
 *
 * <p>This record is the tokenization boundary expressed as a type. Downstream
 * services accept this and have no field to put a PAN in even if one were
 * offered - which is a stronger guarantee than a rule in a document.
 *
 * @param token opaque vault reference; meaningless without vault credentials
 * @param bin   first six digits, for routing and issuer identification
 * @param last4 last four digits, for display and support
 */
public record TokenizedCard(String token, String bin, String last4) {
}
