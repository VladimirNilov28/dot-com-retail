function(ctx) {
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
