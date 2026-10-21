-- Callback acknowledgements only delete/reschedule their own durable rows.
-- They do not reserve call resources or authorize an external command.
INSERT INTO voice_flags (`key`,value) VALUES('__voice_callback_receipts__','0');
