// Maps OIDC claims from cuenta.digital.gob.do into Kratos identity traits.
// Standard OIDC claims: email, given_name, family_name.
// Role defaults to "employee" (per identity.schema.json); not mapped from IdP.
local claims = std.extVar('claims');

{
  identity: {
    traits: {
      email: claims.email,
      [if 'given_name' in claims || 'family_name' in claims then 'name']: {
        [if 'given_name' in claims then 'first']: claims.given_name,
        [if 'family_name' in claims then 'last']: claims.family_name,
      },
    },
  },
}
