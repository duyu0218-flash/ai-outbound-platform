CREATE TABLE ops_seat_state_sample (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 state VARCHAR(32) NOT NULL,event_at DATETIME(3) NOT NULL,
 KEY seat_day(tenant_id,event_at,user_id), KEY seat_history(tenant_id,user_id,event_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TRIGGER ops_seat_state_created AFTER INSERT ON sys_user FOR EACH ROW
INSERT INTO ops_seat_state_sample(tenant_id,user_id,state,event_at)
SELECT NEW.tenant_id,NEW.id,NEW.agent_status,UTC_TIMESTAMP(3) FROM DUAL WHERE NEW.role='agent';
CREATE TRIGGER ops_seat_state_changed AFTER UPDATE ON sys_user FOR EACH ROW
INSERT INTO ops_seat_state_sample(tenant_id,user_id,state,event_at)
SELECT NEW.tenant_id,NEW.id,IF(NEW.enabled,NEW.agent_status,'offline'),UTC_TIMESTAMP(3) FROM DUAL
WHERE (NEW.role='agent' OR OLD.role='agent') AND
(NOT (NEW.agent_status <=> OLD.agent_status) OR NOT (NEW.last_seen_at <=> OLD.last_seen_at) OR NEW.enabled<>OLD.enabled OR NEW.role<>OLD.role);
CREATE TABLE ops_line_group_assignment (
 tenant_id BIGINT NOT NULL,line_id BIGINT NOT NULL,group_name VARCHAR(100) NOT NULL,PRIMARY KEY(tenant_id,line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_call_dimension (
 tenant_id BIGINT NOT NULL,call_id CHAR(36) NOT NULL,actor_id BIGINT,team_id BIGINT,city VARCHAR(100),line_group VARCHAR(100),captured_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,call_id),KEY dims_owner(tenant_id,actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
