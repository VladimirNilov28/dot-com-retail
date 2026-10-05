package ee.bytecore.backend.graphql;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

import ee.bytecore.backend.integration.payment.PaymentResultListener;
import ee.bytecore.backend.integration.payment.RefundResultListener;
import ee.bytecore.backend.integration.payment.event.PaymentFailedEvent;
import ee.bytecore.backend.integration.payment.event.PaymentSucceededEvent;
import ee.bytecore.backend.integration.payment.event.PaymentUnresolvedEvent;
import ee.bytecore.backend.integration.payment.event.RefundResultEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class OrderCancellationGraphQlIntegrationTest extends CheckoutGraphQlIntegrationTest {
    private static final ObjectMapper EVENTS = new ObjectMapper().findAndRegisterModules();
    static final String FIELDS =
            """
        publicId status cancellationEligibility{allowed reason}
        cancellation{source cancelledAt awaitingPaymentOutcome}
        payment{status} refund{status amount currency}
        """;

    @Autowired
    PaymentResultListener payments;

    @Autowired
    RefundResultListener refunds;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Override
    void configureJwt() {
        doAnswer(invocation -> {
                    String value = invocation.getArgument(0);
                    String[] parts = value.split("/", -1);
                    if (parts.length < 2 || parts.length > 3) throw new BadJwtException("Invalid fixture");
                    return Jwt.withTokenValue(value)
                            .header("alg", "RS256")
                            .subject(parts[0])
                            .claim("role", parts.length == 3 ? parts[2] : "USER")
                            .claim(
                                    "scope",
                                    parts[1].replace("-read", ":read")
                                            .replace("-write", ":write")
                                            .replace("order-manage-status", "order:manage-status")
                                            .replace("+", " "))
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(120))
                            .build();
                })
                .when(jwtDecoder)
                .decode(anyString());
    }

    record Placed(Session session, UUID checkoutId, UUID publicId, long id, UUID paymentRequest) {}

    Placed placedGuest() throws Exception {
        return placedGuest(selections());
    }

    Placed placedGuest(Map<String, Object> selection) throws Exception {
        Session session = guest();
        UUID checkout = UUID.randomUUID();
        String quote = success(post(PREVIEW, Map.of("input", selection), session, null))
                .at("/data/guestCheckoutPreview/quoteVersion")
                .asString();
        var body = success(post(
                PLACE,
                Map.of(
                        "input",
                        Map.of("requestId", checkout.toString(), "acceptedQuoteVersion", quote, "checkout", selection)),
                session,
                null));
        UUID publicId =
                UUID.fromString(body.at("/data/createGuestOrder/publicId").asString());
        long id = jdbc.queryForObject("SELECT id FROM orders WHERE public_id=?", Long.class, publicId);
        UUID request = UUID.fromString(jdbc.queryForObject(
                "SELECT payload->>'eventId' FROM payment_outbox WHERE order_id=? AND event_type='payment.requested'",
                String.class,
                id));
        return new Placed(session, checkout, publicId, id, request);
    }

    Reply cancel(Placed order, UUID request, Session session, String token, String operation) throws Exception {
        return post(
                "mutation($input:CancelOrderInput!){" + operation + "(input:$input){requestId order{" + FIELDS + "}}}",
                Map.of(
                        "input",
                        Map.of(
                                "requestId",
                                request.toString(),
                                "publicId",
                                order.publicId().toString())),
                session,
                token);
    }

    JsonNode confirmation(Placed order) throws Exception {
        return success(post(
                        "query($id:UUID!){guestOrder(requestId:$id){" + FIELDS + "}}",
                        Map.of("id", order.checkoutId().toString()),
                        order.session(),
                        null))
                .at("/data/guestOrder");
    }

    void successPayment(Placed order, UUID payment) throws Exception {
        payments.onPaymentSucceeded(EVENTS.writeValueAsString(new PaymentSucceededEvent(
                UUID.randomUUID(), order.paymentRequest(), order.id(), payment, Instant.now())));
    }

    void decline(Placed order, UUID payment) throws Exception {
        payments.onPaymentFailed(EVENTS.writeValueAsString(new PaymentFailedEvent(
                UUID.randomUUID(), order.paymentRequest(), order.id(), payment, "declined", Instant.now())));
    }

    int stock() {
        return jdbc.queryForObject(
                "SELECT sum(quantity) FROM inventory WHERE product_variant_id=?", Integer.class, variant.getId());
    }

    Placed placedOwner() throws Exception {
        carts.addItem(owner.getId(), variant.getId(), 2);
        var selections = Map.of("shippingMethod", "PICKUP");
        var quote = success(post(
                        "query($input:CheckoutInput!){checkoutPreview(input:$input){quoteVersion}}",
                        Map.of("input", selections),
                        new Session(),
                        token()))
                .at("/data/checkoutPreview/quoteVersion")
                .asString();
        UUID checkout = UUID.randomUUID();
        var response = success(post(
                "mutation($input:PlaceOrderInput!){createOrder(input:$input){publicId}}",
                Map.of(
                        "input",
                        Map.of(
                                "requestId",
                                checkout.toString(),
                                "acceptedQuoteVersion",
                                quote,
                                "checkout",
                                selections)),
                new Session(),
                token()));
        UUID publicId =
                UUID.fromString(response.at("/data/createOrder/publicId").asString());
        long id = jdbc.queryForObject("SELECT id FROM orders WHERE public_id=?", Long.class, publicId);
        UUID request = UUID.fromString(jdbc.queryForObject(
                "SELECT payload->>'eventId' FROM payment_outbox WHERE order_id=? AND event_type='payment.requested'",
                String.class,
                id));
        return new Placed(new Session(), checkout, publicId, id, request);
    }

    @Test
    void ownerCancellationRequiresOwnershipScopeAndActiveAccountIncludingReplay() throws Exception {
        var order = placedOwner();
        UUID request = UUID.randomUUID();
        assertThat(cancel(order, request, new Session(), owner.getId() + "/order-read", "cancelOrder")
                        .body()
                        .has("errors"))
                .isTrue();
        assertThat(cancel(order, request, new Session(), "999999/order-write", "cancelOrder")
                        .body()
                        .has("errors"))
                .isTrue();
        String key = UUID.randomUUID().toString();
        var foreign = users.save(ee.bytecore.backend.entities.user.User.create(
                key, key + "@example.com", java.time.LocalDate.of(1990, 1, 1)));
        assertThat(cancel(order, request, new Session(), foreign.getId() + "/order-write", "cancelOrder")
                        .body()
                        .has("errors"))
                .isTrue();
        success(cancel(order, request, new Session(), token(), "cancelOrder"));
        success(cancel(order, request, new Session(), token(), "cancelOrder"));
        jdbc.update("UPDATE users SET deletion_identity_id=? WHERE id=?", UUID.randomUUID(), owner.getId());
        assertThat(cancel(order, request, new Session(), token(), "cancelOrder")
                        .body()
                        .has("errors"))
                .isTrue();
        assertThat(stock()).isEqualTo(20);
    }

    @Test
    void staffCanCancelButCannotAssignFinancialSuccessOrBypassFulfillmentBoundary() throws Exception {
        var order = placedGuest();
        for (String staff : List.of(
                owner.getId() + "/order-manage-status/USER",
                owner.getId() + "/order-read/ADMIN",
                owner.getId() + "/order-read/ORDER_MANAGER")) {
            assertThat(cancel(order, UUID.randomUUID(), new Session(), staff, "cancelOrderAsStaff")
                            .body()
                            .has("errors"))
                    .isTrue();
        }
        String staff = owner.getId() + "/order-manage-status+order-read/ORDER_MANAGER";
        String update =
                "mutation($id:ID!,$status:OrderStatus!){updateOrderStatus(orderId:$id,input:{status:$status}){status}}";
        assertThat(post(update, Map.of("id", Long.toString(order.id()), "status", "PAID"), new Session(), staff)
                        .body()
                        .has("errors"))
                .isTrue();
        successPayment(order, UUID.randomUUID());
        success(post(update, Map.of("id", Long.toString(order.id()), "status", "SHIPPING"), new Session(), staff));
        assertThat(confirmation(order).at("/cancellationEligibility/reason").asString())
                .isEqualTo("PROCESSING_BEGUN");
        assertThat(cancel(order, UUID.randomUUID(), order.session(), null, "cancelGuestOrder")
                        .body()
                        .has("errors"))
                .isTrue();
        assertThat(cancel(order, UUID.randomUUID(), new Session(), staff, "cancelOrderAsStaff")
                        .body()
                        .has("errors"))
                .isTrue();
        success(post(update, Map.of("id", Long.toString(order.id()), "status", "COMPLETED"), new Session(), staff));
        assertThat(confirmation(order).at("/cancellationEligibility/reason").asString())
                .isEqualTo("COMPLETED");
        assertThat(stock()).isEqualTo(18);
    }

    @Test
    void staffCancellationUsesSameReleaseAndRefundPipeline() throws Exception {
        var order = placedGuest();
        successPayment(order, UUID.randomUUID());
        var response = success(cancel(
                order,
                UUID.randomUUID(),
                new Session(),
                owner.getId() + "/order-manage-status/ADMIN",
                "cancelOrderAsStaff"));
        assertThat(response.at("/data/cancelOrderAsStaff/order/cancellation/source")
                        .asString())
                .isEqualTo("STAFF_REQUESTED");
        assertThat(response.at("/data/cancelOrderAsStaff/order/refund/status").asString())
                .isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(20);
    }

    @Test
    void requestUuidCannotBeReboundToAnotherAuthorizedOrder() throws Exception {
        var first = placedGuest();
        UUID request = UUID.randomUUID();
        success(cancel(first, request, first.session(), null, "cancelGuestOrder"));
        var second = placedGuest();
        assertThat(cancel(second, request, second.session(), null, "cancelGuestOrder")
                        .body()
                        .at("/errors/0/extensions/errorType")
                        .asString())
                .isEqualTo("CANCELLATION_REQUEST_CONFLICT");
        assertThat(confirmation(second).at("/status").asString()).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(18);
    }

    @Test
    void refundEventsAreCorrelatedTerminalAndNeverChangeOrderOrInventory() throws Exception {
        var order = placedGuest();
        UUID payment = UUID.randomUUID();
        successPayment(order, payment);
        success(cancel(order, UUID.randomUUID(), order.session(), null, "cancelGuestOrder"));
        UUID refund = jdbc.queryForObject("SELECT id FROM order_refunds WHERE order_id=?", UUID.class, order.id());
        UUID request = jdbc.queryForObject(
                "SELECT request_event_id FROM order_refunds WHERE order_id=?", UUID.class, order.id());
        var wrong = new RefundResultEvent(
                UUID.randomUUID(), UUID.randomUUID(), refund, order.id(), payment, null, null, Instant.now());
        refunds.onRefundSucceeded(EVENTS.writeValueAsString(wrong));
        assertThat(confirmation(order).at("/refund/status").asString()).isEqualTo("PENDING");
        refunds.onRefundUnresolved(EVENTS.writeValueAsString(new RefundResultEvent(
                UUID.randomUUID(), request, refund, order.id(), payment, null, "gateway timeout", Instant.now())));
        assertThat(confirmation(order).at("/refund/status").asString()).isEqualTo("UNRESOLVED");
        String success = EVENTS.writeValueAsString(new RefundResultEvent(
                UUID.randomUUID(), request, refund, order.id(), payment, "simulated-refund", null, Instant.now()));
        refunds.onRefundSucceeded(success);
        refunds.onRefundSucceeded(success);
        refunds.onRefundFailed(EVENTS.writeValueAsString(new RefundResultEvent(
                UUID.randomUUID(), request, refund, order.id(), payment, null, "rejected", Instant.now())));
        refunds.onRefundUnresolved(EVENTS.writeValueAsString(new RefundResultEvent(
                UUID.randomUUID(), request, refund, order.id(), payment, null, "timeout", Instant.now())));
        assertThat(confirmation(order).at("/refund/status").asString()).isEqualTo("SUCCEEDED");
        assertThat(confirmation(order).at("/payment/status").asString()).isEqualTo("SUCCEEDED");
        assertThat(confirmation(order).at("/status").asString()).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(20);
    }

    @Test
    void uncertainPaymentDoesNotDeclineAndOldUncertaintyCannotDowngradeSuccess() throws Exception {
        var order = placedGuest();
        UUID payment = UUID.randomUUID();
        String unresolved = EVENTS.writeValueAsString(new PaymentUnresolvedEvent(
                UUID.randomUUID(), order.paymentRequest(), order.id(), payment, "timeout", Instant.now()));
        payments.onPaymentUnresolved(unresolved);
        assertThat(confirmation(order).at("/status").asString()).isEqualTo("PENDING");
        assertThat(confirmation(order).at("/payment/status").asString()).isEqualTo("UNRESOLVED");
        assertThat(stock()).isEqualTo(18);
        successPayment(order, payment);
        payments.onPaymentUnresolved(unresolved);
        assertThat(confirmation(order).at("/payment/status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void fullRefundUsesOriginalChargedTotalIncludingShipping() throws Exception {
        var order = placedGuest(Map.of(
                "contactEmail",
                "guest@example.com",
                "shippingMethod",
                "STANDARD",
                "shippingAddress",
                Map.of(
                        "firstName",
                        "Ada",
                        "lastName",
                        "Example",
                        "city",
                        "Tallinn",
                        "countryCode",
                        "EE",
                        "postalCode",
                        "10111",
                        "addressLine1",
                        "Example 1")));
        successPayment(order, UUID.randomUUID());
        success(cancel(order, UUID.randomUUID(), order.session(), null, "cancelGuestOrder"));
        assertThat(confirmation(order).at("/refund/amount").asString()).isEqualTo("44.97");
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'amount' FROM payment_outbox WHERE order_id=? AND event_type='refund.requested'",
                        String.class,
                        order.id()))
                .isEqualTo("44.97");
    }

    @Test
    void confirmedRefundFailureIsFinalAndRefundResultRollbackIsRetryable() throws Exception {
        var order = placedGuest();
        UUID payment = UUID.randomUUID();
        successPayment(order, payment);
        success(cancel(order, UUID.randomUUID(), order.session(), null, "cancelGuestOrder"));
        UUID refund = jdbc.queryForObject("SELECT id FROM order_refunds WHERE order_id=?", UUID.class, order.id());
        UUID request = jdbc.queryForObject(
                "SELECT request_event_id FROM order_refunds WHERE order_id=?", UUID.class, order.id());
        String success = EVENTS.writeValueAsString(new RefundResultEvent(
                UUID.randomUUID(), request, refund, order.id(), payment, "simulated-refund", null, Instant.now()));
        var transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            refunds.onRefundSucceeded(success);
            status.setRollbackOnly();
        });
        assertThat(confirmation(order).at("/refund/status").asString()).isEqualTo("PENDING");
        String failed = EVENTS.writeValueAsString(new RefundResultEvent(
                UUID.randomUUID(), request, refund, order.id(), payment, null, "confirmed rejection", Instant.now()));
        refunds.onRefundFailed(failed);
        refunds.onRefundFailed(failed);
        refunds.onRefundSucceeded(success);
        assertThat(confirmation(order).at("/refund/status").asString()).isEqualTo("FAILED");
        assertThat(stock()).isEqualTo(20);
    }

    @Test
    void cancellationRequiresOriginHeaderAndNoStoreOnDenials() throws Exception {
        var order = placedGuest();
        String query = "mutation($input:CancelOrderInput!){cancelGuestOrder(input:$input){requestId}}";
        var body = mapper.writeValueAsString(Map.of(
                "query",
                query,
                "variables",
                Map.of(
                        "input",
                        Map.of(
                                "requestId",
                                UUID.randomUUID().toString(),
                                "publicId",
                                order.publicId().toString()))));
        for (boolean foreignOrigin : List.of(true, false)) {
            var builder = HttpRequest.newBuilder(endpoint())
                    .header("Content-Type", "application/json")
                    .header("Origin", foreignOrigin ? "http://evil.example" : "http://localhost:3000")
                    .header("Cookie", order.session().cookieHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (foreignOrigin) builder.header("X-Guest-Cart-Request", "1");
            var reply = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(reply.statusCode()).isEqualTo(subgraphRejectionStatus(403));
            String cacheControl = reply.headers().firstValue("Cache-Control").orElse("");
            assertThat(cacheControl).contains("no-store");
            if (subgraphRejectionStatus(403) == 403) assertThat(cacheControl).contains("private");
        }
        assertThat(stock()).isEqualTo(18);
    }

    @Test
    void guestCancellationIsIdempotentAndLateSuccessRequestsOneFullRefund() throws Exception {
        var order = placedGuest();
        UUID cancellation = UUID.randomUUID();
        assertThat(confirmation(order).at("/cancellationEligibility/allowed").asBoolean())
                .isTrue();
        var reply = cancel(order, cancellation, order.session(), null, "cancelGuestOrder");
        var result = success(reply).at("/data/cancelGuestOrder/order");
        assertThat(reply.http().headers().firstValue("Cache-Control").orElse(""))
                .contains("no-store");
        assertThat(result.at("/status").asString()).isEqualTo("CANCELLED");
        assertThat(result.at("/payment/status").asString()).isEqualTo("PENDING");
        assertThat(result.at("/cancellation/awaitingPaymentOutcome").asBoolean())
                .isTrue();
        assertThat(stock()).isEqualTo(20);
        success(cancel(order, cancellation, order.session(), null, "cancelGuestOrder"));
        success(cancel(order, UUID.randomUUID(), order.session(), null, "cancelGuestOrder"));
        UUID payment = UUID.randomUUID();
        successPayment(order, payment);
        successPayment(order, payment);
        var settled = confirmation(order);
        assertThat(settled.at("/status").asString()).isEqualTo("CANCELLED");
        assertThat(settled.at("/payment/status").asString()).isEqualTo("SUCCEEDED");
        assertThat(settled.at("/refund/status").asString()).isEqualTo("PENDING");
        assertThat(settled.at("/refund/amount").asString()).isEqualTo("39.98");
        assertThat(stock()).isEqualTo(20);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM order_refunds WHERE order_id=?", Integer.class, order.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment_outbox WHERE order_id=? AND event_type='refund.requested'",
                        Integer.class,
                        order.id()))
                .isEqualTo(1);
    }

    @Test
    void guestGrantAndOriginCannotBeReplacedByOrderReferenceOrJwt() throws Exception {
        var order = placedGuest();
        for (var stranger : List.of(new Session(), guest())) {
            assertThat(cancel(order, UUID.randomUUID(), stranger, null, "cancelGuestOrder")
                            .body()
                            .has("errors"))
                    .isTrue();
        }
        assertThat(cancel(order, UUID.randomUUID(), new Session(), token(), "cancelGuestOrder")
                        .body()
                        .has("errors"))
                .isTrue();
        jdbc.update(
                "UPDATE checkout_requests SET guest_expires_at=now()-interval '1 second' WHERE request_id=?",
                order.checkoutId());
        assertThat(cancel(order, UUID.randomUUID(), order.session(), null, "cancelGuestOrder")
                        .body()
                        .has("errors"))
                .isTrue();
        assertThat(stock()).isEqualTo(18);
    }

    @Test
    void matchingAccountEmailDoesNotClaimGuestOrder() throws Exception {
        var order = placedGuest(Map.of("contactEmail", owner.getEmail(), "shippingMethod", "PICKUP"));
        assertThat(cancel(order, UUID.randomUUID(), new Session(), token(), "cancelOrder")
                        .body()
                        .has("errors"))
                .isTrue();
        assertThat(post(
                                "query($id:UUID!){checkoutOrder(requestId:$id){publicId}}",
                                Map.of("id", order.checkoutId().toString()),
                                new Session(),
                                token())
                        .body()
                        .has("errors"))
                .isTrue();
        success(cancel(order, UUID.randomUUID(), order.session(), null, "cancelGuestOrder"));
        assertThat(stock()).isEqualTo(20);
    }

    @Test
    void cancellationTransportUsesSelectedAliasedFragmentAndRejectsUnsafeEnvelopes() throws Exception {
        var order = placedGuest();
        var input = Map.of(
                "requestId",
                UUID.randomUUID().toString(),
                "publicId",
                order.publicId().toString());
        String mixed = "mutation($input:CancelOrderInput!){cancelGuestOrder(input:$input){requestId}"
                + "cancelOrder(input:$input){requestId}}";
        String selected = "query Private{myOrders{publicId}} "
                + "mutation Selected($input:CancelOrderInput!){...CancellationRoot} "
                + "fragment CancellationRoot on Mutation{answer:cancelGuestOrder(input:$input){order{status}}}";
        var envelope = Map.of("query", selected, "operationName", "Selected", "variables", Map.of("input", input));
        for (Object body : List.of(
                Map.of("query", mixed, "variables", Map.of("input", input)),
                Map.of("query", selected, "operationName", "Private"),
                Map.of("query", selected, "variables", Map.of("input", input)),
                List.of(envelope))) {
            var request = HttpRequest.newBuilder(endpoint())
                    .header("Content-Type", "application/json")
                    .header("Origin", "http://localhost:3000")
                    .header("X-Guest-Cart-Request", "1")
                    .header("Cookie", order.session().cookieHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(mapper.readTree(response.body()).has("errors")).isTrue();
            if (body instanceof List<?> && subgraphRejectionStatus(403) == 200) {
                assertThat(response.statusCode()).isEqualTo(400);
                assertThat(mapper.readTree(response.body()).has("data")).isFalse();
            } else {
                assertThat(response.headers().firstValue("Cache-Control").orElse(""))
                        .contains("no-store");
            }
        }
        var duplicateCookie = HttpRequest.newBuilder(endpoint())
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("X-Guest-Cart-Request", "1")
                .header(
                        "Cookie",
                        order.session().cookieHeader() + "; retail_guest_orders="
                                + order.session().cookies.get("retail_guest_orders"))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(envelope)))
                .build();
        assertThat(mapper.readTree(client.send(duplicateCookie, HttpResponse.BodyHandlers.ofString())
                                .body())
                        .has("errors"))
                .isTrue();
        String literal = "mutation{cancelGuestOrder(input:{requestId:\"" + input.get("requestId") + "\",publicId:\""
                + order.publicId() + "\"}){requestId}}";
        var get = HttpRequest.newBuilder(java.net.URI.create(endpoint() + "?query="
                        + java.net.URLEncoder.encode(literal, java.nio.charset.StandardCharsets.UTF_8)))
                .header("Origin", "http://localhost:3000")
                .header("X-Guest-Cart-Request", "1")
                .header("Cookie", order.session().cookieHeader())
                .GET()
                .build();
        var getResponse = client.send(get, HttpResponse.BodyHandlers.ofString());
        assertThat(getResponse.statusCode() >= 400
                        || mapper.readTree(getResponse.body()).has("errors"))
                .isTrue();
        assertThat(stock()).isEqualTo(18);
        var accepted = HttpRequest.newBuilder(endpoint())
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("X-Guest-Cart-Request", "1")
                .header("Cookie", order.session().cookieHeader())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(envelope)))
                .build();
        var response = client.send(accepted, HttpResponse.BodyHandlers.ofString());
        assertThat(mapper.readTree(response.body())
                        .at("/data/answer/order/status")
                        .asString())
                .isEqualTo("CANCELLED");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(stock()).isEqualTo(20);
    }

    @Test
    void outboxFailureRollsBackAlreadyInsertedRefundAndCancellationReceipt() throws Exception {
        var order = placedGuest();
        successPayment(order, UUID.randomUUID());
        String fixture = "refund_outbox_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + fixture + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.order_id=" + order.id() + " AND NEW.event_type='refund.requested' THEN "
                + "RAISE EXCEPTION 'refund outbox rollback fixture'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + fixture + " BEFORE INSERT ON payment_outbox FOR EACH ROW EXECUTE FUNCTION "
                + fixture + "()");
        UUID request = UUID.randomUUID();
        try {
            assertThat(cancel(order, request, order.session(), null, "cancelGuestOrder")
                            .body()
                            .has("errors"))
                    .isTrue();
            assertThat(stock()).isEqualTo(18);
            assertThat(confirmation(order).at("/status").asString()).isEqualTo("PAID");
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM order_refunds WHERE order_id=?", Integer.class, order.id()))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM order_cancellation_requests WHERE request_id=?",
                            Integer.class,
                            request))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER " + fixture + " ON payment_outbox");
            jdbc.execute("DROP FUNCTION " + fixture + "()");
        }
        success(cancel(order, request, order.session(), null, "cancelGuestOrder"));
        assertThat(confirmation(order).at("/refund/status").asString()).isEqualTo("PENDING");
    }

    @Test
    void lateDeclineRecordsFinancialFailureWithoutAnotherReleaseOrRefund() throws Exception {
        var order = placedGuest();
        success(cancel(order, UUID.randomUUID(), order.session(), null, "cancelGuestOrder"));
        UUID payment = UUID.randomUUID();
        decline(order, payment);
        decline(order, payment);
        successPayment(order, payment);
        assertThat(confirmation(order).at("/payment/status").asString()).isEqualTo("FAILED");
        assertThat(confirmation(order).at("/refund/status").asString()).isEqualTo("NONE");
        assertThat(stock()).isEqualTo(20);
    }

    @Test
    void concurrentCancellationAndPaymentConvergeWithoutDoubleRestock() throws Exception {
        var order = placedGuest();
        try (var workers = Executors.newFixedThreadPool(3)) {
            var first =
                    workers.submit(() -> cancel(order, UUID.randomUUID(), order.session(), null, "cancelGuestOrder"));
            var second =
                    workers.submit(() -> cancel(order, UUID.randomUUID(), order.session(), null, "cancelGuestOrder"));
            var payment = workers.submit(() -> {
                successPayment(order, UUID.randomUUID());
                return true;
            });
            success(first.get(30, TimeUnit.SECONDS));
            success(second.get(30, TimeUnit.SECONDS));
            assertThat(payment.get(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(confirmation(order).at("/status").asString()).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(20);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM order_refunds WHERE order_id=?", Integer.class, order.id()))
                .isEqualTo(1);
    }

    @Test
    void cancellationRollbackLeavesStockOrderReceiptAndRefundUnchanged() throws Exception {
        var order = placedGuest();
        successPayment(order, UUID.randomUUID());
        String fixture = "cancel_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + fixture + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.order_id=" + order.id()
                + " THEN RAISE EXCEPTION 'cancellation rollback fixture'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + fixture + " BEFORE INSERT ON order_refunds FOR EACH ROW EXECUTE FUNCTION "
                + fixture + "()");
        UUID request = UUID.randomUUID();
        try {
            assertThat(cancel(order, request, order.session(), null, "cancelGuestOrder")
                            .body()
                            .has("errors"))
                    .isTrue();
            assertThat(stock()).isEqualTo(18);
            assertThat(confirmation(order).at("/status").asString()).isEqualTo("PAID");
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM order_cancellation_requests WHERE request_id=?",
                            Integer.class,
                            request))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER " + fixture + " ON order_refunds");
            jdbc.execute("DROP FUNCTION " + fixture + "()");
        }
        success(cancel(order, request, order.session(), null, "cancelGuestOrder"));
        assertThat(stock()).isEqualTo(20);
    }
}
