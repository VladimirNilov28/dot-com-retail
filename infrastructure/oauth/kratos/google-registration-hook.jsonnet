local native = import 'registration-hook.jsonnet';
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
  native(ctx)
