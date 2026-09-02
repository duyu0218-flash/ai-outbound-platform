"""Public synthetic values for isolated tests, never deployment credentials."""
SECURITY_SETTINGS = dict(
    service_token="synthetic-service-" + "s" * 32,
    voice_command_secret="synthetic-command-" + "c" * 32,
    voice_security_admin_token="synthetic-admin-" + "a" * 32,
    webhook_token="synthetic-callback-" + "t" * 32,
    webhook_secret="synthetic-signing-" + "h" * 32,
    voice_security_db_path="/not-opened-by-runtime-validation/ledger.sqlite3",
    voice_callback_base_url="http://control-api:8000",
    voice_callback_allow_private_http=True,
)
