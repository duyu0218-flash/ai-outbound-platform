package com.duozhi.outbound;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DatabaseMigrationTest {
    interface Work {void run(Connection connection) throws Exception;}
    void databases(Work work) throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite::memory:")){work.run(c);}
        String url=System.getenv("JAVA_TEST_DATABASE_URL");
        if(url!=null&&url.startsWith("jdbc:postgresql:"))try(Connection c=DriverManager.getConnection(url,System.getenv("DATABASE_USER"),System.getenv("DATABASE_PASSWORD"))) {
            String schema="migration_"+UUID.randomUUID().toString().replace("-","");
            try(Statement s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);s.execute("SET search_path="+schema);}
            try{work.run(c);}finally{try(Statement s=c.createStatement()){s.execute("SET search_path=public");s.execute("DROP SCHEMA "+schema+" CASCADE");}}
        }
    }
    void sql(Connection c,String sql) throws Exception {try(Statement s=c.createStatement()){s.execute(sql);}}
    long count(Connection c,String sql) throws Exception {try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    void baseline(Connection c) throws Exception {
        String dialect=c.getMetaData().getDatabaseProductName().equals("PostgreSQL")?"postgresql":"sqlite";
        for(String sql:DatabaseMigration.resource("contracts/schema-"+dialect+".sql").split(";"))if(!sql.trim().isEmpty())sql(c,sql);
        sql(c,"INSERT INTO tenant(id,name,code,enabled,created_at,updated_at) VALUES(900001,'Historical tenant','historical',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }
    @Test
    void legacyColumnsAndNewTablesAreRepairedWithoutChangingHistoricalRows() throws Exception {
        databases(c->{baseline(c);sql(c,"ALTER TABLE callsession DROP COLUMN voice_ai_pipeline");sql(c,"ALTER TABLE taskoutbox DROP COLUMN lease_token");sql(c,"DROP TABLE callbackinboxworker");
            DatabaseMigration migration=new DatabaseMigration(c);assertFalse(migration.gaps().isEmpty());
            Map<String,Object> plan=migration.plan();assertEquals(false,plan.get("writes"));assertTrue(migration.gaps().contains("column:callsession.voice_ai_pipeline"));
            assertThrows(IllegalStateException.class,migration::verify);assertFalse(migration.apply().isEmpty());migration.verify();
            assertEquals(1,count(c,"SELECT COUNT(*) FROM tenant WHERE id=900001 AND name='Historical tenant'"));
            assertEquals(64,count(c,"SELECT COUNT(*) FROM callbackinboxpartition"));
            assertTrue(migration.apply().isEmpty());assertEquals(1,count(c,"SELECT COUNT(*) FROM tenant"));
        });
    }
    @Test
    void migrationPreservesCredentialsConsentCallIdentityAndTranscript() throws Exception {
        databases(c->{baseline(c);
            boolean pg=c.getMetaData().getDatabaseProductName().equals("PostgreSQL");String id=pg?"11111111-2222-4333-8444-555555555555":"11111111222243338444555555555555";
            sql(c,"INSERT INTO \"user\"(id,tenant_id,username,password_hash,full_name,role,is_supervisor,agent_status,failed_login_attempts,token_version,enabled,created_at,updated_at) VALUES(900001,900001,'legacy-user','pbkdf2_sha256$600000$preserved$bytes','Historical user','admin',FALSE,'offline',2,4,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql(c,"INSERT INTO contact(id,tenant_id,phone,name,tags,consent_state,dnc,dnc_reason,created_at,updated_at) VALUES(900001,900001,'13900000001','Historical contact','legacy','CONSENTED',TRUE,'requested',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql(c,"INSERT INTO callsession(id,tenant_id,contact_id,phone,mode,status,attempts,max_attempts,voice_ai_pipeline,recording_url,created_at,updated_at) VALUES('"+id+"',900001,900001,'13900000001','HUMAN_ONLY','COMPLETED',2,3,'legacy','https://recording.invalid/archive',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql(c,"INSERT INTO speechturn(id,tenant_id,call_session_id,provider_event_key,turn_index,attempt,speaker_role,channel_id,transcript,normalized_transcript,is_final,asr_provider,created_at) VALUES(900001,900001,'"+id+"','legacy-event',1,2,'customer','mono','Historical transcript','Historical transcript',TRUE,'legacy',CURRENT_TIMESTAMP)");
            sql(c,"ALTER TABLE callsession DROP COLUMN voice_ai_pipeline");new DatabaseMigration(c).apply();
            assertEquals(1,count(c,"SELECT COUNT(*) FROM \"user\" WHERE password_hash='pbkdf2_sha256$600000$preserved$bytes' AND token_version=4 AND failed_login_attempts=2"));
            assertEquals(1,count(c,"SELECT COUNT(*) FROM contact WHERE consent_state='CONSENTED' AND dnc=TRUE AND dnc_reason='requested'"));
            assertEquals(1,count(c,"SELECT COUNT(*) FROM callsession c JOIN speechturn s ON s.call_session_id=c.id WHERE c.id='"+id+"' AND c.status='COMPLETED' AND c.attempts=2 AND s.transcript='Historical transcript' AND s.attempt=2 AND c.recording_url='https://recording.invalid/archive'"));
            assertTrue(new DatabaseMigration(c).apply().isEmpty());
        });
    }
    @Test
    void pythonPostgresHistoryIsReusedWithoutRenamingOrReexecuting() throws Exception {
        databases(c->{if(!c.getMetaData().getDatabaseProductName().equals("PostgreSQL"))return;baseline(c);new DatabaseMigration(c).apply();
            long pythonVersions=count(c,"SELECT COUNT(*) FROM schema_migration_history WHERE version NOT LIKE 'java_%'");assertEquals(21,pythonVersions);
            sql(c,"DELETE FROM schema_migration_history WHERE version='java_20261016_additive_compatibility'");
            assertEquals(Collections.singletonList("java_20261016_additive_compatibility"),new DatabaseMigration(c).apply());
            assertEquals(pythonVersions,count(c,"SELECT COUNT(*) FROM schema_migration_history WHERE version NOT LIKE 'java_%'"));
        });
    }
    @Test
    void changedHistoryIsRejectedBeforeAnyWrite() throws Exception {
        databases(c->{baseline(c);DatabaseMigration migration=new DatabaseMigration(c);migration.apply();
            sql(c,"UPDATE schema_migration_history SET checksum_sha256='changed' WHERE version='java_20261016_additive_compatibility'");
            sql(c,"ALTER TABLE taskoutbox DROP COLUMN lease_token");
            assertThrows(IllegalStateException.class,migration::apply);
            assertTrue(migration.gaps().contains("column:taskoutbox.lease_token"));
            assertEquals(1,count(c,"SELECT COUNT(*) FROM tenant"));
        });
    }
    @Test
    void duplicateLegacyContactsBlockMigrationBeforeDdl() throws Exception {
        databases(c->{
            sql(c,"CREATE TABLE contact(id INTEGER PRIMARY KEY,tenant_id INTEGER,phone VARCHAR(32))");
            sql(c,"INSERT INTO contact VALUES(1,1,'13900000001'),(2,1,'13900000001')");
            DatabaseMigration migration=new DatabaseMigration(c);
            assertFalse(((List<?>)migration.plan().get("integrity_conflicts")).isEmpty());
            assertThrows(IllegalStateException.class,migration::apply);
            assertEquals(2,count(c,"SELECT COUNT(*) FROM contact"));
            assertThrows(SQLException.class,()->count(c,"SELECT COUNT(*) FROM schema_migration_history"));
        });
    }
    @Test
    void failedVersionRollsBackItsDdlAndHistory() throws Exception {
        databases(c->{baseline(c);sql(c,"ALTER TABLE taskoutbox DROP COLUMN lease_token");
            // The final parity pass must fail on an incompatible base column.
            sql(c,"ALTER TABLE campaign RENAME COLUMN status TO legacy_status");
            DatabaseMigration migration=new DatabaseMigration(c);
            assertThrows(IllegalStateException.class,migration::apply);
            assertEquals(0,count(c,"SELECT COUNT(*) FROM schema_migration_history WHERE version='java_20261016_additive_compatibility'"));
            assertTrue(migration.gaps().contains("column:taskoutbox.lease_token"));
            assertEquals(1,count(c,"SELECT COUNT(*) FROM tenant"));
        });
    }
    @Test
    void alreadyMigratedSqliteSnapshotCanBeBackedUpAndRestored() throws Exception {
        Path path=Files.createTempFile("java-migration-",".db"),backup=Files.createTempFile("java-backup-",".db");
        try {
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path)){baseline(c);sql(c,"ALTER TABLE callsession DROP COLUMN voice_ai_pipeline");}
            Files.copy(path,backup,StandardCopyOption.REPLACE_EXISTING);byte[] original=Files.readAllBytes(backup);
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path)){new DatabaseMigration(c).apply();}
            assertFalse(Arrays.equals(original,Files.readAllBytes(path)));
            Files.copy(backup,path,StandardCopyOption.REPLACE_EXISTING);
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path)){assertTrue(new DatabaseMigration(c).gaps().contains("column:callsession.voice_ai_pipeline"));assertEquals(1,count(c,"SELECT COUNT(*) FROM tenant"));}
            assertArrayEquals(original,Files.readAllBytes(backup));
        }finally{Files.deleteIfExists(path);Files.deleteIfExists(backup);}
    }
}
