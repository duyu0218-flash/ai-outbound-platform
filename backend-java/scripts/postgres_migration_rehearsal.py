"""Isolated Docker fixture only: clone java_core, rehearse backup/apply/restore.

Requires the disposable java-core-postgres-20261015 test container; never accepts
a production URL. All temporary databases are dropped in finally.
"""
import subprocess,os,json,uuid,hashlib,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2];container='java-core-postgres-20261015';suffix=uuid.uuid4().hex[:10];db='java_migration_'+suffix;restored='java_restore_'+suffix
base=['docker','exec',container];tables=['tenant','user','contact','campaign','callsession','speechturn','callusage','recordingasset']
def command(args,**kw):return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw).stdout
def sql(database,statement):return command(base+['psql','-v','ON_ERROR_STOP=1','-U','postgres','-d',database,'-Atc',statement]).decode().strip()
def snap(database):
 result={}
 for table in tables:
  q='SELECT count(*),md5(COALESCE(string_agg(doc::text,\'\' ORDER BY doc::text),\'\')) FROM (SELECT to_jsonb(t)-\'voice_ai_pipeline\' AS doc FROM "'+table+'" t) s'
  result[table]=sql(database,q)
 return result
try:
 command(base+['createdb','-U','postgres','-T','java_core',db]);command(base+['createdb','-U','postgres',restored])
 sql(db,'ALTER TABLE callsession DROP COLUMN voice_ai_pipeline; ALTER TABLE taskoutbox DROP COLUMN lease_token; DROP TABLE callbackinboxworker;')
 before=snap(db)
 backup=command(base+['pg_dump','-U','postgres','-Fc',db]);sha=hashlib.sha256(backup).hexdigest()
 env=dict(os.environ,JAVA_DATABASE_URL='jdbc:postgresql://127.0.0.1:18545/'+db,DATABASE_USER='postgres',DATABASE_PASSWORD='isolated-java-test-password')
 java=str(root/'.tools/zulu8.96.0.205-ca-jdk8.0.504-macosx_aarch64/Contents/Home/bin/java');jar=str(root/'backend-java/target/outbound-control-0.1.0-SNAPSHOT.jar')
 def migrate(mode):return json.loads(command([java,'-jar',jar,'--database-migration='+mode],env=env))
 plan=migrate('plan');assert plan['writes'] is False;assert before==snap(db)
 applied=migrate('apply');assert len(applied['applied'])==22;assert before==snap(db)
 verified=migrate('verify');assert verified['compatible'];assert migrate('apply')['applied']==[]
 command(['docker','exec','-i',container,'pg_restore','-U','postgres','-d',restored,'--exit-on-error'],input=backup)
 assert before==snap(restored);assert sql(restored,"SELECT count(*) FROM information_schema.columns WHERE table_name='callsession' AND column_name='voice_ai_pipeline'")=='0'
 report=dict(passed=True,environment='isolated PostgreSQL16 clone with synthetic historical rows',plan_read_only=True,applied_versions=22,repeat_applied=0,backup_sha256=sha,backup_bytes=len(backup),restore_preserves_legacy_schema=True,business_snapshots=before,jar_sha256=hashlib.sha256(Path(jar).read_bytes()).hexdigest())
 (root/'backend-java/validation/postgres-migration-rehearsal.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
finally:
 for name in [db,restored]:subprocess.run(base+['dropdb','-U','postgres','--if-exists',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
