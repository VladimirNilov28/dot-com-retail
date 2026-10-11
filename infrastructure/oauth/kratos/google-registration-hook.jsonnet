function(ctx)
  // Kratos omits private metadata from webhook identities. The OIDC mapper
  // validates subject/email; Kratos applies its verified-address evidence here.
  local addresses = if std.objectHas(ctx.identity, 'verifiable_addresses') &&
                       std.type(ctx.identity.verifiable_addresses) == 'array'
                    then ctx.identity.verifiable_addresses else [];
  assert std.length(std.filter(function(address)
    std.objectHas(address, 'via') && address.via == 'email' &&
    std.objectHas(address, 'value') && address.value == ctx.identity.traits.email &&
    std.objectHas(address, 'verified') && address.verified == true &&
    std.objectHas(address, 'status') && address.status == 'completed',
    addresses)) == 1
    : 'Google verified email does not match the registration.';
  {
    flow_id: ctx.flow.id,
    flow_type: ctx.flow.type,
    expires_at: ctx.flow.expires_at,
    schema_id: ctx.identity.schema_id,
    traits: {
      email: ctx.identity.traits.email,
      username: if std.objectHas(ctx.identity.traits, 'username') then ctx.identity.traits.username else null,
      dateOfBirth: if std.objectHas(ctx.identity.traits, 'dateOfBirth') then ctx.identity.traits.dateOfBirth else null,
    },
    altcha: if std.objectHas(ctx.flow, 'transient_payload') &&
              std.type(ctx.flow.transient_payload) == 'object' &&
              std.objectHas(ctx.flow.transient_payload, 'altcha')
           then ctx.flow.transient_payload.altcha
           else null,
  }
