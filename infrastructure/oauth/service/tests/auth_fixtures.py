def active_session(aal="aal1", identity_id="kratos-identity-uuid"):
    method = "totp" if aal == "aal2" else "password"
    return {
        "id": "kratos-session-uuid",
        "active": True,
        "authenticator_assurance_level": aal,
        "authentication_methods": [{"method": method, "aal": aal}],
        "identity": {"id": identity_id},
    }
