package stripehttp

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func signed(body, secret string, at time.Time) string {
	timestamp := fmt.Sprint(at.Unix())
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(timestamp + "." + body))
	return "t=" + timestamp + ",v1=" + hex.EncodeToString(mac.Sum(nil))
}

func TestRawWebhookSignature(t *testing.T) {
	now := time.Now()
	body := `{"id":"evt_fixture","data":{"object":{}}}`
	secret := "whsec_fixture"
	signature := signed(body, secret, now)
	if !VerifySignature([]byte(body), signature, secret, now) {
		t.Fatal("valid raw-body signature rejected")
	}
	for _, invalid := range []struct{ body, signature, secret string }{
		{body + " ", signature, secret}, {body, signature, "whsec_other"},
		{body, signed(body, secret, now.Add(-6*time.Minute)), secret},
		{body, signed(body, secret, now.Add(6*time.Minute)), secret},
		{body, signature + ",t=1", secret}, {body, signature, ""},
	} {
		if VerifySignature([]byte(invalid.body), invalid.signature, invalid.secret, now) {
			t.Fatal("invalid webhook accepted")
		}
	}
	if !VerifySignature([]byte(body), signature+",v1=00", secret, now) {
		t.Fatal("rotated multiple signatures not supported")
	}
}

func TestSessionAndConfigurationRequireInternalCredential(t *testing.T) {
	h := &Handler{InternalToken: strings.Repeat("a", 48), Logger: slog.Default()}
	for _, path := range []string{"/internal/stripe/session", "/internal/stripe/config"} {
		mux := http.NewServeMux()
		h.Register(mux)
		req := httptest.NewRequest(http.MethodPost, path, strings.NewReader(`{}`))
		recorder := httptest.NewRecorder()
		mux.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusUnauthorized {
			t.Fatalf("%s: got %d", path, recorder.Code)
		}
	}
}

func TestInvalidWebhookIsRejectedBeforeDatabaseOrProvider(t *testing.T) {
	h := &Handler{SigningSecret: "whsec_fixture", Logger: slog.Default()}
	recorder := httptest.NewRecorder()
	h.Webhook(recorder, httptest.NewRequest(http.MethodPost, "/webhooks/stripe", strings.NewReader(`{}`)))
	if recorder.Code != http.StatusBadRequest {
		t.Fatal("invalid webhook not rejected")
	}
}
