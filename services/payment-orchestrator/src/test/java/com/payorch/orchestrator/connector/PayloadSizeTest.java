package com.payorch.orchestrator.connector;

import org.junit.jupiter.api.Test;

import com.payorch.proto.v1.AuthorizeRequest;
import com.payorch.proto.v1.AuthorizeResponse;
import com.payorch.proto.v1.Outcome;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many bytes each transport puts on the wire, for the identical message.
 *
 * <h2>Why this is a test and not a line in a shell script</h2>
 *
 * <p>Payload size is one of the four numbers phase 9's benchmark criterion asks
 * for, and it is the only one that is deterministic. Throughput, p99 and CPU
 * vary with the machine, the load generator and whatever else is running;
 * serialized length does not. Measuring it here means the number is reproducible
 * on any machine and is checked on every build, rather than being a figure
 * somebody recorded once.
 *
 * <p>It also protects the claim. A proto that quietly grew a field, or a JSON
 * body that started omitting nulls, would move the ratio - and the writeup would
 * keep quoting the old one.
 *
 * <h2>The comparison is deliberately unflattering to protobuf</h2>
 *
 * <p>Phase 9's trap list says: "for small payloads over a warm connection the gap
 * is often smaller than expected. Report what you measure, including if it is
 * unflattering." So the JSON side is the real request body this system sends -
 * not a padded one, not pretty-printed, and with the same field set.
 */
class PayloadSizeTest {

    private final ObjectMapper json = new ObjectMapper();

    private static final String TOKEN = "tok_KS7TMIx5A37shyH5wwDMqw";
    private static final String REFERENCE = "01a01546-f43d-7b4d-bad5-9c290b8e9b62";

    private ConnectorApi.AuthorizeRequest restRequest() {
        return new ConnectorApi.AuthorizeRequest(
                REFERENCE, "mockpsp", 4200, "INR", TOKEN, "424242", "4242");
    }

    private AuthorizeRequest protoRequest() {
        return AuthorizeRequest.newBuilder()
                .setReference(REFERENCE)
                .setPspId("mockpsp")
                .setAmountMinor(4200)
                .setCurrency("INR")
                .setCardToken(TOKEN)
                .setCardBin("424242")
                .setCardLast4("4242")
                .build();
    }

    @Test
    void theAuthorizeRequestIsSmallerAsProtobuf() {
        int asJson = json.writeValueAsBytes(restRequest()).length;
        int asProto = protoRequest().toByteArray().length;

        System.out.printf("AuthorizeRequest   JSON %d bytes   proto %d bytes   %.0f%% smaller%n",
                asJson, asProto, 100.0 * (asJson - asProto) / asJson);

        assertThat(asProto)
                .as("protobuf drops the field names; JSON repeats every one of them")
                .isLessThan(asJson);
    }

    @Test
    void theAuthorizeResponseIsSmallerAsProtobuf() {
        ConnectorApi.AuthorizeResponse rest = new ConnectorApi.AuthorizeResponse(
                "mock_aoqw6w2bn43giuje49m0", ConnectorApi.Outcome.APPROVED, null, "AUTH01");
        AuthorizeResponse proto = AuthorizeResponse.newBuilder()
                .setProviderRef("mock_aoqw6w2bn43giuje49m0")
                .setOutcome(Outcome.OUTCOME_APPROVED)
                .setAuthCode("AUTH01")
                .build();

        int asJson = json.writeValueAsBytes(rest).length;
        int asProto = proto.toByteArray().length;

        System.out.printf("AuthorizeResponse  JSON %d bytes   proto %d bytes   %.0f%% smaller%n",
                asJson, asProto, 100.0 * (asJson - asProto) / asJson);

        assertThat(asProto).isLessThan(asJson);
    }

    /**
     * The part of the saving that is NOT compression.
     *
     * <p>Most of protobuf's advantage on a message this shape is that it does not
     * send the field names — {@code "amountMinor"} is eleven bytes of key for
     * two bytes of value, every time. The values themselves are mostly identifier
     * strings and are the same length in both encodings.
     *
     * <p>Worth stating because it bounds the claim: the saving scales with the
     * number of fields, not with the size of the data. A message carrying one
     * large blob would show almost no difference, and "protobuf is 40% smaller"
     * is a fact about this message rather than about the format.
     */
    @Test
    void mostOfTheSavingIsFieldNames() {
        int fieldNameBytes = "reference".length() + "pspId".length() + "amountMinor".length()
                + "currency".length() + "cardToken".length() + "cardBin".length()
                + "cardLast4".length();

        int asJson = json.writeValueAsBytes(restRequest()).length;
        int asProto = protoRequest().toByteArray().length;
        int saved = asJson - asProto;

        System.out.printf("field names %d bytes of a %d byte saving%n", fieldNameBytes, saved);

        assertThat(fieldNameBytes)
                .as("the names alone account for most of what protobuf drops")
                .isGreaterThan(saved / 2);
    }
}
