def credential:
  type == "string" and length > 0 and (test("^replace-with") | not) and (test("^\\s*$") | not);

(env.GOOGLE_CLIENT_ID // "") as $client |
(env.GOOGLE_CLIENT_SECRET // "") as $secret |
if $client == "" and $secret == "" then
  .selfservice.methods.oidc = {enabled: false}
elif ($client | credential | not) or ($secret | credential | not) then
  error("Google OIDC requires both a client ID and a client secret.")
else
  (env.GOOGLE_CALLBACK_BASE_URL // "http://127.0.0.1:3000") as $base |
  if ($base | test("^(https://[A-Za-z0-9.-]+(:[0-9]+)?|http://127\\.0\\.0\\.1:3000)/?$") | not) then
    error("Google callback base must be an HTTPS origin or the local storefront origin.")
  else
    .selfservice.methods.oidc = {
      enabled: true,
      config: {
        base_redirect_uri: $base,
        providers: [{
          id: "google",
          provider: "google",
          label: "Google",
          client_id: $client,
          client_secret: $secret,
          scope: ["openid", "email", "profile"],
          mapper_url: "file:///etc/kratos/google-mapper.jsonnet",
          pkce: "never",
          account_linking_mode: "confirm_with_existing_credential"
        }]
      }
    } |
    .selfservice.flows.registration.after.password.hooks as $native |
    .selfservice.flows.registration.after.oidc.hooks = [
      ($native[0] | .config.body = "file:///etc/kratos/google-registration-hook.jsonnet"),
      $native[1],
      {hook: "session"}
    ]
  end
end
