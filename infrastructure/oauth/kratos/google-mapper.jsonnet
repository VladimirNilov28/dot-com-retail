local claims = std.extVar('claims');
assert std.objectHas(claims, 'sub') && std.type(claims.sub) == 'string' && std.length(claims.sub) > 0
  : 'Google did not supply a subject.';
assert std.objectHas(claims, 'email') && std.type(claims.email) == 'string' && std.length(claims.email) > 0
  : 'Google did not supply an email.';
assert std.objectHas(claims, 'email_verified') && claims.email_verified == true
  : 'Google email must be verified.';
{
  identity: {
    traits: { email: claims.email },
    verified_addresses: [{ via: 'email', value: claims.email }],
    metadata_admin: {
      bytecore_oidc: { provider: 'google', subject: claims.sub, email: claims.email },
    },
  },
}
