package com.duozhi.outbound;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Explicit maintenance command. Never starts HTTP, workers or demo accounts. */
public final class DatabaseMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOCK = System.getenv().getOrDefault("DATABASE_MIGRATION_LOCK_NAME","ai-outbound-schema-migrations");
    private final Connection connection;
    private final boolean postgres;
    private final Map<String,Map<String,Map<String,Object>>> tables;
    private final List<String> schema;
    private final List<String> runtime;
    private final LinkedHashMap<String,String> migrations = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public DatabaseMigration(Connection connection) throws Exception {
        this.connection=connection;
        postgres=connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
        if(!postgres&&!connection.getMetaData().getDatabaseProductName().equals("SQLite"))throw new IllegalArgumentException("unsupported database");
        tables=JSON.readValue(resource("contracts/tables.json"),new TypeReference<Map<String,Map<String,Map<String,Object>>>>(){});
        schema=Arrays.asList(resource("contracts/schema-"+(postgres?"postgresql":"sqlite")+".sql").split(";"));
        runtime=JSON.readValue(resource("migrations/runtime-additions.json"),new TypeReference<List<String>>(){});
        Map<String,Object> manifest=JSON.readValue(resource("migrations/manifest.json"),new TypeReference<Map<String,Object>>(){});
        if(postgres)for(Map<String,String> item:(List<Map<String,String>>)manifest.get("postgresql")) {
            String source=resource("migrations/postgresql/"+item.get("version"));
            if(!sha(source).equals(item.get("sha256")))throw new IllegalStateException("packaged migration checksum mismatch: "+item.get("version"));
            migrations.put(item.get("version"),source);
        }
        // Additive compatibility pass also covers old SQLite databases and legacy
        // PostgreSQL installations bootstrapped outside the versioned runner.
        migrations.put("java_20261016_additive_compatibility",JSON.writeValueAsString(runtime)+resource("contracts/schema-"+(postgres?"postgresql":"sqlite")+".sql"));
    }
    static String resource(String path) throws IOException {
        try(InputStream in=DatabaseMigration.class.getClassLoader().getResourceAsStream(path)) {
            if(in==null)throw new IOException("missing resource: "+path);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int count;
            while((count=in.read(bytes))!=-1)out.write(bytes,0,count);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static String sha(String source) throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)))out.append(String.format("%02x",b&255));return out.toString();
    }
    private void execute(String sql) throws SQLException {try(Statement statement=connection.createStatement()){statement.execute(sql);}}
    private Set<String> names(String table) throws SQLException {
        Set<String> names=new TreeSet<>();
        String sql=postgres ? table==null?"SELECT table_name FROM information_schema.tables WHERE table_schema=current_schema()":"SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=?"
                :table==null?"SELECT name FROM sqlite_master WHERE type='table'":"SELECT name FROM pragma_table_info(?)";
        try(PreparedStatement query=connection.prepareStatement(sql)){if(table!=null)query.setString(1,table);try(ResultSet rs=query.executeQuery()){while(rs.next())names.add(rs.getString(1));}}return names;
    }
    private Map<String,String> history() throws Exception {
        Map<String,String> result=new LinkedHashMap<>();if(!names(null).contains("schema_migration_history"))return result;
        try(Statement query=connection.createStatement();ResultSet rs=query.executeQuery("SELECT version,checksum_sha256 FROM schema_migration_history ORDER BY version")){while(rs.next())result.put(rs.getString(1),rs.getString(2));}
        for(Map.Entry<String,String> migration:migrations.entrySet())if(result.containsKey(migration.getKey())&&!sha(migration.getValue()).equals(result.get(migration.getKey())))throw new IllegalStateException("checksum changed for applied migration: "+migration.getKey());
        return result;
    }
    public Map<String,Object> plan() throws Exception {
        Map<String,String> applied=history();List<String> pending=new ArrayList<>();for(String version:migrations.keySet())if(!applied.containsKey(version))pending.add(version);
        return JsonSupport.map("dialect",postgres?"postgresql":"sqlite","pending",pending,"schema_gaps",gaps(),"integrity_conflicts",conflicts(),"writes",false);
    }
    private Map<String,List<List<String>>> uniques() {
        Map<String,List<List<String>>> result=new LinkedHashMap<>();
        for(String raw:schema){String table=group(raw.trim(),"^CREATE TABLE [\"]?(\\w+)",1);if(table==null)continue;
            Matcher matcher=Pattern.compile("UNIQUE \\(([^)]+)\\)").matcher(raw);
            while(matcher.find()){List<String> columns=new ArrayList<>();for(String column:matcher.group(1).split(","))columns.add(column.trim().replace("\"",""));result.computeIfAbsent(table,k->new ArrayList<>()).add(columns);}
        }return result;
    }
    private boolean hasUnique(String table,List<String> columns) throws SQLException {
        Map<String,Set<String>> indexes=new HashMap<>();
        try(ResultSet rs=connection.getMetaData().getIndexInfo(null,postgres?connection.getSchema():null,table,true,false)){
            while(rs.next()){String index=rs.getString("INDEX_NAME"),column=rs.getString("COLUMN_NAME");if(index!=null&&column!=null)indexes.computeIfAbsent(index,k->new HashSet<>()).add(column);}
        }return indexes.values().contains(new HashSet<>(columns));
    }
    private List<String> conflicts() throws SQLException {
        List<String> conflicts=new ArrayList<>();Set<String> existing=names(null);
        for(Map.Entry<String,List<List<String>>> table:uniques().entrySet())if(existing.contains(table.getKey()))for(List<String> columns:table.getValue()){
            if(!names(table.getKey()).containsAll(columns))continue;
            List<String> filters=new ArrayList<>();for(String column:columns)filters.add("\""+column+"\" IS NOT NULL");
            String grouped="\""+String.join("\",\"",columns)+"\"";
            try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT 1 FROM \""+table.getKey()+"\" WHERE "+String.join(" AND ",filters)+" GROUP BY "+grouped+" HAVING COUNT(*)>1 LIMIT 1")){if(r.next())conflicts.add("duplicate:"+table.getKey()+"("+String.join(",",columns)+")");}
        }return conflicts;
    }
    public List<String> gaps() throws SQLException {
        Set<String> existing=names(null);List<String> missing=new ArrayList<>();
        for(Map.Entry<String,Map<String,Map<String,Object>>> table:tables.entrySet()) {
            if(!existing.contains(table.getKey())){missing.add("table:"+table.getKey());continue;}
            Set<String> columns=names(table.getKey());for(String column:table.getValue().keySet())if(!columns.contains(column))missing.add("column:"+table.getKey()+"."+column);
        }
        for(Map.Entry<String,List<List<String>>> table:uniques().entrySet())if(existing.contains(table.getKey()))for(List<String> columns:table.getValue())if(!hasUnique(table.getKey(),columns))missing.add("unique:"+table.getKey()+"("+String.join(",",columns)+")");
        if(postgres)for(String raw:schema){String type=group(raw.trim(),"^CREATE TYPE (\\w+)",1);if(type==null)continue;
            Set<String> actual=new HashSet<>();try(PreparedStatement q=connection.prepareStatement("SELECT e.enumlabel FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid JOIN pg_namespace n ON n.oid=t.typnamespace WHERE t.typname=? AND n.nspname=current_schema()")){q.setString(1,type);try(ResultSet r=q.executeQuery()){while(r.next())actual.add(r.getString(1));}}
            Matcher labels=Pattern.compile("'([^']+)'").matcher(raw);while(labels.find())if(!actual.contains(labels.group(1)))missing.add("enum:"+type+"."+labels.group(1));
        }
        return missing;
    }
    public void verify() throws Exception {history();List<String> missing=gaps();if(!missing.isEmpty())throw new IllegalStateException("database schema is incompatible: "+String.join(", ",missing));}
    private void remember(String version,String source) throws Exception {
        try(PreparedStatement insert=connection.prepareStatement("INSERT INTO schema_migration_history(version,checksum_sha256) VALUES (?,?)")){insert.setString(1,version);insert.setString(2,sha(source));insert.executeUpdate();}
    }
    private static String group(String sql,String pattern,int index){Matcher m=Pattern.compile(pattern,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(sql);return m.find()?m.group(index):null;}
    private void bootstrap() throws Exception {
        Set<String> existing=names(null);
        for(String raw:schema){String sql=raw.trim();String type=group(sql,"^CREATE TYPE (\\w+)",1);if(type!=null){
            boolean found;try(PreparedStatement q=connection.prepareStatement("SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE t.typname=? AND n.nspname=current_schema()")){q.setString(1,type);try(ResultSet rs=q.executeQuery()){found=rs.next();}}
            if(!found)execute(sql);
        }}
        for(String raw:schema){String sql=raw.trim(),table=group(sql,"^CREATE TABLE [\"]?(\\w+)",1);if(table!=null&&!existing.contains(table))execute(sql);}
    }
    private void additive() throws Exception {
        for(String sql:runtime){String table=group(sql,"^ALTER TABLE [\"]?(\\w+)[\"]? ADD COLUMN (\\w+)",1),column=group(sql,"^ALTER TABLE [\"]?(\\w+)[\"]? ADD COLUMN (\\w+)",2);
            if(table!=null){if(!names(table).contains(column))execute(sql);}
            else execute(sql);
        }
        for(String raw:schema){String sql=raw.trim();if(sql.matches("(?s)^CREATE (UNIQUE )?INDEX .*"))execute(sql.replaceFirst("INDEX ","INDEX IF NOT EXISTS "));}
        for(Map.Entry<String,List<List<String>>> table:uniques().entrySet())for(List<String> columns:table.getValue())if(!hasUnique(table.getKey(),columns)){
            String name="java_uq_"+table.getKey()+"_"+sha(String.join(",",columns)).substring(0,12);
            execute("CREATE UNIQUE INDEX "+name+" ON \""+table.getKey()+"\" (\""+String.join("\",\"",columns)+"\")");
        }
        // New enum labels must be committed before any application uses them.
        if(postgres)for(String raw:schema){String sql=raw.trim(),type=group(sql,"^CREATE TYPE (\\w+)",1);if(type!=null){Matcher labels=Pattern.compile("'([^']+)'").matcher(sql);while(labels.find())execute("ALTER TYPE "+type+" ADD VALUE IF NOT EXISTS '"+labels.group(1)+"'");}}
        for(int id=0;id<64;id++)execute("INSERT INTO callbackinboxpartition(id,pending_count,pending_bytes) VALUES ("+id+",0,0) ON CONFLICT(id) DO NOTHING");
    }
    public List<String> apply() throws Exception {
        if(!connection.getAutoCommit())throw new IllegalStateException("migration requires a dedicated connection");
        List<String> completed=new ArrayList<>();boolean locked=false;
        try {
            if(postgres){try(PreparedStatement lock=connection.prepareStatement("SELECT pg_advisory_lock(hashtext(?))")){lock.setString(1,LOCK);lock.execute();}locked=true;}
            // Check every historical checksum before the first DDL statement.
            Map<String,String> applied=history();List<String> conflicts=conflicts();if(!conflicts.isEmpty())throw new IllegalStateException("migration integrity conflicts; resolve without discarding history: "+conflicts);connection.setAutoCommit(false);
            execute("CREATE TABLE IF NOT EXISTS schema_migration_history(version VARCHAR(255) PRIMARY KEY,checksum_sha256 VARCHAR(64) NOT NULL,applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            bootstrap();connection.commit();
            // Re-read after obtaining the next SQLite write transaction; another migrator
            // may have completed between per-version commits.
            applied=history();
            for(Map.Entry<String,String> migration:migrations.entrySet()) {
                if(history().containsKey(migration.getKey()))continue;
                try {
                    if(migration.getKey().startsWith("java_"))additive();
                    else execute(migration.getValue().replaceFirst("(?im)^\\s*BEGIN\\s*;\\s*$","").replaceFirst("(?im)^\\s*COMMIT\\s*;\\s*$",""));
                    remember(migration.getKey(),migration.getValue());connection.commit();completed.add(migration.getKey());
                }catch(Exception failure){connection.rollback();throw new IllegalStateException("migration failed and current version rolled back: "+migration.getKey()+"; previously committed versions="+completed,failure);}
            }
            verify();connection.commit();return completed;
        }finally {
            if(!connection.getAutoCommit()){connection.rollback();connection.setAutoCommit(true);}
            if(locked)try(PreparedStatement unlock=connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")){unlock.setString(1,LOCK);unlock.execute();}
        }
    }
    public static void main(String[] args) throws Exception {
        String mode=args.length==0?"plan":args[0];if(!Arrays.asList("plan","apply","verify").contains(mode))throw new IllegalArgumentException("use plan, apply or verify");
        String url=System.getenv("JAVA_DATABASE_URL");if(url==null)throw new IllegalArgumentException("JAVA_DATABASE_URL is required; no implicit database target");
        if(!url.startsWith("jdbc:postgresql:")&&!url.startsWith("jdbc:sqlite:"))throw new IllegalArgumentException("unsupported database URL");
        if(url.startsWith("jdbc:sqlite:")&&!url.contains(":memory:")&&!Files.isRegularFile(Paths.get(url.substring("jdbc:sqlite:".length()))))throw new IllegalArgumentException("SQLite database must already exist; create fresh databases with the separate bootstrap command");
        Properties props=new Properties();props.setProperty("user",System.getenv().getOrDefault("DATABASE_USER",""));props.setProperty("password",System.getenv().getOrDefault("DATABASE_PASSWORD",""));props.setProperty("transaction_mode","IMMEDIATE");
        try(Connection connection=DriverManager.getConnection(url,props)) {
            if(url.startsWith("jdbc:sqlite:"))try(Statement s=connection.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}
            DatabaseMigration migration=new DatabaseMigration(connection);
            if("apply".equals(mode))System.out.println(JSON.writeValueAsString(JsonSupport.map("applied",migration.apply(),"schema_gaps",migration.gaps())));
            else if("verify".equals(mode)){migration.verify();System.out.println("{\"compatible\":true}");}
            else System.out.println(JSON.writeValueAsString(migration.plan()));
        }
    }
}
