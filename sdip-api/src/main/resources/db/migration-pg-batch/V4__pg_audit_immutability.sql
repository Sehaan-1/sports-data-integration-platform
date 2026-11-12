-- PostgreSQL-only hardening: make the audit trail tamper-resistant at the
-- database level (§4.5). UPDATE/DELETE against audit_event become no-ops,
-- so even a compromised application credential cannot rewrite history.
CREATE OR REPLACE RULE audit_event_no_update AS ON UPDATE TO audit_event DO INSTEAD NOTHING;
CREATE OR REPLACE RULE audit_event_no_delete AS ON DELETE TO audit_event DO INSTEAD NOTHING;
