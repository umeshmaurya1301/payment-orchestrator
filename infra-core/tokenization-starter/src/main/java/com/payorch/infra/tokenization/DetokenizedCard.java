package com.payorch.infra.tokenization;

/**
 * A card, briefly, in the only place allowed to hold one: the moments between
 * {@link TokenVault#detokenize} and the outbound provider call.
 *
 * <p>Deliberately not a {@code @Sensitive}-annotated DTO. An annotation would
 * mask {@code pan} on serialization - including the serialization that produces
 * the provider request body, which would send the provider {@code
 * 424242******4242} and turn every authorization into a decline for reasons no
 * log line would explain. The protection here is that this type is never
 * serialized at all: it exists inside one method, is mapped field by field into
 * the provider's own request, and is never logged, stored or returned.
 *
 * <p>There is a test in {@code psp-connector} that pins this down, because the
 * mistake it prevents is one a careful reviewer would otherwise introduce on
 * purpose.
 */
public record DetokenizedCard(String pan, int expiryMonth, int expiryYear) {

    /**
     * Overridden so an accidental {@code log.debug("{}", card)} or an exception
     * message that interpolates this record cannot print a card number. Records
     * generate a {@code toString} that includes every component, and that
     * default is the single easiest way to leak a PAN.
     */
    @Override
    public String toString() {
        return "DetokenizedCard[pan=****, expiry=**/****]";
    }
}
