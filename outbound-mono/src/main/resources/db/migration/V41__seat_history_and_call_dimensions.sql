CREATE TABLE ops_seat_state_sample (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 state VARCHAR(32) NOT NULL,event_at DATETIME(3) NOT NULL,
 KEY seat_day(tenant_id,event_at,user_id), KEY seat_history(tenant_id,user_id,event_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_line_group_assignment (
 tenant_id BIGINT NOT NULL,line_id BIGINT NOT NULL,group_name VARCHAR(100) NOT NULL,PRIMARY KEY(tenant_id,line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_call_dimension (
 tenant_id BIGINT NOT NULL,call_id CHAR(36) NOT NULL,actor_id BIGINT,team_id BIGINT,city VARCHAR(100),line_group VARCHAR(100),captured_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,call_id),KEY dims_owner(tenant_id,actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
