local native = import 'registration-hook.jsonnet';
function(ctx)
  local proof = ctx.identity.metadata_admin.bytecore_oidc;
  assert proof.provider == 'google' &&
         std.type(proof.subject) == 'string' && std.length(proof.subject) > 0 &&
         proof.email == ctx.identity.traits.email
    : 'Google account proof does not match the registration.';
  native(ctx)
