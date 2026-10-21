-- The journal itself is the recovery outbox, including synchronous API claims.
-- Leases never establish a negative PBX result or release a reservation.
ALTER TABLE dial_command_journal
 ADD COLUMN rejection_proof LONGTEXT,
 ADD COLUMN recovery_owner VARCHAR(128),
 ADD COLUMN recovery_until DATETIME(3),
 ADD COLUMN recovery_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 ADD COLUMN manual_hold_at DATETIME(3),
 ADD KEY dial_command_recovery_due(state,recovery_at,created_at);
