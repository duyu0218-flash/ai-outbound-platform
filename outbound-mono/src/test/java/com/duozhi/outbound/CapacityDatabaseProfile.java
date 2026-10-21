package com.duozhi.outbound;

import java.sql.*;
import java.util.*;
import org.testcontainers.containers.MySQLContainer;

/** Explicit isolated-host resources, recorded independently from SLO assertions. */
final class CapacityDatabaseProfile {
    static void apply(MySQLContainer<?> mysql,Map<String,Object> result){
        var command=new ArrayList<String>();var overrides=new LinkedHashMap<String,Integer>();
        setting(command,overrides,"bufferMiB","--innodb-buffer-pool-size",2048,1024L*1024);
        setting(command,overrides,"tableCache","--table-open-cache",4096,1);
        setting(command,overrides,"packetMiB","--max-allowed-packet",64,1024L*1024);
        setting(command,overrides,"redoMiB","--innodb-redo-log-capacity",1024,1024L*1024);
        boolean compress=Boolean.getBoolean("capacity.mysql.binlogCompression");
        if(compress)command.add("--binlog-transaction-compression=ON");
        if(!command.isEmpty()){
            command.addAll(List.of("--innodb-flush-log-at-trx-commit=1","--sync-binlog=1","--transaction-isolation=REPEATABLE-READ"));
            mysql.withCommand(command.toArray(String[]::new));
        }
        result.put("mysql_resource_overrides",overrides);result.put("mysql_buffer_pool_override_mib",overrides.get("bufferMiB"));result.put("mysql_binlog_compression_override",compress);
    }
    private static void setting(List<String> command,Map<String,Integer> overrides,String key,String option,int maximum,long scale){
        int value=Integer.getInteger("capacity.mysql."+key,0);if(value<0||value>maximum)throw new IllegalArgumentException("invalid isolated MySQL "+key);
        overrides.put(key,value);if(value>0)command.add(option+"="+(value*scale));
    }
    static Map<String,String> actual(String jdbc,String user,String password)throws SQLException{
        var values=new LinkedHashMap<String,String>();
        try(var connection=DriverManager.getConnection(jdbc,user,password);var statement=connection.createStatement();var rows=statement.executeQuery("SHOW VARIABLES WHERE Variable_name IN ('version','version_comment','innodb_buffer_pool_size','table_open_cache','max_allowed_packet','innodb_redo_log_capacity','innodb_flush_log_at_trx_commit','sync_binlog','transaction_isolation','log_bin','binlog_row_image','binlog_transaction_compression','binlog_group_commit_sync_delay')")){
            while(rows.next())values.put(rows.getString(1),rows.getString(2));
        }return values;
    }
}
